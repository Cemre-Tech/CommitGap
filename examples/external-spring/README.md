# External Spring application example

This standalone application owns its H2 schema and business code. It consumes the
`commitgap-adapter` SDK without using `commitgap-demo` or the Docker runner. It proves
that externally owned transaction records can be evaluated by CommitGap's existing
event-identity invariants.

Java 21 or newer is required. From the repository root, first install the SDK and
its reactor dependencies, then build and run the separate application:

```sh
./mvnw -B -pl commitgap-adapter -am install
./mvnw -B -f examples/external-spring/pom.xml verify
java -jar examples/external-spring/target/external-spring-example-0.1.0-SNAPSHOT.jar
```

On Windows, use `mvnw.cmd` for the Maven commands. No Docker or external database is
needed. `--example.case=loss`, `duplicate`, or `safe` selects one experiment; the
default runs all three. The output includes the real event ID and any failed
invariant's identity evidence:

```text
loss: VIOLATION_OBSERVED | event=... | effects=0 | duplicateEffects=0
duplicate: VIOLATION_OBSERVED | event=... | effects=2 | duplicateEffects=1
safe: CONSISTENT | event=... | effects=1 | duplicateEffects=0
```

`InventoryService` commits the producer transaction before hitting the producer
checkpoint. The consumer commits stock, ledger, and optional dedup records in one
transaction before hitting its acknowledgement checkpoint. `ExampleCases` waits
for the exact event at that boundary, cooperatively aborts its worker, joins it,
and explicitly drives redelivery/recovery. The verifier does not drive the app.

H2 executes real JDBC transactions. The transport is an in-process queue model:
this example makes no RabbitMQ durability, network, publisher-confirm, or
process-kill claim. A cooperative exception is different from SIGKILL. Use the
built-in Docker laboratory for those experiments.

Snapshots read all application tables within one read-only repeatable-read
transaction, filter every read by a unique run ID, and are taken after the driver
has stopped its workers. Each run inserts fresh records without resetting tables;
even repeated event IDs from an identical seed stay isolated.
An activity generation checked before and after capture detects any worker that
starts or finishes during the reads, including changes to the local transport;
such a capture is marked non-quiescent and the verifier returns `INCONCLUSIVE`.
The example's SQL is
fixed application code, never user-supplied verifier configuration. The database
is local and ephemeral; no connection URL, password, or environment value is
printed. Do not point this sample's schema initializer at an existing application
database: implement that application's read-only adapter instead.

The six tests exercise actual loss, duplicate application, safe redelivery,
rollback, isolation between runs sharing the same deterministic event ID, and a
writer overlapping capture through a deterministic JDBC barrier.
See [the integration contract](../../docs/external-applications.md) to adapt your
own application's reads and checkpoint hooks.
