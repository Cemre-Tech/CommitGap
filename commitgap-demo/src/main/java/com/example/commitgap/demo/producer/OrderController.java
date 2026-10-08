package com.example.commitgap.demo.producer;

import com.example.commitgap.demo.events.OrderCreatedEvent;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "commitgap", name = "role", havingValue = "producer")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    /** The runner supplies both ids so that the workload is reproducible from the seed. */
    public record CreateOrder(UUID orderId, UUID eventId, String sku, int quantity, boolean rollback) {
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderService.Result> create(@RequestBody CreateOrder request) {
        OrderService.Result result = orders.create(
                new OrderCreatedEvent(request.eventId(), request.orderId(), request.sku(), request.quantity()),
                request.rollback());
        HttpStatus status;
        if (!result.committed()) {
            status = HttpStatus.CONFLICT;
        } else if (result.publishResult() != null && !result.published()) {
            // Committed, but the event did not reach the broker. The naive producer keeps no record of it.
            status = HttpStatus.ACCEPTED;
        } else {
            status = HttpStatus.CREATED;
        }
        return ResponseEntity.status(status).body(result);
    }
}
