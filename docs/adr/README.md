# Architecture decision records

Short records of the decisions that shape CommitGap.

- [ADR 0001: Checkpoint coordination over a control API](0001-checkpoint-coordination.md)
- [ADR 0002: One container per process; crashes are SIGKILL](0002-separate-containers-and-sigkill.md)
- [ADR 0003: Database snapshots are the evidence; checks use identities](0003-database-snapshots-as-evidence.md)
- [ADR 0004: Queue depth from rabbitmqctl, not the management API](0004-queue-depth-from-rabbitmqctl.md)
- [ADR 0005: Label-scoped cleanup; the Testcontainers reaper is disabled in the CLI](0005-label-scoped-cleanup.md)
- [ADR 0006: Temporary namespace, Jackson 3, SnakeYAML for scenarios](0006-temporary-namespace-and-libraries.md)
- [ADR 0007: Quorum queue with a delivery limit; same broker settings for all strategies](0007-quorum-queue-bounded-redelivery.md)
- [ADR 0008: A single relay without claims or leases](0008-single-relay.md)
