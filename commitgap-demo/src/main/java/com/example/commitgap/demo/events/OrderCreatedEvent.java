package com.example.commitgap.demo.events;

import java.util.UUID;

/** The OrderCreated event. {@code eventId} is assigned once, when the order is created, and never regenerated. */
public record OrderCreatedEvent(UUID eventId, UUID orderId, String sku, int quantity) {
}
