package com.example.commitgap.demo.consumer;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.demo.control.CheckpointGate;
import com.example.commitgap.demo.events.OrderCreatedEvent;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual-acknowledgement listener. The delivery is acknowledged only after the business transaction
 * committed (or after the idempotent consumer verified an earlier commit for the same event). On a
 * failed transaction the delivery is returned to the queue; the quorum queue's delivery limit bounds
 * how often that can happen before the message is dead-lettered, so there is no endless requeue loop.
 */
public class StockConsumer implements ChannelAwareMessageListener {

    private static final Logger log = LoggerFactory.getLogger(StockConsumer.class);

    private final StockLedger ledger;
    private final DeliveryLog deliveries;
    private final CheckpointGate gate;
    private final JsonMapper json;

    public StockConsumer(StockLedger ledger, DeliveryLog deliveries, CheckpointGate gate, JsonMapper json) {
        this.ledger = ledger;
        this.deliveries = deliveries;
        this.gate = gate;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        MessageProperties props = message.getMessageProperties();
        long tag = props.getDeliveryTag();
        boolean redelivered = Boolean.TRUE.equals(props.getRedelivered());
        Integer deliveryCount = props.getHeader("x-delivery-count") instanceof Number n ? n.intValue() : null;
        String worker = Thread.currentThread().getName();

        OrderCreatedEvent event;
        try {
            event = json.readValue(message.getBody(), OrderCreatedEvent.class);
        } catch (JacksonException e) {
            log.warn("unreadable message {}, dead-lettering it: {}", props.getMessageId(), e.getOriginalMessage());
            deliveries.finish(deliveries.received(null, redelivered, deliveryCount, worker), "FAILED", "unreadable payload");
            channel.basicNack(tag, false, false);
            return;
        }

        Long logId = deliveries.received(event.eventId(), redelivered, deliveryCount, worker);
        StockLedger.Outcome outcome;
        try {
            outcome = ledger.apply(event);
        } catch (RuntimeException e) {
            log.warn("processing {} failed, returning it to the queue: {}", event.eventId(), e.getMessage());
            deliveries.finish(logId, "FAILED", e.getMessage());
            channel.basicNack(tag, false, true);
            return;
        }

        if (outcome == StockLedger.Outcome.APPLIED) {
            // The business transaction has committed; the delivery is not acknowledged yet.
            gate.reach(Checkpoint.CONSUMER_AFTER_BUSINESS_COMMIT_BEFORE_ACK,
                    Map.of("eventId", event.eventId().toString(), "orderId", event.orderId().toString()));
        }
        deliveries.finish(logId, outcome.name(), null);
        channel.basicAck(tag, false);
        log.info("event {} {} (redelivered={}, worker={})", event.eventId(), outcome, redelivered, worker);
    }
}
