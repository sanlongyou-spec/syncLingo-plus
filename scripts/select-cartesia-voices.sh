#!/usr/bin/env bash
# Select native Cartesia voices for zh/en/id by gender and write them to backend.env.
#
# Usage:
#   bash /opt/syncLingo/scripts/select-cartesia-voices.sh [backend.env]
#
# The script reads CARTESIA_API_KEY from backend.env, calls Cartesia /voices,
# writes CARTESIA_{ZH|EN|ID}_{MALE|FEMALE}_VOICE_ID, and stores the full
# candidate list in /tmp/cartesia-voice-candidates.txt for manual review.
set -euo pipefail

ENV_FILE="${1:-/opt/syncLingo/backend.env}"
CARTESIA_VERSION="${CARTESIA_VERSION:-2026-03-01}"
API="${CARTESIA_VOICES_API:-https://api.cartesia.ai/voices}"
CANDIDATES_FILE="${CANDIDATES_FILE:-/tmp/cartesia-voice-candidates.txt}"

if [ ! -f "$ENV_FILE" ]; then
  echo "env file not found: $ENV_FILE" >&2
  exit 1
fi

KEY=$(grep -E '^CARTESIA_API_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r')
if [ -z "${KEY:-}" ]; then
  echo "CARTESIA_API_KEY not found in $ENV_FILE" >&2
  exit 1
fi

: > "$CANDIDATES_FILE"

fetch_voices() {
  local lang="$1"
  local gender="$2"
  curl -sfS "$API?language=$lang&gender=$gender&limit=20" \
    -H "Authorization: Bearer $KEY" \
    -H "Cartesia-Version: $CARTESIA_VERSION"
}

pick_voice() {
  python3 -c '
import json
import sys

try:
    data = json.load(sys.stdin).get("data", [])
except Exception:
    data = []

public = [v for v in data if v.get("is_public")]
chosen = public or data
if chosen:
    voice = chosen[0]
    print((voice.get("id") or "") + "|" + (voice.get("name") or ""))
else:
    print("|")
'
}

dump_candidates() {
  python3 -c '
import json
import sys

try:
    data = json.load(sys.stdin).get("data", [])
except Exception:
    data = []

for voice in data:
    print(
        "  ",
        voice.get("id", ""),
        "|",
        voice.get("name", "") or "",
        "|",
        "pub" if voice.get("is_public") else "own",
        "|",
        (voice.get("description", "") or "")[:90],
    )
'
}

set_env() {
  local name="$1"
  local value="$2"
  if grep -qE "^$name=" "$ENV_FILE"; then
    sed -i "s|^$name=.*|$name=$value|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$name" "$value" >> "$ENV_FILE"
  fi
}

declare -A LANG_TAG=( [zh]=ZH [en]=EN [id]=ID )

echo "Selecting native Cartesia voices by language and gender..."
for lang in zh en id; do
  for gender in masculine feminine; do
    json=$(fetch_voices "$lang" "$gender")
    res=$(printf '%s' "$json" | pick_voice)
    voice_id="${res%%|*}"
    voice_name="${res#*|}"
    gender_tag=$([ "$gender" = "masculine" ] && echo MALE || echo FEMALE)
    var_name="CARTESIA_${LANG_TAG[$lang]}_${gender_tag}_VOICE_ID"

    {
      echo "===== $lang / $gender (selected: ${voice_id:-none} ${voice_name}) ====="
      printf '%s' "$json" | dump_candidates
    } >> "$CANDIDATES_FILE"

    if [ -n "$voice_id" ]; then
      set_env "$var_name" "$voice_id"
      printf '  %-30s = %s  (%s)\n' "$var_name" "$voice_id" "$voice_name"
    else
      printf '  %-30s <no matched voice; runtime will use fallback>\n' "$var_name"
    fi
  done
done

echo
echo "Full candidate list: $CANDIDATES_FILE"
echo "Updated env file: $ENV_FILE"
echo "Restart backend container to apply:"
echo "  docker rm -f si-backend && docker run -d --name si-backend --restart=always --network host \\"
echo "    --env-file $ENV_FILE -e JAVA_OPTS=\"-Xms512m -Xmx3g\" si-backend:latest"
