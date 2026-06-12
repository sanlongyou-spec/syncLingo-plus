#!/usr/bin/env bash
# 下载 sherpa-onnx 标点模型并验证 speaker-service /punctuate 端点可用
# 用法（在服务器 speaker-service/ 目录下运行）：
#   bash setup_punct_model.sh
set -e
cd "$(dirname "$0")"

MODEL_DIR="models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12"
MODEL_FILE="$MODEL_DIR/model.onnx"

echo "=== 标点模型安装 ==="

if [ -f "$MODEL_FILE" ]; then
    echo "[ok] 模型已存在: $MODEL_FILE ($(du -sh "$MODEL_FILE" | cut -f1))"
else
    echo "[info] 正在下载 sherpa-onnx CT-Transformer 标点模型..."
    mkdir -p "$MODEL_DIR"
    BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models"
    TARBALL="sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12.tar.bz2"

    # 下载
    curl -L --progress-bar \
        "$BASE_URL/$TARBALL" \
        -o "/tmp/$TARBALL"

    # 解压（只取 model.onnx）
    tar -xjf "/tmp/$TARBALL" -C "models/" \
        "$MODEL_DIR/model.onnx" \
        2>/dev/null || tar -xjf "/tmp/$TARBALL" -C "models/"

    rm -f "/tmp/$TARBALL"
    echo "[ok] 下载完成: $MODEL_FILE ($(du -sh "$MODEL_FILE" | cut -f1))"
fi

echo ""
echo "=== 启动参数确认 ==="
echo "PUNCT_MODEL_PATH 应设置为: $MODEL_FILE"
echo ""
echo "重启 speaker-service 使模型生效（根据你的启动方式选择）："
echo "  方式 A — systemd:  sudo systemctl restart speaker-service"
echo "  方式 B — screen:   先 kill 旧进程, 再: PUNCT_MODEL_PATH=$MODEL_FILE bash start.sh"
echo "  方式 C — 直接:     PUNCT_MODEL_PATH=$MODEL_FILE uvicorn main:app --host 0.0.0.0 --port 7000 &"
echo ""
echo "=== 验证健康状态（等服务启动后运行）==="
echo "  curl -s http://localhost:7000/health | python3 -m json.tool"
echo "  → 预期: punct_model_loaded: true"
echo ""
echo "=== 快速功能测试 ==="
TEST_TEXT="今天会议讨论一下我们项目的进展情况和预算安排"
echo "  测试文本: $TEST_TEXT"
echo ""
echo "运行:"
echo "  curl -s http://localhost:7000/punctuate \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"text\":\"$TEST_TEXT\"}' | python3 -m json.tool"
echo ""
echo "预期输出中 punctuated 应含句末/子句标点，model_available: true"
