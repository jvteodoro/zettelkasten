# Developer operations, Git hooks, CI and releases

## Local prerequisites

- JDK 25 for compilation, testing and runtime. Java 21 is not a supported target.
- Use the pinned Maven wrapper (`./mvnw`, Maven 3.9.11 distribution); the initial wrapper invocation needs network access if Maven is not cached. Maven tests and the packaged application do not require network access.
- Git and POSIX shell for the provided hooks.
- The compiler release is 25 and the build rejects an older JDK.

## Install hooks

~~~sh
bash scripts/install-git-hooks.sh
git config --local --get core.hooksPath
~~~

Expected result: `.githooks`. Hooks are POSIX shell scripts; cloning does not activate them automatically.

### pre-commit

- Validate staged filenames against accidental secrets/binary database/archive artifacts and merge-conflict markers.
- Reject trailing whitespace in staged Markdown/Java/YAML/JSON/SQL, except intentionally exempted generated fixtures.
- Reject critical security placeholders; normal TODOs are allowed.
- Run `git diff --cached --check`.
- Does not modify the working tree or format unstaged changes.

### pre-push

- Runs `mvn -B verify` when `pom.xml` exists.
- If Maven is missing after a pom exists, fails with an explicit instruction.
- `SKIP_LONG_HOOKS=1` can skip the local long check for emergency debugging; CI remains mandatory.

## CI

`.github/workflows/ci.yml` validates hook syntax and required docs, verifies the active Temurin JDK 25, runs `mvn -B verify`, and smoke-tests the shaded jar's help/version and offline init/doctor/backup/restore/export/import commands. Surefire and Failsafe reports are uploaded even when verification fails. Maven's wrapper is pinned, but CI uses its installed Maven.

## Tests and developer commands

~~~sh
./mvnw -B verify
./mvnw -B test
./mvnw -B -Dtest=AddressParserTest test
java -jar target/zettelkasten-0.1.0-SNAPSHOT-all.jar --help
~~~

Use a fresh temporary database for manual smoke tests. The process-driven acceptance test exercises the real terminal event loop and CLI in child JVMs with fabricated content.

## Versioned release procedure

1. In a reviewed change, update both `pom.xml` project version and `Main.VERSION` together. Build the resulting versioned `target/zettelkasten-<version>-all.jar` using JDK 25.
2. Run `./mvnw -B verify`, then check `--help`, `--version`, and offline CLI flows on a clean temporary archive.
3. Confirm class-file major version 69 and record the artifact checksum with `sha256sum`.
4. After the change is reviewed and merged, tag it `v<version>`. Publish the jar and checksum only as an explicit release; CI does not publish automatically.

## Commit and PR conventions

- Use prefixes such as `feat(domain): ...`, `feat(tui): ...`, `feat(storage): ...`, `test(...): ...`, `docs: ...`, and `fix(...): ...`.
- Each PR references its implementing issue, describes tests and manual reproduction, includes database compatibility notes, terminal evidence for UI changes where useful, and lists non-goals.
- Never commit user databases/backups, private research notes, Zotero local databases, secrets, editor caches, or terminal captures containing personal content.

## PR review and repository settings

See `.github/pull_request_template.md`. For schema changes, review migration safety, backup/restore, and deterministic export; for UI changes, check that no hidden shortcut was added. New dependencies need purpose, license, version, and fallback documented.

Repository owners should enable branch protection on `main` (required PR and CI, no force-push/delete, and review). This documentation does not claim those settings have been enabled.
