#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_ROOT="${REPO_ROOT}/si-backend"
REPORT="${BACKEND_ROOT}/target/permission-entrypoints-report.json"

cd "${BACKEND_ROOT}"
mvn -q -Dtest=PermissionEntryPointCoverageTest test

if [ ! -s "${REPORT}" ]; then
  echo "Permission entry-point report was not generated: ${REPORT}" >&2
  exit 1
fi

echo "Permission entry-point coverage guard passed: ${REPORT}"
