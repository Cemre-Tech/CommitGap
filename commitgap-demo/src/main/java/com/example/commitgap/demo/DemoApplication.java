package com.example.commitgap.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * One jar, three roles. Every container started by the CommitGap runtime runs this application with
 * {@code COMMITGAP_ROLE} set to producer, relay or consumer and {@code COMMITGAP_STRATEGY} set to the
 * strategy under test, so killing one role never affects the others.
 */
@SpringBootApplication
@EnableConfigurationProperties(DemoProperties.class)
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
