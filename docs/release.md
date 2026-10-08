# Building, running and packaging a release

No Maven, npm or Docker package and no GitHub release of CommitGap has been published. This page
describes how to build one; publishing it is up to the maintainers.

## Requirements

- Java 21 or newer (the build targets Java 21 with `--release 21`; the CI workflow builds with
  Temurin 21, and the demo processes always run on the pinned Temurin 21 image)
- A running Docker engine with Linux containers for anything that starts a lab
- Network access on the first build (Maven and dependencies) and the first run (container images)

The launchers do not install any of these.

## Build

```bash
./mvnw -DskipTests package        # Windows: mvnw.cmd -DskipTests package
```

Produces `commitgap-cli/target/commitgap-cli-exec.jar` and
`commitgap-demo/target/commitgap-demo-exec.jar`. The launchers run this build automatically when the
jars are missing or older than the main sources.

## Tests

```bash
./mvnw verify                     # unit tests, no Docker needed
./mvnw -Pit verify                # plus integration tests against real containers (Docker needed, ~10 min)
```

## Run

```bash
./commitgap doctor
./commitgap demo
```

On Windows: `.\commitgap.ps1 doctor`, or `commitgap.cmd doctor` from `cmd.exe` (it runs the
PowerShell launcher with the execution policy relaxed for that one process).

## Package

```bash
./mvnw -Pdist -DskipTests package
```

Produces `commitgap-cli/target/commitgap-<version>-dist.zip`:

```
commitgap-<version>/
  commitgap  commitgap.ps1  commitgap.cmd
  lib/commitgap-cli.jar  lib/commitgap-demo.jar
  scenarios/  docs/
  LICENSE  README.md  README.tr.md  CHANGELOG.md  SECURITY.md  THIRD-PARTY-NOTICES.md
```

The launchers detect the `lib/` layout and do not try to build. Runs are written to
`./.commitgap/runs` in the current directory (override with `--runs-dir` or `COMMITGAP_RUNS_DIR`).

## Before a public release

Replace the placeholders, which are deliberately not filled in:

| Placeholder | Where |
|---|---|
| `[COMPANY_NAME]` | `LICENSE`, README files, HTML report footer (`HtmlReport`) |
| `[GITHUB_ORG]` | README files, CONTRIBUTING.md |
| `[SECURITY_CONTACT]` | SECURITY.md |
| `[CONDUCT_CONTACT]` | CODE_OF_CONDUCT.md |
| `[MAVEN_NAMESPACE]` | `groupId` and Java packages, currently the temporary `com.example.commitgap` |

`com.example` is reserved for examples and owned by no one, which is why it is used as the temporary
namespace. Renaming it means changing the `groupId` in every `pom.xml` and moving the packages.

Then: set the version (drop `-SNAPSHOT`) in the POMs, update `CHANGELOG.md`, run `./mvnw -Pit verify`
and `./commitgap demo` on Linux, build the zip, and attach it to a tagged release.
