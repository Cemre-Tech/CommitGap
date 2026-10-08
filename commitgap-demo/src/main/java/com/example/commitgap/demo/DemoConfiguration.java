package com.example.commitgap.demo;

import com.example.commitgap.core.model.ProcessRole;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DemoConfiguration {

    public static final String EXCHANGE = "commitgap.orders";
    public static final String ROUTING_KEY = "order.created";
    public static final String QUEUE = "commitgap.order-created";
    public static final String DEAD_LETTER_EXCHANGE = "commitgap.orders.dlx";
    public static final String DEAD_LETTER_QUEUE = "commitgap.order-created.dlq";
    /** Broker-side bound on redeliveries; after this many, the message is dead-lettered, not requeued forever. */
    public static final int DELIVERY_LIMIT = 5;

    /**
     * The same durable topology for every strategy and every role. A quorum queue keeps messages
     * durable and bounds redeliveries with {@code x-delivery-limit}.
     */
    @Bean
    Declarables topology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .quorum()
                .deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
        Queue dlq = QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(ROUTING_KEY);
        return new Declarables(exchange, dlx, queue, dlq, binding, dlqBinding);
    }

    /** Producer and relay share the producer database; the consumer has its own. */
    @Bean
    FlywayConfigurationCustomizer schemaPerRole(DemoProperties properties) {
        String location = properties.processRole() == ProcessRole.CONSUMER ? "classpath:db/consumer" : "classpath:db/producer";
        return configuration -> configuration.locations(location);
    }
}
