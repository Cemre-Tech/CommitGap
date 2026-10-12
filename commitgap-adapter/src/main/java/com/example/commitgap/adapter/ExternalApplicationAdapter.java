package com.example.commitgap.adapter;

import com.example.commitgap.core.workload.WorkloadPlan;

/**
 * Reads application-owned business data without changing it. Map only this run's rows to the core
 * stock/order model, with a consistent snapshot within each database. The caller owns workload
 * driving, recovery, and stopping writers while capturing across databases and the broker.
 *
 * <p>Never substitute an empty snapshot for a failed read. Throw instead; the verifier reports an
 * inconclusive measurement without exposing the exception message (which may contain credentials).
 */
@FunctionalInterface
public interface ExternalApplicationAdapter {
    ExternalObservation capture(WorkloadPlan plan) throws Exception;
}
