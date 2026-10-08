package com.example.commitgap.runtime;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Scrubs credentials from text that may reach reports, manifests or timelines. Docker and
 * Testcontainers error messages can contain a container's full environment, which includes the
 * per-run database and broker passwords.
 */
public final class Redaction {

    static final String MASK = "[redacted]";
    private static final int MAX_LENGTH = 600;
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([A-Z0-9_.-]*(PASSWORD|PASSWD|SECRET|TOKEN|CREDENTIAL)[A-Z0-9_.-]*\\s*[=:]\\s*)[^,\\s\\]}\"')]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://[^/\\s:@]+:)[^@\\s/]+@");

    private Redaction() {
    }

    public static String redact(String text, Collection<String> secrets) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 6) {
                result = result.replace(secret, MASK);
            }
        }
        result = ASSIGNMENT.matcher(result).replaceAll("$1" + MASK);
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1" + MASK + "@");
        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH) + "... (truncated; see the container logs in the run directory)";
        }
        return result;
    }
}
