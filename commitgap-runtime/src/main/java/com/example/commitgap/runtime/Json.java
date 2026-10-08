package com.example.commitgap.runtime;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Shared JSON mapper for the runtime (control API, broker API, manifests, timeline). */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /** Single-line output for JSON Lines files. */
    public static final JsonMapper COMPACT = JsonMapper.builder().build();

    private Json() {
    }
}
