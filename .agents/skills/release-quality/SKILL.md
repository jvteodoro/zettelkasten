---
name: release-quality
description: Enforce tests, CI, agent issue gates, migration safety and portable archival guarantees.
---
# release-quality

1. Read AGENTS.md, docs/08-TEST-PLAN.md and docs/11-DEVELOPER-OPERATIONS.md.
2. Build only the linked issue, add positive and negative UX tests and document deviations.
3. Execute mvn -B verify when scaffold exists; never falsely claim green tests.
4. Check WAL backup/restore, foreign keys, canonical export roundtrip and migration fixtures.
5. Never commit user data/secrets or force push/merge main. Use ADR for architectural changes.
