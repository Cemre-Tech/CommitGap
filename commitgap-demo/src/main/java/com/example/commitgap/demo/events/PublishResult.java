package com.example.commitgap.demo.events;

/**
 * Result of publishing one message and waiting for the broker.
 *
 * <ul>
 *   <li>CONFIRMED: positive publisher confirm and the message was not returned as unroutable</li>
 *   <li>RETURNED: the broker confirmed, but returned the mandatory message because no queue was bound</li>
 *   <li>NACKED: negative confirm, or the channel closed before the confirm arrived</li>
 *   <li>TIMEOUT: no confirm within the timeout; the broker may or may not have the message</li>
 *   <li>FAILED: the message could not be sent (for example, no connection)</li>
 * </ul>
 * Only CONFIRMED allows marking an outbox row as sent. A confirm says the broker took responsibility
 * for the message; it says nothing about whether a consumer processed it.
 */
public record PublishResult(Kind kind, String detail) {

    public enum Kind { CONFIRMED, RETURNED, NACKED, TIMEOUT, FAILED }

    public boolean confirmed() {
        return kind == Kind.CONFIRMED;
    }
}
