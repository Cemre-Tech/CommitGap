# ADR 0005: Label-scoped cleanup; the Testcontainers reaper is disabled in the CLI

Status: accepted (version 0.1.0)

## Context
Runs must not interfere with each other or with anything else on the machine. Users need to keep a
failed run's containers for inspection and remove them later with `commitgap cleanup --run`. The
Testcontainers reaper (Ryuk) removes everything of a session when the JVM exits, which would make
`--keep` impossible.

## Decision
Every container and network carries `commitgap.managed=true`, `commitgap.run=<id>` and, for cells,
`commitgap.parent-run=<id>`; names are prefixed `commitgap-<id>`; ports are random; credentials are
generated per run and scrubbed from every text that can reach a report or timeline. Cleanup lists
resources by these labels and removes only those; it never prunes. The launchers set
`TESTCONTAINERS_RYUK_DISABLED=true` unless the user set it; a shutdown hook removes running labs on
interrupt unless keeping was requested. Integration tests run with the reaper enabled.

## Consequences
If the JVM is killed hard, resources can remain; `commitgap cleanup --run <id>` removes them. An
integration test checks that cleaning one run leaves other runs and unlabelled containers untouched.
