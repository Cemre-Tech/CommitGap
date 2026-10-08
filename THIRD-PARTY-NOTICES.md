# Third-party components and licenses

CommitGap is released under the MIT License (see `LICENSE`). This file lists what it builds on and
how each component reaches users. It is a review aid, not legal advice; the publishing organisation
should confirm it against its own policy before a release.

## Bundled in the runnable jars

The CLI jar (`commitgap-cli`) and the demo jar (`commitgap-demo`) are Spring Boot executable jars
that contain their dependencies unmodified.

| Component | Version | License | Used by |
|---|---|---|---|
| Spring Boot, Spring Framework, Spring AMQP | 4.1.1 / managed | Apache-2.0 | demo; Spring Boot loader in the CLI jar |
| RabbitMQ Java client | 5.30.0 (managed) | Apache-2.0 / MPL-2.0 / GPL-2.0 (triple licensed; used under Apache-2.0) | demo |
| PostgreSQL JDBC driver | 42.7.13 (managed) | BSD-2-Clause | demo, runtime |
| Flyway (core, database-postgresql) | 12.4.0 (managed) | Apache-2.0 | demo |
| HikariCP | managed | Apache-2.0 | demo |
| Jackson 3 (databind, core) | 3.1.5 (managed) | Apache-2.0 | demo, runtime, report |
| SnakeYAML | 2.6 (managed) | Apache-2.0 | core |
| Testcontainers (core, postgresql, rabbitmq, toxiproxy) | 2.0.5 | MIT | runtime |
| docker-java (shaded in Testcontainers) | managed | Apache-2.0 | runtime |
| toxiproxy-java | 2.1.11 | MIT | runtime |
| picocli | 4.7.7 | Apache-2.0 | CLI |
| SLF4J (api, simple) | managed | MIT | runtime, CLI |
| JNA (via Testcontainers) | managed | Apache-2.0 / LGPL-2.1 (dual; used under Apache-2.0) | runtime |

Test-only dependencies (not distributed): JUnit (EPL-2.0), AssertJ (Apache-2.0), Awaitility
(Apache-2.0), json-schema-validator by networknt (Apache-2.0).

## Container images pulled at run time (not redistributed)

The lab pulls these images from their public registries on first use. CommitGap does not ship them.

| Image | License of the software |
|---|---|
| `postgres:18.6-alpine` | PostgreSQL License |
| `rabbitmq:4.3.6-management-alpine` | MPL-2.0 |
| `ghcr.io/shopify/toxiproxy:2.12.0` | MIT |
| `eclipse-temurin:21.0.12_8-jre-alpine` | GPL-2.0 with Classpath Exception |
| `testcontainers/ryuk` (only when the Testcontainers reaper is enabled, e.g. in tests) | MIT |

Base images contain additional packages (Alpine Linux) under their own licenses.

None of these licenses conflicts with distributing CommitGap's own code under MIT. The triple- or
dual-licensed libraries are used under their permissive option.
