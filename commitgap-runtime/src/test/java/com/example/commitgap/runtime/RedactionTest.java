package com.example.commitgap.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void removesGeneratedSecretsWhereverTheyAppear() {
        String secret = "129993ba7f72b2bbf0f462919581c32f4749";

        String text = Redaction.redact("cmd=[java], env=[SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/consumer, X="
                + secret + "]", List.of(secret));

        assertThat(text).doesNotContain(secret).contains("[redacted]");
    }

    @Test
    void masksPasswordAssignmentsEvenForUnknownValues() {
        String text = Redaction.redact("env=[SPRING_RABBITMQ_PASSWORD=hunter2, SPRING_DATASOURCE_USERNAME=commitgap, "
                + "POSTGRES_PASSWORD: s3cret, api_token=abc]", List.of());

        assertThat(text).doesNotContain("hunter2").doesNotContain("s3cret").doesNotContain("abc]")
                .contains("SPRING_DATASOURCE_USERNAME=commitgap");
    }

    @Test
    void masksCredentialsInUrls() {
        assertThat(Redaction.redact("amqp://commitgap:topsecret@rabbitmq:5672/", List.of()))
                .isEqualTo("amqp://commitgap:[redacted]@rabbitmq:5672/");
    }

    @Test
    void truncatesHugeMessages() {
        assertThat(Redaction.redact("x".repeat(5000), List.of())).hasSizeLessThan(700).endsWith("run directory)");
    }
}
