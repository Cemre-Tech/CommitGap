package com.example.commitgap.core.scenario;

import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.model.FaultAction;
import com.example.commitgap.core.model.FaultTarget;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.RecoveryAction;
import com.example.commitgap.core.model.Strategy;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Parses and validates scenario YAML. The loader is strict on purpose: duplicate keys, unknown
 * fields, unknown checkpoints or actions, unsupported schema versions and options that make no sense
 * for the chosen action are all rejected with a message naming the field. Scenario files are data
 * only; nothing in them is executed.
 */
public final class ScenarioLoader {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int MAX_ORDERS = 500;

    private static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "id", "description", "workload",
            "fault", "recovery", "observation", "assertions", "expectedByStrategy");
    private static final Set<String> WORKLOAD_KEYS = Set.of("seed", "orders", "quantityPerOrder",
            "initialStock", "rollbackEvery", "consumerWorkers");
    private static final Set<String> FAULT_KEYS = Set.of("target", "action", "checkpoint", "occurrence",
            "copies", "atOrder", "ordersDuringFault");
    private static final Map<FaultAction, Set<String>> FAULT_KEYS_BY_ACTION = Map.of(
            FaultAction.KILL, Set.of("target", "action", "checkpoint", "occurrence"),
            FaultAction.DUPLICATE_PUBLISH, Set.of("target", "action", "occurrence", "copies"),
            FaultAction.NETWORK_CUT, Set.of("target", "action", "atOrder", "ordersDuringFault"));
    private static final Set<String> RECOVERY_KEYS = Set.of("action");
    private static final Set<String> OBSERVATION_KEYS = Set.of("timeoutSeconds", "quietPeriodMillis");

    public Scenario load(Path file) {
        String source = file.getFileName().toString();
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ScenarioValidationException(source, List.of("cannot read file: " + e.getMessage()));
        }
        return parse(text, source);
    }

    public Scenario parse(String yamlText, String source) {
        Object root = readYaml(yamlText, source);
        Problems problems = new Problems();
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new ScenarioValidationException(source, List.of("the document must be a mapping of fields"));
        }
        Map<String, Object> doc = asStringKeyed(rootMap, "", problems);
        checkKeys(doc, ROOT_KEYS, "", problems);

        Integer schemaVersion = requireInt(doc, "schemaVersion", "", 1, Integer.MAX_VALUE, problems);
        if (schemaVersion != null && schemaVersion != Scenario.SUPPORTED_SCHEMA_VERSION) {
            throw new ScenarioValidationException(source, List.of("unsupported schemaVersion " + schemaVersion
                    + " (this CommitGap version supports " + Scenario.SUPPORTED_SCHEMA_VERSION + ")"));
        }

        String id = requireString(doc, "id", "", problems);
        if (id != null && (!ID_PATTERN.matcher(id).matches() || id.length() > 64)) {
            problems.add("id: '" + id + "' must be lowercase letters, digits and single dashes (max 64 characters)");
        }
        String description = requireString(doc, "description", "", problems);

        Workload workload = parseWorkload(section(doc, "workload", "", true, problems), problems);
        Optional<Fault> fault = parseFault(section(doc, "fault", "", false, problems), workload, problems);
        boolean faultDeclared = doc.get("fault") != null;
        Recovery recovery = parseRecovery(section(doc, "recovery", "", false, problems), fault, faultDeclared,
                problems);
        Observation observation = parseObservation(section(doc, "observation", "", true, problems), problems);
        List<InvariantId> assertions = parseAssertions(doc.get("assertions"), problems);
        Map<Strategy, Outcome> expected = parseExpected(section(doc, "expectedByStrategy", "", true, problems),
                fault, problems);

        if (!problems.isEmpty()) {
            throw new ScenarioValidationException(source, problems.list);
        }
        return new Scenario(schemaVersion, id, description, workload, fault, recovery, observation,
                assertions, expected, source);
    }

    private static Object readYaml(String text, String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(256 * 1024);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            Object root = yaml.load(text);
            if (root == null) {
                throw new ScenarioValidationException(source, List.of("the file is empty"));
            }
            return root;
        } catch (MarkedYAMLException e) {
            String where = e.getProblemMark() == null ? "" : " (line " + (e.getProblemMark().getLine() + 1) + ")";
            String problem = e.getProblem() == null ? e.getMessage() : e.getProblem();
            throw new ScenarioValidationException(source, List.of("YAML error" + where + ": " + problem));
        } catch (YAMLException e) {
            throw new ScenarioValidationException(source, List.of("YAML error: " + e.getMessage()));
        }
    }

    private Workload parseWorkload(Map<String, Object> m, Problems p) {
        if (m == null) {
            return null;
        }
        String path = "workload.";
        checkKeys(m, WORKLOAD_KEYS, path, p);
        Long seed = requireLong(m, "seed", path, p);
        Integer orders = requireInt(m, "orders", path, 1, MAX_ORDERS, p);
        Integer quantity = requireInt(m, "quantityPerOrder", path, 1, 1000, p);
        Integer initialStock = requireInt(m, "initialStock", path, 0, 10_000_000, p);
        int rollbackEvery = optionalInt(m, "rollbackEvery", path, 0, MAX_ORDERS, 0, p);
        int consumerWorkers = optionalInt(m, "consumerWorkers", path, 1, 8, 1, p);
        if (rollbackEvery == 1) {
            p.add("workload.rollbackEvery: 1 would roll back every order; use 0 (no rollbacks) or a value of 2 or more");
        }
        if (orders != null && rollbackEvery > orders) {
            p.add("workload.rollbackEvery: " + rollbackEvery + " is larger than workload.orders (" + orders
                    + "), so no order would be rolled back; use 0 to disable rollbacks");
        }
        if (seed == null || orders == null || quantity == null || initialStock == null) {
            return null;
        }
        return new Workload(seed, orders, quantity, initialStock, rollbackEvery, consumerWorkers);
    }

    private Optional<Fault> parseFault(Map<String, Object> m, Workload workload, Problems p) {
        if (m == null) {
            return Optional.empty();
        }
        String path = "fault.";
        checkKeys(m, FAULT_KEYS, path, p);
        FaultTarget target = requireEnum(m, "target", path, FaultTarget::fromId, FaultTarget.knownIds(), p);
        FaultAction action = requireEnum(m, "action", path, FaultAction::fromId, FaultAction.knownIds(), p);
        if (action == null) {
            if (m.get("checkpoint") != null) {
                requireEnum(m, "checkpoint", path, Checkpoint::fromId, Checkpoint.knownIds(), p);
            }
            return Optional.empty();
        }
        Set<String> allowed = FAULT_KEYS_BY_ACTION.get(action);
        for (String key : m.keySet()) {
            if (FAULT_KEYS.contains(key) && !allowed.contains(key)) {
                p.add("fault." + key + " is not meaningful for action '" + action.id() + "'");
            }
        }
        int committed = workload == null ? Integer.MAX_VALUE : workload.committedOrderCount();
        Optional<Checkpoint> checkpoint = Optional.empty();
        int occurrence = 0;
        int copies = 0;
        int atOrder = 0;
        int ordersDuringFault = 0;
        switch (action) {
            case KILL -> {
                Checkpoint cp = requireEnum(m, "checkpoint", path, Checkpoint::fromId, Checkpoint.knownIds(), p);
                checkpoint = Optional.ofNullable(cp);
                occurrence = optionalInt(m, "occurrence", path, 1, MAX_ORDERS, 1, p);
                if (target != null && !Set.of(FaultTarget.PRODUCER, FaultTarget.RELAY, FaultTarget.CONSUMER).contains(target)) {
                    p.add("fault.target: action 'kill' needs a process target (producer, relay or consumer), not '"
                            + target.id() + "'");
                } else if (target != null && cp != null && !cp.role().id().equals(target.id())) {
                    p.add("fault.checkpoint: '" + cp.id() + "' belongs to the " + cp.role().id()
                            + ", but fault.target is '" + target.id() + "'");
                }
            }
            case DUPLICATE_PUBLISH -> {
                occurrence = optionalInt(m, "occurrence", path, 1, MAX_ORDERS, 1, p);
                Integer c = requireInt(m, "copies", path, 2, 10, p);
                copies = c == null ? 0 : c;
                if (target != null && target != FaultTarget.PUBLISHER) {
                    p.add("fault.target: action 'duplicate-publish' applies to 'publisher', not '" + target.id() + "'");
                }
            }
            case NETWORK_CUT -> {
                Integer at = requireInt(m, "atOrder", path, 1, MAX_ORDERS, p);
                Integer during = requireInt(m, "ordersDuringFault", path, 1, MAX_ORDERS, p);
                atOrder = at == null ? 0 : at;
                ordersDuringFault = during == null ? 0 : during;
                if (target != null && target != FaultTarget.BROKER_LINK) {
                    p.add("fault.target: action 'network-cut' applies to 'broker-link', not '" + target.id() + "'");
                }
                if (workload != null && at != null && during != null && at + during - 1 > workload.orders()) {
                    p.add("fault: atOrder + ordersDuringFault - 1 (" + (at + during - 1)
                            + ") exceeds workload.orders (" + workload.orders() + ")");
                }
            }
        }
        if ((action == FaultAction.KILL || action == FaultAction.DUPLICATE_PUBLISH) && occurrence > committed) {
            p.add("fault.occurrence: " + occurrence + " is larger than the number of committed orders in the workload ("
                    + committed + "), so the fault could never trigger");
        }
        if (target == null) {
            return Optional.empty();
        }
        return Optional.of(new Fault(target, action, checkpoint, occurrence, copies, atOrder, ordersDuringFault));
    }

    private Recovery parseRecovery(Map<String, Object> m, Optional<Fault> fault, boolean faultDeclared, Problems p) {
        RecoveryAction action = RecoveryAction.NONE;
        if (m != null) {
            checkKeys(m, RECOVERY_KEYS, "recovery.", p);
            RecoveryAction parsed = requireEnum(m, "action", "recovery.", RecoveryAction::fromId,
                    RecoveryAction.knownIds(), p);
            if (parsed != null) {
                action = parsed;
            }
        }
        if (fault.isEmpty()) {
            if (action != RecoveryAction.NONE && !faultDeclared) {
                p.add("recovery.action: '" + action.id() + "' is meaningless without a fault");
            }
            return Recovery.none();
        }
        FaultAction faultAction = fault.get().action();
        switch (faultAction) {
            case KILL -> {
                if (m == null) {
                    p.add("recovery: required for action 'kill' (use 'restart' or 'none')");
                } else if (action == RecoveryAction.RESTORE_NETWORK) {
                    p.add("recovery.action: 'restore-network' does not undo a 'kill'; use 'restart' or 'none'");
                }
            }
            case NETWORK_CUT -> {
                if (action != RecoveryAction.RESTORE_NETWORK) {
                    p.add("recovery.action: action 'network-cut' needs 'restore-network'");
                }
            }
            case DUPLICATE_PUBLISH -> {
                if (action != RecoveryAction.NONE) {
                    p.add("recovery.action: action 'duplicate-publish' has nothing to recover; use 'none' or omit recovery");
                }
            }
        }
        return new Recovery(action);
    }

    private Observation parseObservation(Map<String, Object> m, Problems p) {
        if (m == null) {
            return null;
        }
        checkKeys(m, OBSERVATION_KEYS, "observation.", p);
        Integer timeout = requireInt(m, "timeoutSeconds", "observation.", 5, 600, p);
        int quiet = optionalInt(m, "quietPeriodMillis", "observation.", 200, 30_000,
                Observation.DEFAULT_QUIET_PERIOD_MILLIS, p);
        if (timeout != null && quiet >= timeout * 1000L) {
            p.add("observation.quietPeriodMillis must be shorter than observation.timeoutSeconds");
        }
        return timeout == null ? null : new Observation(timeout, quiet);
    }

    private List<InvariantId> parseAssertions(Object value, Problems p) {
        if (value == null) {
            p.add("assertions: required (known: " + InvariantId.knownIds() + ")");
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            p.add("assertions: must be a non-empty list (known: " + InvariantId.knownIds() + ")");
            return List.of();
        }
        Set<InvariantId> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                p.add("assertions: every entry must be an invariant name, got " + describe(item));
                continue;
            }
            Optional<InvariantId> id = InvariantId.fromId(s);
            if (id.isEmpty()) {
                p.add("assertions: unknown invariant '" + s + "' (known: " + InvariantId.knownIds() + ")");
            } else if (!result.add(id.get())) {
                p.add("assertions: '" + s + "' is listed twice");
            }
        }
        return List.copyOf(result);
    }

    private Map<Strategy, Outcome> parseExpected(Map<String, Object> m, Optional<Fault> fault, Problems p) {
        Map<Strategy, Outcome> result = new EnumMap<>(Strategy.class);
        if (m == null) {
            return result;
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Optional<Strategy> strategy = Strategy.fromId(e.getKey());
            if (strategy.isEmpty()) {
                p.add("expectedByStrategy: unknown strategy '" + e.getKey() + "' (known: " + Strategy.knownIds() + ")");
                continue;
            }
            if (!(e.getValue() instanceof String s)) {
                p.add("expectedByStrategy." + e.getKey() + ": must be an outcome name, got " + describe(e.getValue()));
                continue;
            }
            Optional<Outcome> outcome = Outcome.fromId(s);
            if (outcome.isEmpty()) {
                p.add("expectedByStrategy." + e.getKey() + ": unknown outcome '" + s
                        + "' (use CONSISTENT, VIOLATION_OBSERVED or NOT_APPLICABLE)");
                continue;
            }
            if (outcome.get() == Outcome.INCONCLUSIVE) {
                p.add("expectedByStrategy." + e.getKey() + ": INCONCLUSIVE describes a measurement problem and cannot be expected");
                continue;
            }
            boolean applicable = fault.map(f -> f.target().existsIn(strategy.get())).orElse(true);
            if (!applicable && outcome.get() != Outcome.NOT_APPLICABLE) {
                p.add("expectedByStrategy." + e.getKey() + ": fault target '" + fault.get().target().id()
                        + "' does not exist in this strategy, so the expectation must be NOT_APPLICABLE");
            } else if (applicable && outcome.get() == Outcome.NOT_APPLICABLE) {
                p.add("expectedByStrategy." + e.getKey() + ": the scenario applies to this strategy; "
                        + "NOT_APPLICABLE is only valid when the fault target does not exist");
            }
            result.put(strategy.get(), outcome.get());
        }
        for (Strategy s : Strategy.values()) {
            if (!m.containsKey(s.id())) {
                p.add("expectedByStrategy: missing an expectation for '" + s.id() + "'");
            }
        }
        return result;
    }

    // ---- small typed accessors ------------------------------------------------------------------

    private static Map<String, Object> section(Map<String, Object> doc, String key, String path, boolean required,
                                               Problems p) {
        Object value = doc.get(key);
        if (value == null) {
            if (required) {
                p.add(path + key + ": required");
            }
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            p.add(path + key + ": must be a mapping, got " + describe(value));
            return null;
        }
        return asStringKeyed(map, path + key + ".", p);
    }

    private static Map<String, Object> asStringKeyed(Map<?, ?> map, String path, Problems p) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String k) {
                result.put(k, e.getValue());
            } else {
                p.add(path + String.valueOf(e.getKey()) + ": field names must be text");
            }
        }
        return result;
    }

    private static void checkKeys(Map<String, Object> m, Set<String> allowed, String path, Problems p) {
        for (String key : m.keySet()) {
            if (!allowed.contains(key)) {
                p.add(path + key + ": unknown field (allowed: " + String.join(", ", allowed.stream().sorted().toList()) + ")");
            }
        }
    }

    private static String requireString(Map<String, Object> m, String key, String path, Problems p) {
        Object v = m.get(key);
        if (v == null) {
            p.add(path + key + ": required");
            return null;
        }
        if (!(v instanceof String s) || s.isBlank()) {
            p.add(path + key + ": must be non-empty text, got " + describe(v));
            return null;
        }
        if (s.length() > 500) {
            p.add(path + key + ": longer than 500 characters");
            return null;
        }
        return s.strip();
    }

    private static Long requireLong(Map<String, Object> m, String key, String path, Problems p) {
        Object v = m.get(key);
        if (v == null) {
            p.add(path + key + ": required");
            return null;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof BigInteger) {
            p.add(path + key + ": out of range for a 64-bit integer");
            return null;
        }
        p.add(path + key + ": must be an integer, got " + describe(v));
        return null;
    }

    private static Integer requireInt(Map<String, Object> m, String key, String path, int min, int max, Problems p) {
        if (m.get(key) == null) {
            p.add(path + key + ": required");
            return null;
        }
        return readInt(m, key, path, min, max, p);
    }

    private static int optionalInt(Map<String, Object> m, String key, String path, int min, int max, int fallback,
                                   Problems p) {
        if (m.get(key) == null) {
            return fallback;
        }
        Integer value = readInt(m, key, path, min, max, p);
        return value == null ? fallback : value;
    }

    private static Integer readInt(Map<String, Object> m, String key, String path, int min, int max, Problems p) {
        Object v = m.get(key);
        long value;
        if (v instanceof Integer i) {
            value = i;
        } else if (v instanceof Long l) {
            value = l;
        } else {
            p.add(path + key + ": must be an integer, got " + describe(v));
            return null;
        }
        if (value < min || value > max) {
            p.add(path + key + ": " + value + " is outside the allowed range " + min + ".." + max);
            return null;
        }
        return (int) value;
    }

    private static <E> E requireEnum(Map<String, Object> m, String key, String path,
                                     java.util.function.Function<String, Optional<E>> lookup, String known,
                                     Problems p) {
        Object v = m.get(key);
        if (v == null) {
            p.add(path + key + ": required (known: " + known + ")");
            return null;
        }
        if (!(v instanceof String s)) {
            p.add(path + key + ": must be text, got " + describe(v));
            return null;
        }
        Optional<E> value = lookup.apply(s);
        if (value.isEmpty()) {
            p.add(path + key + ": unknown value '" + s + "' (known: " + known + ")");
            return null;
        }
        return value.get();
    }

    private static String describe(Object v) {
        if (v == null) {
            return "nothing";
        }
        if (v instanceof String s) {
            return "text '" + s + "'";
        }
        if (v instanceof Map<?, ?>) {
            return "a mapping";
        }
        if (v instanceof List<?>) {
            return "a list";
        }
        return v.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT) + " " + v;
    }

    private static final class Problems {
        private final List<String> list = new ArrayList<>();

        void add(String problem) {
            list.add(problem);
        }

        boolean isEmpty() {
            return list.isEmpty();
        }
    }
}
