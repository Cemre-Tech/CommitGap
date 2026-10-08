# ADR 0006: Temporary namespace, Jackson 3, SnakeYAML for scenarios

Status: accepted (version 0.1.0)

## Context
The publishing organisation's name, domain and Maven namespace are not known yet and must not be
invented. Spring Boot 4.1 uses Jackson 3.

## Decision
Use `com.example.commitgap` as groupId and package root: `com.example` is reserved for examples, so it
cannot collide with a real owner, and the code builds without placeholders. Use Jackson 3 (managed by
the Spring Boot BOM) for all JSON. Parse scenarios with SnakeYAML directly, with duplicate keys
disallowed and the safe constructor, and validate field by field so that every error names its path.
The report JSON is built field by field, not serialised from internal classes, so the schema changes
only on purpose.

## Consequences
Before publishing, the namespace must be renamed (see docs/release.md).
