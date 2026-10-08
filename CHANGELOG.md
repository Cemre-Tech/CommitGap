# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
