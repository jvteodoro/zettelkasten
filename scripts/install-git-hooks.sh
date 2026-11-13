#!/usr/bin/env bash
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
cd "$root"
for hook in .githooks/pre-commit .githooks/pre-push; do
  test -f "$hook" || { echo "Missing $hook" >&2; exit 1; }
  chmod +x "$hook"
done
git config --local core.hooksPath .githooks
echo "Hooks enabled: $(git config --local core.hooksPath)"
echo "To revert: git config --local --unset core.hooksPath"
