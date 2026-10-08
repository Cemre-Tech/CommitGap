package com.example.commitgap.demo.relay;

import com.example.commitgap.demo.DemoProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Explicit barrier for the relay. In producer crash scenarios the runner starts the relay with the
 * gate closed, so the relay cannot publish an outbox row before the producer has reached its checkpoint
 * and been killed. The runner opens the gate afterwards.
 */
@Component
@ConditionalOnProperty(prefix = "commitgap", name = "role", havingValue = "relay")
public class RelayGate {

    private static final Logger log = LoggerFactory.getLogger(RelayGate.class);

    private final AtomicBoolean open;

    public RelayGate(DemoProperties properties) {
        this.open = new AtomicBoolean(properties.relayGateOpen());
        log.info("relay gate starts {}", open.get() ? "open" : "closed");
    }

    public boolean isOpen() {
        return open.get();
    }

    public void set(boolean value) {
        open.set(value);
        log.info("relay gate {}", value ? "opened" : "closed");
    }
}
