package com.example.commitgap.demo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.commitgap.demo.DemoProperties;
import com.example.commitgap.demo.control.CheckpointGate;
import com.example.commitgap.demo.control.FaultSwitches;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** A relay rollback must never erase the independently recorded publishing evidence. */
@Testcontainers
class EventPublisherLogIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    @Test
    void publishLogCommitsIndependentlyOfTheRolledBackRelayTransaction() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/producer").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        DemoProperties properties = mock(DemoProperties.class);
        EventPublisher publisher = new EventPublisher(mock(RabbitTemplate.class), jdbc, JsonMapper.builder().build(),
                new FaultSwitches(), properties, mock(CheckpointGate.class), manager);
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (order_id, event_id, sku, quantity) VALUES (?, ?, 'SKU-001', 1)",
                orderId, eventId);
        jdbc.update("INSERT INTO outbox (event_id, order_id, payload) VALUES (?, ?, '{}')", eventId, orderId);

        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT event_id FROM outbox WHERE event_id = ? FOR UPDATE", eventId);
            publisher.logAttempt(eventId, "relay", "CONFIRMED", "test recorded result");
            jdbc.update("UPDATE outbox SET status = 'SENT' WHERE event_id = ?", eventId);
            throw new IllegalStateException("relay transaction rolls back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM outbox WHERE event_id = ?", String.class, eventId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publish_log WHERE event_id = ? AND result = 'CONFIRMED'",
                Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void eightConcurrentOwnersCanLogIndependentlyWithoutExhaustingTheConfiguredPool() throws Exception {
        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl(POSTGRES.getJdbcUrl());
            ds.setUsername(POSTGRES.getUsername());
            ds.setPassword(POSTGRES.getPassword());
            ds.setMaximumPoolSize(18); // matches the demo's pool, including eight outer + eight log connections
            ds.setConnectionTimeout(5000);
            Flyway.configure().dataSource(ds).locations("classpath:db/producer").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
            EventPublisher publisher = new EventPublisher(mock(RabbitTemplate.class), jdbc, JsonMapper.builder().build(),
                    new FaultSwitches(), mock(DemoProperties.class), mock(CheckpointGate.class), manager);
            List<UUID> events = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UUID eventId = UUID.randomUUID();
                UUID orderId = UUID.randomUUID();
                events.add(eventId);
                jdbc.update("INSERT INTO orders (order_id, event_id, sku, quantity) VALUES (?, ?, 'SKU-001', 1)",
                        orderId, eventId);
                jdbc.update("INSERT INTO outbox (event_id, order_id, payload) VALUES (?, ?, '{}')", eventId, orderId);
            }
            CyclicBarrier owners = new CyclicBarrier(8);
            try (var pool = Executors.newFixedThreadPool(8)) {
                List<Future<?>> completions = new ArrayList<>();
                for (UUID eventId : events) {
                    completions.add(pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                        jdbc.queryForList("SELECT event_id FROM outbox WHERE event_id = ? FOR UPDATE", eventId);
                        try {
                            owners.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException("could not hold eight simultaneous relay transactions", e);
                        }
                        publisher.logAttempt(eventId, "relay", "CONFIRMED", "eight-owner pool regression");
                        status.setRollbackOnly();
                    })));
                }
                for (Future<?> completion : completions) {
                    completion.get(20, TimeUnit.SECONDS);
                }
            }
            for (UUID eventId : events) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM publish_log WHERE event_id = ?", Integer.class,
                        eventId)).isEqualTo(1);
            }
        }
    }
}
