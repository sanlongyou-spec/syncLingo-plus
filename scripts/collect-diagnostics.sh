#!/usr/bin/env bash
# 一次性收集 syncLingo 全部服务的最细粒度日志，打包成单个 tar.gz 便于下载分享。
#
# 推荐用法（干净不掺旧日志）：
#   测试前打基线： date +%s > /tmp/synclingo-test-baseline
#   测试后收集：   bash /opt/syncLingo/scripts/collect-diagnostics.sh
#   脚本会自动只截取基线之后的日志。
#
# 也可手动指定回溯分钟数（无基线文件时）：
#   bash /opt/syncLingo/scripts/collect-diagnostics.sh 20    # 最近 20 分钟
#
# 输出：/tmp/synclingo-diag-<时间戳>.tar.gz
# 注意：只收集日志，不包含 backend.env 等密钥文件。
set -euo pipefail

BASELINE_FILE="/tmp/synclingo-test-baseline"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-si-backend}"

# 确定起始时间（epoch 秒）：优先用基线文件，否则用回溯分钟数（默认 30）。
if [[ -f "${BASELINE_FILE}" ]]; then
  START_EPOCH="$(cat "${BASELINE_FILE}")"
  START_DESC="基线 $(date -d "@${START_EPOCH}" '+%F %T')"
else
  MINUTES="${1:-30}"
  START_EPOCH="$(date -d "-${MINUTES} minutes" +%s)"
  START_DESC="最近 ${MINUTES} 分钟"
fi

STAMP="$(date +%Y%m%d_%H%M%S)"
WORK="/tmp/synclingo-diag-${STAMP}"
mkdir -p "${WORK}"

echo "[collect] 起始=${START_DESC} (epoch=${START_EPOCH}) container=${BACKEND_CONTAINER} -> ${WORK}"

# 1) 后端容器 stdout/stderr（docker 用 epoch 秒；含 SI_LOG_LEVEL=DEBUG 时的最细粒度日志）
docker logs --since "${START_EPOCH}" "${BACKEND_CONTAINER}" > "${WORK}/backend.log" 2>&1 \
  || echo "[collect] WARN: docker logs ${BACKEND_CONTAINER} failed"

# 2) Speaker 与 Bot（systemd journal 用 @epoch 格式）
journalctl -u si-speaker --since "@${START_EPOCH}" --no-pager > "${WORK}/speaker.log" 2>&1 \
  || echo "[collect] WARN: journalctl si-speaker failed"
journalctl -u si-bot --since "@${START_EPOCH}" --no-pager > "${WORK}/bot.log" 2>&1 \
  || echo "[collect] WARN: journalctl si-bot failed"

# 3) Nginx 错误日志（尾部）
tail -n 1000 /var/log/nginx/error.log > "${WORK}/nginx-error.log" 2>/dev/null \
  || echo "[collect] WARN: nginx error.log unavailable"

# 4) 运行状态快照
{
  echo "## time: $(date -Is)"
  echo "## window-start: $(date -d "@${START_EPOCH}" -Is)"
  echo
  echo "## backend /api/health"; curl -sf http://127.0.0.1:8080/api/health || echo "(unreachable)"; echo
  echo "## speaker /health"; curl -sf http://127.0.0.1:7000/health || echo "(unreachable)"; echo
  echo "## SI_LOG_LEVEL (backend)"; docker exec "${BACKEND_CONTAINER}" printenv SI_LOG_LEVEL 2>/dev/null || echo "(unset -> INFO)"
  echo "## VOICE_GENDER_TIMEOUT_MS (backend)"; docker exec "${BACKEND_CONTAINER}" printenv VOICE_GENDER_TIMEOUT_MS 2>/dev/null || echo "(unset)"
  echo "## speaker Environment"; systemctl show si-speaker -p Environment --no-pager 2>/dev/null || echo "(unknown)"
  echo "## backend image/status"; docker ps --filter "name=${BACKEND_CONTAINER}" --format '{{.Image}} {{.Status}}'
  echo "## git commit"; git -C /opt/syncLingo rev-parse HEAD 2>/dev/null || echo "(n/a)"
} > "${WORK}/snapshot.txt" 2>&1 || true

# 5) 语音性别问题聚焦提取（仅从已按时间截取的来源里提，保持干净）
grep -hE 'VoiceGenderIntegration|SpeakerVoiceGenderService|detect end|detect start|resolveVoiceIdByGender|gender voiceId|schedule (queued|skipped)|timeout|\[voice-gender\]|reason=|accepted=' \
  "${WORK}/backend.log" "${WORK}/speaker.log" 2>/dev/null \
  > "${WORK}/voice-gender-focused.log" \
  || echo "[collect] (未匹配到语音性别日志 — 确认已真人跑过会话)"

ARCHIVE="/tmp/synclingo-diag-${STAMP}.tar.gz"
tar -czf "${ARCHIVE}" -C /tmp "synclingo-diag-${STAMP}"
rm -rf "${WORK}"

echo
echo "[collect] 完成 -> ${ARCHIVE}"
ls -lh "${ARCHIVE}"
echo "[collect] 下载到本地后发我：  scp root@<server>:${ARCHIVE} ."
