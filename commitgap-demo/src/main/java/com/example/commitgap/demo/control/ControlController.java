package com.example.commitgap.demo.control;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.demo.DemoProperties;
import com.example.commitgap.demo.relay.RelayGate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The runner's control channel into a lab process: health, checkpoint arming and observation,
 * fault switches and the relay barrier. Only used by the CommitGap runtime against containers it created.
 */
@RestController
@RequestMapping("/control")
public class ControlController {

    private final DemoProperties properties;
    private final CheckpointGate gate;
    private final FaultSwitches faults;
    private final ObjectProvider<RelayGate> relayGate;

    public ControlController(DemoProperties properties, CheckpointGate gate, FaultSwitches faults,
                             ObjectProvider<RelayGate> relayGate) {
        this.properties = properties;
        this.gate = gate;
        this.faults = faults;
        this.relayGate = relayGate;
    }

    public record ArmRequest(String checkpoint, int occurrence) {
    }

    public record ReleaseRequest(String checkpoint) {
    }

    public record DuplicateRequest(int occurrence, int copies) {
    }

    public record GateRequest(boolean open) {
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("role", properties.role(), "strategy", properties.strategy(), "ready", true,
                "pid", ProcessHandle.current().pid());
    }

    @PostMapping("/checkpoints/arm")
    public ResponseEntity<Object> arm(@RequestBody ArmRequest request) {
        Optional<Checkpoint> checkpoint = Checkpoint.fromId(request.checkpoint());
        if (checkpoint.isEmpty() || checkpoint.get().role() != properties.processRole() || request.occurrence() < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "checkpoint " + request.checkpoint()
                    + " cannot be armed in role " + properties.role()));
        }
        gate.arm(checkpoint.get(), request.occurrence());
        return ResponseEntity.ok(state());
    }

    @PostMapping("/checkpoints/release")
    public ResponseEntity<Object> release(@RequestBody ReleaseRequest request) {
        Optional<Checkpoint> checkpoint = Checkpoint.fromId(request.checkpoint());
        if (checkpoint.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown checkpoint"));
        }
        gate.release(checkpoint.get());
        return ResponseEntity.ok(state());
    }

    @GetMapping("/checkpoints")
    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        CheckpointGate.Arming armed = gate.armed();
        state.put("armed", armed == null ? null
                : Map.of("checkpoint", armed.checkpoint().id(), "occurrence", armed.occurrence()));
        state.put("counts", gate.counts());
        state.put("arrivals", gate.arrivals().stream().map(a -> Map.of(
                "checkpoint", a.checkpoint().id(),
                "occurrence", a.occurrence(),
                "at", a.at().toString(),
                "state", a.state(),
                "context", a.context())).toList());
        return state;
    }

    @PostMapping("/faults/duplicate-publish")
    public Map<String, Object> duplicate(@RequestBody DuplicateRequest request) {
        faults.armDuplicatePublish(request.occurrence(), request.copies());
        return Map.of("occurrence", faults.duplicateOccurrence(), "copies", faults.duplicateCopies());
    }

    @PostMapping("/relay/gate")
    public ResponseEntity<Object> relayGate(@RequestBody GateRequest request) {
        RelayGate relay = relayGate.getIfAvailable();
        if (relay == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "not a relay process"));
        }
        relay.set(request.open());
        return ResponseEntity.ok(Map.of("open", relay.isOpen()));
    }
}
