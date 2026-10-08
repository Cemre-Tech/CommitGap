# ADR 0002: One container per process; crashes are SIGKILL

Status: accepted (version 0.1.0)

## Context
A crash must be a crash: an exception or graceful shutdown runs finally blocks and closes
connections cleanly, which hides exactly the behaviour under test. Killing one process must not take
down the runner or the other processes.

## Decision
Producer, relay and consumer run as separate containers from the same demo jar. A `kill` fault sends
SIGKILL through the Docker API and is verified with `docker inspect`: the container must be stopped
with exit code 137. Recovery `restart` starts the same container again. The demo processes run on the
pinned `eclipse-temurin:21.0.12_8-jre-alpine` image with the jar copied in, so no image is built.

## Consequences
Startup costs a few seconds per process per run. A fault that cannot be verified makes the run
INCONCLUSIVE rather than counting as applied.
