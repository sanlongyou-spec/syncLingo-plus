#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORBIDDEN='external/Microsoft-Teams-Samples/samples/bot-calling-meeting/csharp/Source/CallingBotSample'
SEARCH_TARGETS=("${REPO_ROOT}/scripts")

for compose_file in "${REPO_ROOT}/docker-compose.yml" "${REPO_ROOT}/docker-compose.prod.yml"; do
  if [ -f "${compose_file}" ]; then
    SEARCH_TARGETS+=("${compose_file}")
  fi
done

if grep -R --line-number --fixed-strings \
  --exclude="$(basename "${BASH_SOURCE[0]}")" \
  "${FORBIDDEN}" "${SEARCH_TARGETS[@]}"; then
  echo "Deployment references the external Bot sample. Use bot/CallingBotSample only." >&2
  exit 1
fi

echo "Bot production source guard passed: bot/CallingBotSample"
