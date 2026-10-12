package com.example.commitgap.demo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.demo.DemoProperties;
import com.example.commitgap.demo.control.CheckpointGate;
import com.example.commitgap.demo.control.FaultSwitches;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class EventPublisherTest {

    @Test
    void confirmedCheckpointRunsBeforeTheResultIsLoggedOrReturned() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CheckpointGate checkpoints = mock(CheckpointGate.class);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        doAnswer(call -> {
            verifyNoInteractions(jdbc);
            return null;
        }).when(checkpoints).reach(eq(Checkpoint.RELAY_AFTER_BROKER_CONFIRM_BEFORE_PUBLISH_RESULT), any());

        PublishResult result = publisher(rabbit, jdbc, checkpoints).publish(UUID.randomUUID(), UUID.randomUUID(), "{}", "relay");

        assertThat(result.confirmed()).isTrue();
        verify(checkpoints).reach(eq(Checkpoint.RELAY_AFTER_BROKER_CONFIRM_BEFORE_PUBLISH_RESULT), any());
        verify(jdbc).update(anyString(), any(), eq("relay"), eq("CONFIRMED"), any());
    }

    @Test
    void negativeConfirmCannotReachTheAcceptanceCheckpoint() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        CheckpointGate checkpoints = mock(CheckpointGate.class);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        PublishResult result = publisher(rabbit, mock(JdbcTemplate.class), checkpoints)
                .publish(UUID.randomUUID(), UUID.randomUUID(), "{}", "relay");

        assertThat(result.kind()).isEqualTo(PublishResult.Kind.NACKED);
        verify(checkpoints, never()).reach(any(), any());
    }

    private static EventPublisher publisher(RabbitTemplate rabbit, JdbcTemplate jdbc, CheckpointGate checkpoints) {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DemoProperties properties = mock(DemoProperties.class);
        when(properties.confirmTimeoutMillis()).thenReturn(3000L);
        return new EventPublisher(rabbit, jdbc, JsonMapper.builder().build(), new FaultSwitches(), properties,
                checkpoints, manager);
    }
}
