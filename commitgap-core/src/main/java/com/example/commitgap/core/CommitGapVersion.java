package com.example.commitgap.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build information filtered into the jar by Maven. */
public final class CommitGapVersion {

    private static final Properties PROPS = load();

    private CommitGapVersion() {
    }

    public static String version() {
        return PROPS.getProperty("version", "unknown");
    }

    public static String component(String name) {
        return PROPS.getProperty(name, "unknown");
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = CommitGapVersion.class.getResourceAsStream("commitgap-version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // version stays "unknown"
        }
        return p;
    }
}
