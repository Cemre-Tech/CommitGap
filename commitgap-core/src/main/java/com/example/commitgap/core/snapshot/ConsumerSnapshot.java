package com.example.commitgap.core.snapshot;

import java.util.List;

/** Rows read from the consumer database at the end of the observation window. */
public record ConsumerSnapshot(int stockQuantity, List<StockMovement> movements, List<ProcessedMessage> processed,
                               List<DeliveryRecord> deliveries) {

    public ConsumerSnapshot {
        movements = List.copyOf(movements);
        processed = List.copyOf(processed);
        deliveries = List.copyOf(deliveries);
    }
}
