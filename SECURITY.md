# Security policy

## Reporting a vulnerability

Please do not open a public issue for security problems. Report them privately to
**[SECURITY_CONTACT]**, with steps to reproduce and the CommitGap version (`commitgap --version`).
You will receive an acknowledgement, and we will coordinate a fix and disclosure with you.

Only the latest released version receives security fixes. No release has been published yet.

## Security model

CommitGap is a local laboratory. It is meant to run on a developer machine or a CI runner, against
its own throwaway environment.

- **Scope of actions.** Faults (SIGKILL, network cuts) are applied only to containers and proxies
  that the run created. Cleanup removes only Docker resources labelled `commitgap.managed=true` with
  the selected run id. CommitGap never prunes Docker, never touches other containers and never
  connects to databases or brokers it did not start.
- **Credentials.** Database and broker passwords are generated per run and live only in that run's
  containers. They are not written to reports, manifests or timelines; error text from Docker or
  Testcontainers is scrubbed before it is recorded. They are visible to anyone who can run
  `docker inspect` on the machine while the run's containers exist.
- **Network exposure.** Lab containers publish their ports on random host ports, as Docker does by
  default. The demo processes expose an unauthenticated control API (checkpoints, fault switches)
  that exists only for the lab. Do not run CommitGap on a host whose published ports are reachable
  from untrusted networks, and remove kept runs when you are done (`commitgap cleanup --run <id>`).
- **Scenario files** are data. They are parsed with a safe YAML constructor that refuses tags; no
  field is executed as a command.
- **Reports** are static HTML without scripts or external resources.
- **Supply chain.** Maven dependencies and container images are pinned to exact versions. Images are
  pulled from their public registries (Docker Hub, GitHub Container Registry).
