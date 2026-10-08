package com.example.commitgap.demo.consumer;

import com.example.commitgap.core.workload.WorkloadPlan;
import com.example.commitgap.demo.DemoConfiguration;
import com.example.commitgap.demo.DemoProperties;
import com.example.commitgap.demo.control.CheckpointGate;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "commitgap", name = "role", havingValue = "consumer")
public class ConsumerConfiguration {

    /** Created after Flyway migrated the consumer schema (Spring Boot orders JdbcTemplate users after it). */
    @Bean
    StockLedger stockLedger(JdbcTemplate jdbc, TransactionTemplate tx, DemoProperties properties) {
        StockLedger ledger = new StockLedger(jdbc, tx, properties.strategyValue().idempotentConsumer(),
                properties.consumerName());
        ledger.seedStock(WorkloadPlan.SKU, properties.initialStock());
        return ledger;
    }

    @Bean
    DeliveryLog deliveryLog(JdbcTemplate jdbc) {
        return new DeliveryLog(jdbc);
    }

    @Bean
    StockConsumer stockConsumer(StockLedger ledger, DeliveryLog deliveries, CheckpointGate gate, JsonMapper json) {
        return new StockConsumer(ledger, deliveries, gate, json);
    }

    @Bean
    SimpleMessageListenerContainer stockListener(ConnectionFactory connectionFactory, StockConsumer consumer,
                                                 DemoProperties properties) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(DemoConfiguration.QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(properties.consumerWorkers());
        container.setPrefetchCount(properties.consumerPrefetch());
        container.setMissingQueuesFatal(false);
        container.setMessageListener(consumer);
        return container;
    }
}
