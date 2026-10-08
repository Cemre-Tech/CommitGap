package com.example.commitgap.runtime;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Run identifiers: a sortable timestamp plus random suffix, safe for Docker names and file names. */
public final class RunIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{2,80}");

    private RunIds() {
    }

    public static String next() {
        byte[] suffix = new byte[3];
        RANDOM.nextBytes(suffix);
        return FORMAT.format(LocalDateTime.now()) + "-" + HexFormat.of().formatHex(suffix);
    }

    public static String child(String parentRunId, String strategyId) {
        return parentRunId + "-" + strategyId;
    }

    public static boolean isValid(String runId) {
        return runId != null && VALID.matcher(runId).matches();
    }

    /** Throwaway credential for one run's database and broker. Never written to reports or manifests. */
    static String secret() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
