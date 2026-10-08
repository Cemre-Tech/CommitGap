package com.example.commitgap.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.RunResult;
import org.junit.jupiter.api.Test;

class SmokeIT {

    @Test
    void happyPathOutboxIdempotent() {
        RunResult r = LabSupport.run("happy-path", Strategy.OUTBOX_IDEMPOTENT);
        System.out.println(LabSupport.describe(r));
        assertThat(r.outcome()).isEqualTo(Outcome.CONSISTENT);
    }
}
