package com.example.commitgap.core.scenario;

/**
 * The business workload. The seed makes order ids, event ids and the rollback pattern reproducible;
 * it does not make wall-clock timing, scheduling or network latency reproducible.
 *
 * @param rollbackEvery   every n-th order is deliberately rolled back by the producer; 0 disables rollbacks
 * @param consumerWorkers number of concurrent consumer worker threads in the consumer process
 * @param relayWorkers    number of concurrent relay worker threads in the single relay process
 */
public record Workload(
        long seed,
        int orders,
        int quantityPerOrder,
        int initialStock,
        int rollbackEvery,
        int consumerWorkers,
        int relayWorkers) {

    /** Existing workloads keep a single relay worker. */
    public Workload(long seed, int orders, int quantityPerOrder, int initialStock, int rollbackEvery,
                    int consumerWorkers) {
        this(seed, orders, quantityPerOrder, initialStock, rollbackEvery, consumerWorkers, 1);
    }

    public boolean isRolledBack(int orderNumber) {
        return rollbackEvery > 0 && orderNumber % rollbackEvery == 0;
    }

    public int committedOrderCount() {
        return rollbackEvery > 0 ? orders - orders / rollbackEvery : orders;
    }
}
