package com.example.inventory;

import com.example.commitgap.core.invariant.InvariantStatus;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** A separate application using the adapter SDK, without the built-in Docker demo. */
@SpringBootApplication
public class ExternalSpringExample {
    public static void main(String[] args) {
        // CommandLineRunner completes before run returns; closing shuts down the owned datasource.
        SpringApplication.run(ExternalSpringExample.class, args).close();
    }

    @Bean
    CommandLineRunner demonstrate(JdbcTemplate jdbc, PlatformTransactionManager transactions, Environment environment) {
        return args -> {
            String requested = environment.getProperty("example.case", "all");
            var cases = "all".equals(requested) ? List.of("loss", "duplicate", "safe") : List.of(requested);
            for (String name : cases) {
                var execution = ExampleCases.execute(jdbc, transactions, name);
                var result = execution.verification();
                System.out.println(name + ": " + result.classification().outcome()
                        + " | event=" + execution.eventId()
                        + " | effects=" + result.measurements().businessEffects()
                        + " | duplicateEffects=" + result.measurements().duplicateEffects());
                result.invariants().stream().filter(i -> i.status() == InvariantStatus.FAIL).forEach(i ->
                        System.out.println("  " + i.id().id() + ": " + i.explanation() + " " + i.evidence()));
            }
        };
    }
}
