#!/usr/bin/env bash
set -euo pipefail

gradle --quiet \
  --init-script scripts/main.groovy \
  --init-script scripts/main_test.groovy \
  help | grep -E 'Installed Painless|All Painless compatibility tests passed'
