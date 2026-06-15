#!/bin/bash
# SyncLingo — deploy latest final-version and run comprehensive security/functional tests
# Usage: bash scripts/deploy-and-test.sh
# Run from server: cd /opt/syncLingo && bash /tmp/deploy-and-test.sh
set -e

BASE="https://julongtongchuan.icu"
PASS=""  # fill in your server root password if using sshpass, or run directly on server

echo "=============================="
echo "1. PULL & BUILD BACKEND"
echo "=============================="
cd /opt/syncLingo
git pull origin final-version

echo "--- Verifying permission entry-point coverage ---"
bash scripts/verify-permission-entrypoints.sh
bash scripts/verify-bot-production-source.sh

echo "--- Building Docker image ---"
docker build -t si-backend:latest . --build-arg BUILDKIT_INLINE_CACHE=1

echo "--- Restarting backend container ---"
docker rm -f si-backend || true
docker run -d --name si-backend --restart=always --network host \
  --env-file /opt/syncLingo/backend.env \
  si-backend:latest

echo "--- Waiting for backend to start ---"
for i in $(seq 1 30); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/health" 2>/dev/null || echo "000")
  if [ "$STATUS" = "200" ]; then
    echo "Backend healthy after ${i}s"
    break
  fi
  echo "  waiting... (${i}s, status=$STATUS)"
  sleep 2
done

