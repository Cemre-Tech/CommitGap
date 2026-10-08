package com.example.commitgap.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.model.FaultAction;
import com.example.commitgap.core.model.FaultTarget;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.RecoveryAction;
import com.example.commitgap.core.model.Strategy;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScenarioLoaderTest {

    private final ScenarioLoader loader = new ScenarioLoader();

    private static final String VALID = """
            schemaVersion: 1
            id: crash-after-commit
            description: Kill the producer after its database transaction commits.
            workload:
              seed: 42
              orders: 20
              quantityPerOrder: 1
              initialStock: 100
            fault:
              target: producer
              checkpoint: producer.after-db-commit-before-publish
              action: kill
            recovery:
              action: restart
            observation:
              timeoutSeconds: 30
            assertions:
              - committed-order-has-business-effect
              - stock-changed-once-per-event
            expectedByStrategy:
              naive-dual-write: VIOLATION_OBSERVED
              transactional-outbox: CONSISTENT
              outbox-idempotent: CONSISTENT
            """;

    @Test
    void parsesTheDocumentedExampleExactly() {
        Scenario s = loader.parse(VALID, "example.yaml");

        assertThat(s.id()).isEqualTo("crash-after-commit");
        assertThat(s.workload()).isEqualTo(new Workload(42, 20, 1, 100, 0, 1));
        Fault fault = s.fault().orElseThrow();
        assertThat(fault.target()).isEqualTo(FaultTarget.PRODUCER);
        assertThat(fault.action()).isEqualTo(FaultAction.KILL);
        assertThat(fault.checkpoint()).contains(Checkpoint.PRODUCER_AFTER_DB_COMMIT_BEFORE_PUBLISH);
        assertThat(fault.occurrence()).isEqualTo(1);
        assertThat(s.recovery().action()).isEqualTo(RecoveryAction.RESTART);
        assertThat(s.observation().timeoutSeconds()).isEqualTo(30);
        assertThat(s.assertions()).containsExactly(InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT,
                InvariantId.STOCK_CHANGED_ONCE_PER_EVENT);
        assertThat(s.expectedFor(Strategy.NAIVE_DUAL_WRITE)).isEqualTo(Outcome.VIOLATION_OBSERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"happy-path", "crash-after-commit", "relay-confirm-gap", "duplicate-delivery",
            "consumer-ack-gap", "broker-outage"})
    void bundledScenariosAreValid(String id) {
        ScenarioCatalog catalog = new ScenarioCatalog(Path.of(System.getProperty("commitgap.scenarios.dir")), loader);

        Scenario s = catalog.resolve(id);

        assertThat(s.id()).isEqualTo(id);
        assertThat(s.expectedByStrategy()).containsOnlyKeys(Strategy.values());
    }

    @Test
    void relayScenarioIsNotApplicableToNaive() {
        ScenarioCatalog catalog = new ScenarioCatalog(Path.of(System.getProperty("commitgap.scenarios.dir")), loader);

        Scenario s = catalog.resolve("relay-confirm-gap");

        assertThat(s.isApplicableTo(Strategy.NAIVE_DUAL_WRITE)).isFalse();
        assertThat(s.isApplicableTo(Strategy.TRANSACTIONAL_OUTBOX)).isTrue();
    }

    @Test
    void rejectsDuplicateKeys() {
        String yaml = VALID.replace("  orders: 20\n", "  orders: 20\n  orders: 30\n");

        assertProblems(yaml, "YAML error", "duplicate key orders");
    }

    @Test
    void rejectsMissingFields() {
        String yaml = VALID.replace("  initialStock: 100\n", "").replace("observation:\n  timeoutSeconds: 30\n", "");

        assertProblems(yaml, "workload.initialStock: required", "observation: required");
    }

    @Test
    void rejectsUnknownCheckpointAndAction() {
        String yaml = VALID.replace("producer.after-db-commit-before-publish", "producer.somewhere")
                .replace("action: kill", "action: explode");

        assertProblems(yaml, "fault.checkpoint", "fault.action: unknown value 'explode'");
    }

    @Test
    void rejectsUnknownCheckpointForKill() {
        String yaml = VALID.replace("producer.after-db-commit-before-publish", "producer.somewhere");

        assertProblems(yaml, "fault.checkpoint: unknown value 'producer.somewhere'");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        String yaml = VALID.replace("schemaVersion: 1", "schemaVersion: 2");

        assertProblems(yaml, "unsupported schemaVersion 2");
    }

    @Test
    void rejectsUnknownFields() {
        String yaml = VALID.replace("observation:\n", "observation:\n  retries: 3\n");

        assertProblems(yaml, "observation.retries: unknown field");
    }

    @Test
    void rejectsOptionsThatMeanNothingForTheAction() {
        String yaml = VALID.replace("  action: kill\n", "  action: kill\n  copies: 3\n  atOrder: 2\n");

        assertProblems(yaml, "fault.copies is not meaningful for action 'kill'",
                "fault.atOrder is not meaningful for action 'kill'");
    }

    @Test
    void rejectsACheckpointOfAnotherProcess() {
        String yaml = VALID.replace("target: producer", "target: consumer");

        assertProblems(yaml, "belongs to the producer, but fault.target is 'consumer'");
    }

    @Test
    void rejectsRecoveryThatDoesNotMatchTheFault() {
        String yaml = VALID.replace("action: restart", "action: restore-network");

        assertProblems(yaml, "'restore-network' does not undo a 'kill'");
    }

    @Test
    void rejectsAnOccurrenceTheWorkloadCanNeverReach() {
        String yaml = VALID.replace("  action: kill\n", "  action: kill\n  occurrence: 25\n");

        assertProblems(yaml, "fault.occurrence");
    }

    @Test
    void rejectsInconclusiveAsAnExpectation() {
        String yaml = VALID.replace("transactional-outbox: CONSISTENT", "transactional-outbox: INCONCLUSIVE");

        assertProblems(yaml, "INCONCLUSIVE describes a measurement problem");
    }

    @Test
    void rejectsNotApplicableWhenTheTargetExists() {
        String yaml = VALID.replace("naive-dual-write: VIOLATION_OBSERVED", "naive-dual-write: NOT_APPLICABLE");

        assertProblems(yaml, "NOT_APPLICABLE is only valid when the fault target does not exist");
    }

    @Test
    void requiresNotApplicableWhenTheTargetIsMissing() {
        String yaml = VALID.replace("target: producer", "target: relay")
                .replace("producer.after-db-commit-before-publish", "relay.after-publisher-confirm-before-outbox-mark");

        assertProblems(yaml, "expectedByStrategy.naive-dual-write: fault target 'relay' does not exist");
    }

    @Test
    void requiresAnExpectationForEveryStrategy() {
        String yaml = VALID.replace("  outbox-idempotent: CONSISTENT\n", "");

        assertProblems(yaml, "missing an expectation for 'outbox-idempotent'");
    }

    @Test
    void rejectsWrongTypes() {
        String yaml = VALID.replace("orders: 20", "orders: \"20\"").replace("seed: 42", "seed: forty-two");

        assertProblems(yaml, "workload.orders: must be an integer", "workload.seed: must be an integer");
    }

    @Test
    void refusesToConstructArbitraryTypes() {
        String yaml = VALID.replace("description: Kill the producer after its database transaction commits.",
                "description: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://x\"]]]]");

        assertProblems(yaml, "YAML error");
    }

    @Test
    void collectsAllProblemsAtOnce() {
        String yaml = VALID.replace("orders: 20", "orders: 0").replace("timeoutSeconds: 30", "timeoutSeconds: 1");

        assertThatThrownBy(() -> loader.parse(yaml, "x.yaml"))
                .isInstanceOfSatisfying(ScenarioValidationException.class,
                        e -> assertThat(e.problems()).hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    void networkCutNeedsRestoreNetworkAndAFittingWindow() {
        String yaml = VALID.replace("""
                fault:
                  target: producer
                  checkpoint: producer.after-db-commit-before-publish
                  action: kill
                recovery:
                  action: restart
                """, """
                fault:
                  target: broker-link
                  action: network-cut
                  atOrder: 18
                  ordersDuringFault: 5
                recovery:
                  action: restart
                """);

        assertProblems(yaml, "needs 'restore-network'", "exceeds workload.orders");
    }

    private void assertProblems(String yaml, String... fragments) {
        assertThatThrownBy(() -> loader.parse(yaml, "test.yaml"))
                .isInstanceOfSatisfying(ScenarioValidationException.class, e -> {
                    List<String> problems = e.problems();
                    for (String fragment : fragments) {
                        assertThat(problems).as("problems %s", problems).anyMatch(p -> p.contains(fragment));
                    }
                });
    }
}
