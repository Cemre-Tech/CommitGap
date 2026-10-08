package com.example.commitgap.demo.consumer;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records each delivery the consumer receives, on autocommit statements outside the business
 * transaction. Distinguishes deliveries and attempts from business effects; it is not evidence of an
 * effect. A missing outcome means the process died between receiving and finishing.
 */
public class DeliveryLog {

    private static final Logger log = LoggerFactory.getLogger(DeliveryLog.class);

    private final JdbcTemplate jdbc;

    public DeliveryLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long received(UUID eventId, boolean redelivered, Integer deliveryCount, String worker) {
        try {
            return jdbc.queryForObject("INSERT INTO delivery_log (event_id, redelivered, delivery_count, worker) "
                    + "VALUES (?, ?, ?, ?) RETURNING id", Long.class, eventId, redelivered, deliveryCount, worker);
        } catch (RuntimeException e) {
            log.warn("could not write delivery_log: {}", e.getMessage());
            return null;
        }
    }

    public void finish(Long id, String outcome, String detail) {
        if (id == null) {
            return;
        }
        try {
            jdbc.update("UPDATE delivery_log SET outcome = ?, detail = ?, finished_at = clock_timestamp() WHERE id = ?",
                    outcome, detail, id);
        } catch (RuntimeException e) {
            log.warn("could not update delivery_log {}: {}", id, e.getMessage());
        }
    }
}
