# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Event story in static HTML reports: recorded checkpoint, fault, recovery and per-event evidence,
  with strategy mechanisms kept separate from measured correctness.
- Dependency-light Java adapter SDK and a standalone Spring JDBC example with application-owned,
  run-scoped observation, bounded cooperative checkpoints and measured loss/duplicate/safe cases.
- `publisher-confirm-gap`, `confirm-loss`, `consumer-transaction-crash`, `competing-relays` and `relay-lock-crash`
  scenarios; configurable relay workers coordinated by transaction-held PostgreSQL row locks.
- Real downstream confirm loss on a warmed publishing connection, proven by both a same-event
  publisher timeout and direct consumer receipt before restoring responses and allowing an outbox retry.

### Fixed

- Database snapshot queries now share a read-only repeatable-read transaction within each database;
  a real PostgreSQL regression proves a writer cannot split stock and ledger observations.
- Docker resource-cleanup integration test resolves its pinned probe image before using the raw
  Docker API, so it no longer depends on a warm developer image cache.

## [0.1.0] - not released yet

### Added

- Three strategies on one order/stock example: `naive-dual-write`, `transactional-outbox`,
  `outbox-idempotent`, each with producer, relay (outbox only) and consumer in separate containers.
- Checkpoints `producer.after-db-commit-before-publish`,
  `relay.after-publisher-confirm-before-outbox-mark`, `consumer.after-business-commit-before-ack`,
  with SIGKILL faults verified by exit code 137 and container restart.
- Duplicate publishing and Toxiproxy broker-link cuts.
- Six scenarios: `happy-path`, `crash-after-commit`, `relay-confirm-gap`, `duplicate-delivery`,
  `consumer-ack-gap`, `broker-outage`; strict scenario YAML (schema version 1).
- Identity-based invariants on producer and consumer database snapshots; outcome and expectation
  reported separately.
- Versioned JSON report (schema version 1) and self-contained HTML report.
- CLI commands `doctor`, `demo`, `scenarios list`, `run`, `compare`, `report`, `cleanup`, with Bash,
  PowerShell and cmd launchers.
- Label-scoped resource cleanup, `--keep` and `--keep-on-failure`.
- Unit tests, integration tests against real containers, GitHub Actions workflow.
