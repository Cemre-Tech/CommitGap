package com.example.commitgap.demo;

import com.example.commitgap.core.model.ProcessRole;
import com.example.commitgap.core.model.Strategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param relayGateOpen        whether the relay may publish at startup; the runner opens a closed gate
 *                             explicitly (barrier for producer crash scenarios)
 * @param relayMaxAttempts     publish attempts per outbox row before the row is parked
 * @param barrierTimeoutSeconds how long a process waits at an armed checkpoint before continuing on its own
 */
@ConfigurationProperties("commitgap")
public record DemoProperties(
        String role,
        String strategy,
        String consumerName,
        int consumerWorkers,
        int consumerPrefetch,
        int initialStock,
        boolean relayGateOpen,
        long relayPollMillis,
        int relayMaxAttempts,
        long confirmTimeoutMillis,
        long barrierTimeoutSeconds) {

    public ProcessRole processRole() {
        return ProcessRole.fromId(role)
                .orElseThrow(() -> new IllegalStateException("Unknown commitgap.role '" + role + "'"));
    }

    public Strategy strategyValue() {
        return Strategy.fromId(strategy)
                .orElseThrow(() -> new IllegalStateException("Unknown commitgap.strategy '" + strategy + "'"));
    }
}
