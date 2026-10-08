package com.example.commitgap.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pinned container images. Never "latest": a result must be explainable by the versions that
 * produced it, so every report records these references and the local image ids.
 *
 * <p>Licenses: PostgreSQL (PostgreSQL License), RabbitMQ (MPL 2.0), Toxiproxy (MIT), Eclipse Temurin
 * (GPLv2 with Classpath Exception). The images are pulled from their public registries at run time
 * and are not redistributed by CommitGap.
 */
public final class LabImages {

    public static final String POSTGRES = "postgres:18.6-alpine";
    public static final String RABBITMQ = "rabbitmq:4.3.6-management-alpine";
    public static final String TOXIPROXY = "ghcr.io/shopify/toxiproxy:2.12.0";
    public static final String JAVA_RUNTIME = "eclipse-temurin:21.0.12_8-jre-alpine";

    private LabImages() {
    }

    public static Map<String, String> all() {
        Map<String, String> images = new LinkedHashMap<>();
        images.put("postgres", POSTGRES);
        images.put("rabbitmq", RABBITMQ);
        images.put("toxiproxy", TOXIPROXY);
        images.put("java-runtime", JAVA_RUNTIME);
        return images;
    }
}
