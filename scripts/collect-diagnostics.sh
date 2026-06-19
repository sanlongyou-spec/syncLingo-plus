#!/usr/bin/env bash
# 一次性收集 syncLingo 全部服务的最细粒度日志，打包成单个 tar.gz 便于下载分享。
#
# 用法（在服务器上执行）：
#   bash /opt/syncLingo/scripts/collect-diagnostics.sh ["时间范围"]
# 示例：
#   bash /opt/syncLingo/scripts/collect-diagnostics.sh "30 minutes ago"
#   bash /opt/syncLingo/scripts/collect-diagnostics.sh "2 hours ago"
#
# 输出：/tmp/synclingo-diag-<时间戳>.tar.gz
# 注意：只收集日志，不包含 backend.env 等密钥文件。
set -euo pipefail

SINCE="${1:-30 minutes ago}"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-si-backend}"
STAMP="$(date +%Y%m%d_%H%M%S)"
WORK="/tmp/synclingo-diag-${STAMP}"
mkdir -p "${WORK}"

echo "[collect] since=\"${SINCE}\" container=${BACKEND_CONTAINER} -> ${WORK}"

# 1) 后端容器 stdout/stderr（含 SI_LOG_LEVEL=DEBUG 时的最细粒度日志）
docker logs --since "${SINCE}" "${BACKEND_CONTAINER}" > "${WORK}/backend-docker.log" 2>&1 \
  || echo "[collect] WARN: docker logs ${BACKEND_CONTAINER} failed"

# 2) 后端滚动文件日志 /app/logs/*.log
docker cp "${BACKEND_CONTAINER}:/app/logs" "${WORK}/backend-file-logs" 2>/dev/null \
  || echo "[collect] WARN: no /app/logs in ${BACKEND_CONTAINER}"

# 3) Speaker 服务与 Bot 服务（systemd journal）
journalctl -u si-speaker --since "${SINCE}" --no-pager > "${WORK}/speaker.log" 2>&1 \
  || echo "[collect] WARN: journalctl si-speaker failed"
journalctl -u si-bot --since "${SINCE}" --no-pager > "${WORK}/bot.log" 2>&1 \
  || echo "[collect] WARN: journalctl si-bot failed"

# 4) Nginx 错误日志
tail -n 2000 /var/log/nginx/error.log > "${WORK}/nginx-error.log" 2>/dev/null \
  || echo "[collect] WARN: nginx error.log unavailable"

# 5) 运行状态快照
{
  echo "## time: $(date -Is)"
  echo "## since: ${SINCE}"
  echo
  echo "## backend /api/health"
  curl -sf http://127.0.0.1:8080/api/health || echo "(unreachable)"
  echo
  echo "## speaker /health"
  curl -sf http://127.0.0.1:7000/health || echo "(unreachable)"
  echo
  echo "## SI_LOG_LEVEL (backend)"
  docker exec "${BACKEND_CONTAINER}" printenv SI_LOG_LEVEL 2>/dev/null || echo "(unset -> INFO)"
  echo "## VOICE_GENDER_TIMEOUT_MS (backend)"
  docker exec "${BACKEND_CONTAINER}" printenv VOICE_GENDER_TIMEOUT_MS 2>/dev/null || echo "(unset)"
  echo "## speaker LOG_LEVEL"
  systemctl show si-speaker -p Environment --no-pager 2>/dev/null || echo "(unknown)"
  echo "## backend image/status"
  docker ps --filter "name=${BACKEND_CONTAINER}" --format '{{.Image}} {{.Status}}'
} > "${WORK}/snapshot.txt" 2>&1 || true

# 6) 语音性别问题聚焦提取（后端 + speaker 全部来源合并）
grep -hE 'VoiceGenderIntegration|SpeakerVoiceGenderService|detect end|detect start|resolveVoiceIdByGender|gender voiceId|schedule (queued|skipped)|timeout|\[voice-gender\]|reason=|accepted=' \
  "${WORK}/backend-docker.log" "${WORK}/speaker.log" \
  "${WORK}"/backend-file-logs/*.log 2>/dev/null \
  > "${WORK}/voice-gender-focused.log" \
  || echo "[collect] (no voice-gender lines matched yet — run a real session first)"

ARCHIVE="/tmp/synclingo-diag-${STAMP}.tar.gz"
tar -czf "${ARCHIVE}" -C /tmp "synclingo-diag-${STAMP}"
rm -rf "${WORK}"

echo
echo "[collect] DONE -> ${ARCHIVE}"
ls -lh "${ARCHIVE}"
echo "[collect] 下载到本地后发我即可，例如："
echo "  scp root@<server>:${ARCHIVE} ."