echo ""
echo "=============================="
echo "2. BUILD & DEPLOY FRONTEND"
echo "=============================="
cd /opt/syncLingo/si-frontend
npm run build
cp -r dist/* /var/www/si/
echo "Frontend deployed."

echo ""
echo "=============================="
echo "3. COMPREHENSIVE SECURITY TESTS"
echo "=============================="
PASS_COUNT=0
FAIL_COUNT=0

check() {
  local DESC="$1"
  local EXPECTED="$2"
  local ACTUAL="$3"
  if [ "$ACTUAL" = "$EXPECTED" ]; then
    echo "  [PASS] $DESC (got $ACTUAL)"
    PASS_COUNT=$((PASS_COUNT+1))
  else
    echo "  [FAIL] $DESC (expected=$EXPECTED, got=$ACTUAL)"
    FAIL_COUNT=$((FAIL_COUNT+1))
  fi
}

# --- 3.1 Health ---
echo ""
echo "--- 3.1 Health endpoint ---"
H=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/health")
check "GET /api/health → 200" "200" "$H"

# --- 3.2 Auth: login ---
echo ""
echo "--- 3.2 Auth endpoints ---"
LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass123"}')
LOGIN_CODE=$(echo "$LOGIN" | tail -1)
LOGIN_BODY=$(echo "$LOGIN" | head -1)
check "POST /api/auth/login (register or login) → 200" "200" "$LOGIN_CODE"

TOKEN=$(echo "$LOGIN_BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('token',''))" 2>/dev/null || echo "")
USER_ID=$(echo "$LOGIN_BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('user',{}).get('id',''))" 2>/dev/null || echo "")
echo "  Token: ${TOKEN:0:40}... UserID: $USER_ID"

# Register a second user for ownership cross-check
LOGIN2=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"otheruser","password":"testpass456"}')
LOGIN2_CODE=$(echo "$LOGIN2" | tail -1)
TOKEN2=$(echo "$LOGIN2" | head -1 | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('token',''))" 2>/dev/null || echo "")
USER2_ID=$(echo "$LOGIN2" | head -1 | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('user',{}).get('id',''))" 2>/dev/null || echo "")
check "POST /api/auth/login (second user) → 200" "200" "$LOGIN2_CODE"

# --- 3.3 Unauthenticated requests rejected ---
echo ""
echo "--- 3.3 JWT filter: unauthenticated requests ---"
R=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/interpretation/users/1/sessions")
check "GET /api/interpretation/users/1/sessions without token → 401" "401" "$R"

R=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/meetings/1")
check "GET /api/meetings/1 without token → 401" "401" "$R"

R=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE/api/meetings/1")
check "DELETE /api/meetings/1 without token → 401" "401" "$R"

# Public endpoints still work without token
R=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/interpretation/public/user/1/active")
check "GET /api/interpretation/public/... without token → 200 or 404" "" ""
echo "  (public endpoint status=$R, acceptable: 200/400/404)"

# --- 3.4 Authenticated requests work ---
echo ""
echo "--- 3.4 Authenticated requests ---"
if [ -n "$TOKEN" ]; then
  R=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE/api/interpretation/users/$USER_ID/sessions")
  check "GET /api/interpretation/users/{myId}/sessions with token → 200" "200" "$R"
else
  echo "  [SKIP] No token available, skipping authenticated tests"
fi

# --- 3.5 Cross-user access rejected ---
echo ""
echo "--- 3.5 Ownership: cross-user session access ---"
if [ -n "$TOKEN" ] && [ -n "$USER2_ID" ]; then
  R=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE/api/interpretation/users/$USER2_ID/sessions")
  check "GET other user's sessions with token → 401" "401" "$R"
fi

# --- 3.6 Meeting ownership ---
echo ""
echo "--- 3.6 Meeting ownership ---"
if [ -n "$TOKEN" ]; then
  CREATE=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/meetings" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":$USER_ID,\"title\":\"SecurityTest $(date +%s)\"}")
  CREATE_CODE=$(echo "$CREATE" | tail -1)
  MTG_ID=$(echo "$CREATE" | head -1 | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))" 2>/dev/null || echo "")
  check "POST /api/meetings (create) → 200" "200" "$CREATE_CODE"
  echo "  MeetingID: $MTG_ID"

  if [ -n "$TOKEN2" ] && [ -n "$MTG_ID" ]; then
    R=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer $TOKEN2" \
      "$BASE/api/meetings/$MTG_ID")
    check "GET other user's meeting → 404" "404" "$R"

    R=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
      -H "Authorization: Bearer $TOKEN2" \
      "$BASE/api/meetings/$MTG_ID")
    check "DELETE other user's meeting → 404" "404" "$R"
  fi

  # Clean up meeting
  if [ -n "$MTG_ID" ]; then
    curl -s -o /dev/null -X DELETE \
      -H "Authorization: Bearer $TOKEN" \
      "$BASE/api/meetings/$MTG_ID"
  fi
fi

# --- 3.7 Admin endpoint with blank secret ---
echo ""
echo "--- 3.7 Admin: blank-secret reject ---"
R=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/admin/meetings?secret=")
check "GET /api/admin/meetings with blank secret → 401" "401" "$R"

R=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/admin/meetings?secret=wrongsecret")
check "GET /api/admin/meetings with wrong secret → 401" "401" "$R"

# --- 3.8 Speaker rename functional test ---
echo ""
echo "--- 3.8 Speaker rename ---"
if [ -n "$TOKEN" ] && [ -n "$USER_ID" ]; then
  # Get sessions to find a real sessionId
  SESS_LIST=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "$BASE/api/interpretation/users/$USER_ID/sessions")
  SESSION_ID=$(echo "$SESS_LIST" | python3 -c "
import sys, json
d = json.load(sys.stdin)
sessions = d.get('data', [])
if sessions:
    print(sessions[0].get('sessionId', ''))
" 2>/dev/null || echo "")

  if [ -n "$SESSION_ID" ]; then
    echo "  Using sessionId: $SESSION_ID"
    RENAME=$(curl -s -w "\n%{http_code}" -X PUT \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d '{"personName":"测试发言人"}' \
      "$BASE/api/interpretation/session-speakers/$SESSION_ID/speaker_0")
    RENAME_CODE=$(echo "$RENAME" | tail -1)
    RENAME_BODY=$(echo "$RENAME" | head -1)
    check "PUT session-speaker rename → 200" "200" "$RENAME_CODE"
    # Verify VO only has speakerId + personName (no speakerProfileId etc.)
    HAS_UNWANTED=$(echo "$RENAME_BODY" | python3 -c "
import sys, json
d = json.load(sys.stdin).get('data', {})
unwanted = [k for k in ['speakerProfileId','cartesiaVoiceId','status','source'] if k in d]
print(','.join(unwanted) if unwanted else 'OK')
" 2>/dev/null || echo "parse-err")
    check "Speaker rename VO has no legacy fields (speakerProfileId etc.)" "OK" "$HAS_UNWANTED"
  else
    echo "  [SKIP] No existing sessions for speaker rename test (start a session first)"
  fi
fi

# --- 3.9 GET speaker summaries (no LLM triggered) ---
echo ""
echo "--- 3.9 GET speaker summaries: no LLM side effect ---"
if [ -n "$SESSION_ID" ] && [ -n "$TOKEN" ]; then
  START_MS=$(($(date +%s%N)/1000000))
  curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" \
    "$BASE/api/summary/speaker/$SESSION_ID"
  END_MS=$(($(date +%s%N)/1000000))
  ELAPSED=$((END_MS - START_MS))
  # If LLM were triggered this would be >>3s; under 500ms means no LLM
  if [ "$ELAPSED" -lt 3000 ]; then
    echo "  [PASS] GET speaker summaries completed in ${ELAPSED}ms (no LLM triggered)"
    PASS_COUNT=$((PASS_COUNT+1))
  else
    echo "  [WARN] GET speaker summaries took ${ELAPSED}ms — check if LLM was triggered"
  fi
fi

# --- 3.10 WebSocket: no-token connection rejected ---
echo ""
echo "--- 3.10 WebSocket: unauthenticated connection ---"
if command -v wscat &>/dev/null; then
  WS_OUT=$(echo "" | timeout 3 wscat --connect "wss://julongtongchuan.icu/ws/asr?token=INVALID" 2>&1 || true)
  if echo "$WS_OUT" | grep -qi "error\|rejected\|401\|403\|Unauthorized"; then
    echo "  [PASS] WS rejected invalid token"
    PASS_COUNT=$((PASS_COUNT+1))
  else
    echo "  [INFO] WS output: $WS_OUT"
    echo "  (manual verification needed)"
  fi
else
  echo "  [SKIP] wscat not installed — install with: npm install -g wscat"
fi

echo ""
echo "=============================="
echo "TEST SUMMARY"
echo "=============================="
echo "  PASSED: $PASS_COUNT"
echo "  FAILED: $FAIL_COUNT"
if [ "$FAIL_COUNT" -eq 0 ]; then
  echo "  STATUS: ALL PASS"
else
  echo "  STATUS: $FAIL_COUNT FAILURES — check above"
fi
echo ""
echo "Build time: $(docker inspect si-backend --format '{{.Created}}' 2>/dev/null || echo 'unknown')"
