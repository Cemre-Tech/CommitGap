# Contributing to CommitGap

Thank you for considering a contribution. CommitGap is a measurement tool, so the bar for changes is
that results stay honest: every reported outcome must come from a real measurement.

## Getting started

Requirements: Java 21+, Docker with Linux containers.

```bash
git clone https://github.com/[GITHUB_ORG]/commitgap.git
cd commitgap
./mvnw verify            # unit tests, no Docker
./mvnw -Pit verify       # integration tests against real containers
./commitgap doctor
```

## Where things live

See [docs/architecture.md](docs/architecture.md). In short: `commitgap-core` has no Spring or Docker
dependency; anything that touches containers belongs in `commitgap-runtime`; the system under test is
`commitgap-demo`; reports are built in `commitgap-report`; commands and exit codes are in
`commitgap-cli`.

## Rules for changes

- **No fake results.** Do not add fixed percentages, sample numbers or expected outcomes that are not
  measured. A new expected result needs an integration test that measures it and checks its evidence.
- **No timing-based faults.** Faults are bound to checkpoints that fire after the real stage; waits
  are condition-based with deadlines. Do not add `sleep` as synchronisation.
- **Keep the fragile strategies honest.** Do not make them fail through weaker broker settings or
  unrelated bugs, and do not make them safe by accident (for example a unique constraint on
  `stock_movement.event_id`).
- **Pinned versions only.** No `latest` image tags, no dynamic or snapshot dependency versions. The
  enforcer plugin checks the Maven side.
- **No credentials in output.** Anything that can reach a report, manifest or timeline goes through
  redaction.
- **Tests.** Unit tests for core logic (scenario validation, invariants, classification, report
  schema); integration tests for anything that depends on real PostgreSQL, RabbitMQ or Docker
  behaviour. Tests should not just repeat the implementation or rely on mocks for the behaviour
  being claimed.
- Scenario or report format changes need a schema version decision and documentation updates.
- Record significant design decisions as an ADR in `docs/adr/`.

## Pull requests

1. Open an issue first for larger changes.
2. Keep changes focused; update `CHANGELOG.md` under "Unreleased".
3. Make sure `./mvnw verify` passes; run `./mvnw -Pit verify` for runtime, demo or CLI changes.
4. Describe what you measured and how.

By contributing you agree that your contribution is licensed under the MIT License of this project.
Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).
