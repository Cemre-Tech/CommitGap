package com.example.commitgap.core.snapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to publish an event, written by the publishing process on its own connection outside the
 * business transaction. Explains behaviour; it is not evidence of a business effect.
 *
 * @param result CONFIRMED, NACKED, RETURNED, TIMEOUT or FAILED
 */
public record PublishAttempt(UUID eventId, String publisher, String result, String detail, Instant at) {
}
