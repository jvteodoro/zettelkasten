# ADR 0004: Java 25 is the sole supported runtime baseline

Status: Accepted (design, pending implementation) — 2026-11-11.

## Context

The user selected Java 25 for the entire Zettelkasten implementation. The repository previously documented Java 21 in its scaffold, CI and agent instructions. An inconsistent release target would break the handoff to the implementation agent.

## Decision

- Use Java 25 LTS for the application, domain libraries, terminal UI, CLI and all automated tests.
- Require JDK 25 to build and execute the packaged application. Set Maven compiler release to 25 and enforce the actual JDK version through Maven configuration and CI, with compatible pinned plugin versions.
- GitHub Actions must install Temurin 25, run `java -version` and `mvn -B verify`.
- Keep the distribution entirely offline-capable; do not silently downgrade the language level to Java 21 for compatibility.
- Avoid preview features and incubator APIs in the default release; a later decision requires a separate ADR and runtime flags.

## Consequences

Newer JDK requirement for contributors and end users. Check the compatibility of Lanterna, SQLite JDBC, coverage/instrumentation, Maven Surefire/Failsafe, shading/native packaging and terminal handling on JDK 25. Add a packaging smoke test that starts and runs the CLI on JDK 25.

## Validation

ZK-01 must implement the Maven enforcer/compiler settings and make CI fail under Java 21; ZK-15 must run packaged artifacts on JDK 25. Until the scaffold exists, this ADR and CI are the authoritative intent, **not proof of a passing Java build**.
