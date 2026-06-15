#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:?Usage: download-backend-logs.sh <base-url> <admin-secret> [output-file]}"
ADMIN_SECRET="${2:?Usage: download-backend-logs.sh <base-url> <admin-secret> [output-file]}"
OUTPUT_FILE="${3:-si-backend-$(date +%F).log}"

curl --fail --silent --show-error \
  -H "X-Admin-Secret: ${ADMIN_SECRET}" \
  "${BASE_URL%/}/api/admin/logs/download" \
  --output "${OUTPUT_FILE}"

echo "Downloaded ${OUTPUT_FILE}"
