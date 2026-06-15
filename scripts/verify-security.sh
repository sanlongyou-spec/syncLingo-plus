#!/bin/bash
# Quick security verification — run on server after deploy
# Usage: bash /opt/syncLingo/scripts/verify-security.sh
BASE="https://julongtongchuan.icu"
P=0; F=0
ok()  { echo "[PASS] $1"; P=$((P+1)); }
fail(){ echo "[FAIL] $1 (expected=$2 got=$3)"; F=$((F+1)); }
chk() { local d=$1 e=$2 a=$3; [ "$a" = "$e" ] && ok "$d" || fail "$d" "$e" "$a"; }

echo "=== Health ==="
chk "health" "200" "$(curl -s -o/dev/null -w%{http_code} $BASE/api/health)"

echo "=== Auth ==="
BODY=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type:application/json' \
  -d '{"username":"sectest1","password":"pass1234"}')
CODE=$(curl -s -o/dev/null -w%{http_code} -X POST $BASE/api/auth/login \
  -H 'Content-Type:application/json' -d '{"username":"sectest1","password":"pass1234"}')
chk "login" "200" "$CODE"
TOK=$(echo $BODY | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('token',''))" 2>/dev/null)
UID=$(echo $BODY | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('user',{}).get('id',''))" 2>/dev/null)

BODY2=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type:application/json' \
  -d '{"username":"sectest2","password":"pass5678"}')
TOK2=$(echo $BODY2 | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('token',''))" 2>/dev/null)
UID2=$(echo $BODY2 | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('user',{}).get('id',''))" 2>/dev/null)

echo "=== JWT Filter (no token → 401) ==="
chk "sessions-notoken"  "401" "$(curl -s -o/dev/null -w%{http_code} $BASE/api/interpretation/users/1/sessions)"
chk "meetings-notoken"  "401" "$(curl -s -o/dev/null -w%{http_code} $BASE/api/meetings/1)"
chk "summary-notoken"   "401" "$(curl -s -o/dev/null -w%{http_code} $BASE/api/summary/speaker/X)"

echo "=== Auth works with token ==="
chk "sessions-authed" "200" "$(curl -s -o/dev/null -w%{http_code} -H "Authorization: Bearer $TOK" $BASE/api/interpretation/users/$UID/sessions)"

echo "=== Cross-user ownership ==="
chk "sessions-crossuser" "403" "$(curl -s -o/dev/null -w%{http_code} -H "Authorization: Bearer $TOK" $BASE/api/interpretation/users/$UID2/sessions)"

# Meeting ownership
M=$(curl -s -X POST $BASE/api/meetings \
  -H "Authorization: Bearer $TOK" -H 'Content-Type:application/json' \
  -d "{\"userId\":$UID,\"title\":\"sec-test-$(date +%s)\"}")
MID=$(echo $M | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('id',''))" 2>/dev/null)
if [ -n "$MID" ]; then
  chk "mtg-read-own"    "200" "$(curl -s -o/dev/null -w%{http_code} -H "Authorization: Bearer $TOK" $BASE/api/meetings/$MID)"
  chk "mtg-read-other"  "404" "$(curl -s -o/dev/null -w%{http_code} -H "Authorization: Bearer $TOK2" $BASE/api/meetings/$MID)"
  chk "mtg-delete-other" "404" "$(curl -s -o/dev/null -w%{http_code} -X DELETE -H "Authorization: Bearer $TOK2" $BASE/api/meetings/$MID)"
  # cleanup
  curl -s -o/dev/null -X DELETE -H "Authorization: Bearer $TOK" $BASE/api/meetings/$MID
fi

echo "=== Admin query secrets rejected ==="
chk "admin-blank"  "401" "$(curl -s -o/dev/null -w%{http_code} "$BASE/api/admin/meetings?secret=")"
chk "admin-wrong"  "401" "$(curl -s -o/dev/null -w%{http_code} "$BASE/api/admin/meetings?secret=WRONG")"

echo "=== Speaker rename VO format ==="
SESS=$(curl -s -H "Authorization: Bearer $TOK" $BASE/api/interpretation/users/$UID/sessions | \
  python3 -c "import sys,json;d=json.load(sys.stdin);ss=d.get('data',[]);print(ss[0]['sessionId'] if ss else '')" 2>/dev/null)
if [ -n "$SESS" ]; then
  RES=$(curl -s -X PUT -H "Authorization: Bearer $TOK" -H 'Content-Type:application/json' \
    -d '{"personName":"张三"}' "$BASE/api/interpretation/session-speakers/$SESS/speaker_0")
  BAD=$(echo $RES | python3 -c "
import sys,json
d=json.load(sys.stdin).get('data',{})
bad=[k for k in ['speakerProfileId','cartesiaVoiceId','status','source'] if k in d]
print(','.join(bad) or 'OK')" 2>/dev/null)
  chk "rename-vo-clean" "OK" "$BAD"
else
  echo "[SKIP] no session for speaker rename test"
fi

echo ""
echo "=== RESULT: PASS=$P FAIL=$F ==="
