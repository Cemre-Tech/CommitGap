package com.example.commitgap.demo.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.commitgap.demo.DemoProperties;
import com.example.commitgap.demo.control.CheckpointGate;
import com.example.commitgap.demo.events.EventPublisher;
import com.example.commitgap.demo.events.PublishResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL locks; broker transport is replaced only to hold the lock deterministically. */
@Testcontainers
class OutboxRelayIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private JdbcTemplate jdbc;
    private OutboxRelay relay;
    private EventPublisher publisher;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/producer").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        publisher = mock(EventPublisher.class);
        DemoProperties properties = mock(DemoProperties.class);
        when(properties.relayWorkers()).thenReturn(3);
        when(properties.relayMaxAttempts()).thenReturn(50);
        when(properties.relayPollMillis()).thenReturn(100L);
        relay = new OutboxRelay(jdbc, publisher, mock(CheckpointGate.class), mock(RelayGate.class), properties,
                new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Test
    void concurrentWorkersPublishDifferentRowsWithoutBlockingEachOther() throws Exception {
        UUID a = pending();
        UUID b = pending();
        CyclicBarrier bothPublishing = new CyclicBarrier(2);
        Set<UUID> published = ConcurrentHashMap.newKeySet();
        when(publisher.publish(any(), any(), anyString(), anyString())).thenAnswer(call -> {
            assertThat(published.add(call.getArgument(0))).as("a row has only one active owner").isTrue();
            bothPublishing.await(10, TimeUnit.SECONDS);
            return new PublishResult(PublishResult.Kind.CONFIRMED, null);
        });

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(relay::relayNext);
            var second = pool.submit(relay::relayNext);
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(OutboxRelay.Attempt.SENT);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(OutboxRelay.Attempt.SENT);
        }
        assertThat(published).containsExactlyInAnyOrder(a, b);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'SENT'", Integer.class)).isEqualTo(2);
    }

    @Test
    void lockedRowIsSkippedUntilTheOwningTransactionCommits() throws Exception {
        pending();
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(publisher.publish(any(), any(), anyString(), anyString())).thenAnswer(call -> {
            publishing.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new PublishResult(PublishResult.Kind.CONFIRMED, null);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = pool.submit(relay::relayNext);
            try {
                assertThat(publishing.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(pool.submit(relay::relayNext).get(5, TimeUnit.SECONDS)).isEqualTo(OutboxRelay.Attempt.EMPTY);
            } finally {
                release.countDown();
            }
            assertThat(owner.get(10, TimeUnit.SECONDS)).isEqualTo(OutboxRelay.Attempt.SENT);
        }
        assertThat(relay.relayNext()).isEqualTo(OutboxRelay.Attempt.EMPTY);
    }

    @Test
    void rolledBackOwnerReleasesTheRowForRetry() {
        UUID event = pending();
        AtomicBoolean failFirst = new AtomicBoolean(true);
        List<UUID> attempts = new java.util.ArrayList<>();
        when(publisher.publish(any(), any(), anyString(), anyString())).thenAnswer(call -> {
            attempts.add(call.getArgument(0));
            if (failFirst.getAndSet(false)) {
                throw new IllegalStateException("publisher stopped before sending");
            }
            return new PublishResult(PublishResult.Kind.CONFIRMED, null);
        });

        assertThatThrownBy(relay::relayNext).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox WHERE event_id = ?", String.class, event))
                .isEqualTo("PENDING");
        assertThat(relay.relayNext()).isEqualTo(OutboxRelay.Attempt.SENT);
        assertThat(attempts).containsExactly(event, event);
    }

    private UUID pending() {
        UUID event = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (order_id, event_id, sku, quantity) VALUES (?, ?, 'SKU-001', 1)", order, event);
        jdbc.update("INSERT INTO outbox (event_id, order_id, payload) VALUES (?, ?, '{}')", event, order);
        return event;
    }
}
