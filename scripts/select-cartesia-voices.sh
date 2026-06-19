#!/usr/bin/env bash
# 按官方方案为每个语种×性别挑选 Cartesia 母语音色，并写入 backend.env。
#
# 选取策略：调用官方 GET /voices?language=<lang>&gender=<g>，取该语种的母语音色
# （优先公共音色），保证每种语言都用本语言的音色，从根本上消除跨语种"怪声"。
#
# 用法（服务器）：bash /opt/syncLingo/scripts/select-cartesia-voices.sh [backend.env路径]
# 跑完重启后端容器生效。打印每个选中的音色名，便于你耳测后按需替换。
set -euo pipefail

ENV_FILE="${1:-/opt/syncLingo/backend.env}"
CARTESIA_VERSION="2026-03-01"
API="https://api.cartesia.ai/voices"

[ -f "$ENV_FILE" ] || { echo "找不到 env 文件: $ENV_FILE"; exit 1; }
KEY=$(grep -E '^CARTESIA_API_KEY=' "$ENV_FILE" | head -1 | cut -d= -f2-)
[ -n "${KEY:-}" ] || { echo "$ENV_FILE 中未找到 CARTESIA_API_KEY"; exit 1; }

# 后续精确指定用的完整候选清单文件
CANDIDATES_FILE="${CANDIDATES_FILE:-/tmp/cartesia-voice-candidates.txt}"
: > "$CANDIDATES_FILE"

fetch_voices() {  # $1=language  $2=gender
  curl -s "$API?language=$1&gender=$2&limit=20" \
    -H "Authorization: Bearer $KEY" \
    -H "Cartesia-Version: $CARTESIA_VERSION"
}

pick_voice() {  # stdin=json ; 输出 id|name
  python3 -c '
import sys, json
try:
    data = json.load(sys.stdin).get("data", [])
except Exception:
    data = []
public = [v for v in data if v.get("is_public")]
chosen = (public or data)
if chosen:
    v = chosen[0]
    print(v.get("id", "") + "|" + (v.get("name", "") or ""))
else:
    print("|")
'
}

dump_candidates() {  # stdin=json ; 追加全部候选到清单文件
  python3 -c '
import sys, json
try:
    data = json.load(sys.stdin).get("data", [])
except Exception:
    data = []
for v in data:
    print("  ", v.get("id",""), "|", (v.get("name","") or ""),
          "|", ("pub" if v.get("is_public") else "own"),
          "|", (v.get("description","") or "")[:90])
'
}

set_env() {  # $1=KEY  $2=VALUE
  if grep -qE "^$1=" "$ENV_FILE"; then
    sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"
  else
    echo "$1=$2" >> "$ENV_FILE"
  fi
}

declare -A LANG_TAG=( [zh]=ZH [en]=EN [id]=ID )
echo "为每个语种×性别挑选母语音色:"
for lang in zh en id; do
  for gender in masculine feminine; do
    json=$(fetch_voices "$lang" "$gender")
    res=$(printf '%s' "$json" | pick_voice)
    vid="${res%%|*}"; vname="${res#*|}"
    gtag=$([ "$gender" = masculine ] && echo MALE || echo FEMALE)
    var="CARTESIA_${LANG_TAG[$lang]}_${gtag}_VOICE_ID"
    {
      echo "===== $lang / $gender  (选中: ${vid:-无} ${vname}) ====="
      printf '%s' "$json" | dump_candidates
    } >> "$CANDIDATES_FILE"
    if [ -n "$vid" ]; then
      set_env "$var" "$vid"
      printf '  %-28s = %s  (%s)\n' "$var" "$vid" "$vname"
    else
      printf '  %-28s   <该语种无匹配音色，留空，运行时回退全局音色>\n' "$var"
    fi
  done
done

echo
echo "完整候选清单已存到: $CANDIDATES_FILE （后面发我即可精确指定）"
echo "已写入 $ENV_FILE。重启后端容器后生效："
echo "  docker rm -f si-backend && docker run -d --name si-backend --restart=always --network host \\"
echo "    --env-file $ENV_FILE -e JAVA_OPTS=\"-Xms512m -Xmx3g\" si-backend:latest"
