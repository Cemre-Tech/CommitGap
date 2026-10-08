package com.example.commitgap.demo.events;

import com.example.commitgap.demo.DemoConfiguration;
import com.example.commitgap.demo.DemoProperties;
import com.example.commitgap.demo.control.FaultSwitches;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes OrderCreated events with the same broker settings for every strategy: persistent
 * messages, mandatory routing, publisher confirms. Every attempt is written to {@code publish_log}
 * through {@link JdbcTemplate} outside any transaction (autocommit), so the log never joins or
 * depends on a business transaction.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final FaultSwitches faults;
    private final long confirmTimeoutMillis;

    public EventPublisher(RabbitTemplate rabbit, JdbcTemplate jdbc, JsonMapper json, FaultSwitches faults,
                          DemoProperties properties) {
        this.rabbit = rabbit;
        this.jdbc = jdbc;
        this.json = json;
        this.faults = faults;
        this.confirmTimeoutMillis = properties.confirmTimeoutMillis();
    }

    public String toPayload(OrderCreatedEvent event) {
        return json.writeValueAsString(event);
    }

    /**
     * Publishes the event (several times if a duplicate-publish fault is armed for it) and returns the
     * result of the last copy. Every copy carries the same event id.
     */
    public PublishResult publish(UUID eventId, UUID orderId, String payload, String publisher) {
        int copies = faults.copiesFor(eventId);
        PublishResult result = null;
        for (int copy = 1; copy <= copies; copy++) {
            result = publishOnce(eventId, orderId, payload);
            String detail = copies > 1 ? "copy " + copy + "/" + copies + (result.detail() == null ? "" : "; " + result.detail())
                    : result.detail();
            logAttempt(eventId, publisher, result.kind().name(), detail);
            if (!result.confirmed()) {
                break;
            }
        }
        return result;
    }

    private PublishResult publishOnce(UUID eventId, UUID orderId, String payload) {
        Message message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(eventId.toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("x-commitgap-order-id", orderId.toString())
                .build();
        CorrelationData correlation = new CorrelationData(eventId + "/" + UUID.randomUUID());
        try {
            rabbit.send(DemoConfiguration.EXCHANGE, DemoConfiguration.ROUTING_KEY, message, correlation);
        } catch (AmqpException e) {
            return new PublishResult(PublishResult.Kind.FAILED, rootMessage(e));
        }
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                return new PublishResult(PublishResult.Kind.NACKED, confirm.reason());
            }
            if (correlation.getReturned() != null) {
                return new PublishResult(PublishResult.Kind.RETURNED, correlation.getReturned().getReplyText());
            }
            return new PublishResult(PublishResult.Kind.CONFIRMED, null);
        } catch (TimeoutException e) {
            return new PublishResult(PublishResult.Kind.TIMEOUT, "no confirm within " + confirmTimeoutMillis + " ms");
        } catch (ExecutionException e) {
            return new PublishResult(PublishResult.Kind.FAILED, rootMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PublishResult(PublishResult.Kind.FAILED, "interrupted");
        }
    }

    private void logAttempt(UUID eventId, String publisher, String result, String detail) {
        try {
            jdbc.update("INSERT INTO publish_log (event_id, publisher, result, detail) VALUES (?, ?, ?, ?)",
                    eventId, publisher, result, detail);
        } catch (RuntimeException e) {
            log.warn("could not write publish_log for {}: {}", eventId, e.getMessage());
        }
        log.info("publish {} by {}: {}{}", eventId, publisher, result, detail == null ? "" : " (" + detail + ")");
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
