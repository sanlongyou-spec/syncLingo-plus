"""
syncLingo-plus API 自动化测试
覆盖：登录回归、Bot 指令路由、SSE 端点、会议管理 CRUD、文件持久化、发言人摘要持久化

用法：
    pip install requests
    python tests/api_test.py [--base-url http://host:8080] [--user-id 1]
"""
import argparse
import io
import sys
import time
import zipfile

try:
    import requests
except ImportError:
    print("pip install requests first")
    sys.exit(1)

# Force UTF-8 output on Windows GBK terminals
if sys.stdout.encoding and sys.stdout.encoding.lower() != 'utf-8':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

def make_minimal_docx(text: str) -> bytes:
    """Build a minimal valid .docx (OOXML ZIP) containing the given text."""
    CT = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    content_types = (CT +
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '</Types>')
    rels = (CT +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
        '</Relationships>')
    doc_rels = (CT +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>')
    document = (CT +
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:body><w:p><w:r><w:t>' + text + '</w:t></w:r></w:p></w:body></w:document>')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', content_types)
        z.writestr('_rels/.rels', rels)
        z.writestr('word/_rels/document.xml.rels', doc_rels)
        z.writestr('word/document.xml', document)
    return buf.getvalue()


parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://localhost:8080")
parser.add_argument("--user-id", type=int, default=1)
args = parser.parse_args()

BASE = args.base_url
UID  = args.user_id

passed = failed = 0


def ok(name):
    global passed
    passed += 1
    print(f"  ✅ PASS  {name}")


def fail(name, reason=""):
    global failed
    failed += 1
    print(f"  ❌ FAIL  {name}" + (f" — {reason}" if reason else ""))


def check(name, cond, reason=""):
    if cond:
        ok(name)
    else:
        fail(name, reason)


# ── 1. 登录（回归）─────────────────────────────────────────────────────────
print("\n[1] 回归：登录")
r = requests.post(f"{BASE}/api/auth/login", json={"username": "admin", "password": "admin123"})
check("登录成功 code=200", r.status_code == 200)
check("响应包含 code 字段", "code" in r.json())

r2 = requests.post(f"{BASE}/api/auth/login", json={"username": "admin", "password": "wrong"})
check("密码错误 code≠200", r2.status_code != 200 or r2.json().get("code") != 200)

# ── 2. Bot 指令路由（回归）──────────────────────────────────────────────────
print("\n[2] Bot 指令路由")
for cmd in ["help", "list", "search abc", "summary"]:
    r = requests.post(f"{BASE}/api/teams-bot/query", json={"userId": UID, "message": cmd})
    check(f"指令 '{cmd}' → HTTP 200", r.status_code == 200)

# ── 3. SSE 端点基础验证 ──────────────────────────────────────────────────────
print("\n[3] SSE 流式端点")
r = requests.post(f"{BASE}/api/teams-bot/query/stream",
                  json={"userId": UID, "message": ""},
                  stream=True)
check("空消息 → 400", r.status_code == 400)

r = requests.post(f"{BASE}/api/teams-bot/query/stream",
                  json={"userId": 999999, "message": "这个月会议的主要议题是什么？"},
                  stream=True, timeout=15)
check("未绑定用户 → HTTP 200", r.status_code == 200)
if r.status_code == 200:
    lines = []
    for line in r.iter_lines(decode_unicode=True):
        if line:
            lines.append(line)
        if len(lines) >= 3:
            break
    check("SSE 返回 data: 行", any(l.startswith("data:") for l in lines))

# ── 4. 会议 CRUD ────────────────────────────────────────────────────────────
print("\n[4] 会议 CRUD")
r = requests.post(f"{BASE}/api/meetings", json={
    "userId": UID,
    "title": "自动化测试会议",
    "scheduledTime": "2026-06-01 10:00",
    "note": "api_test.py 创建"
})
check("POST /api/meetings → 200", r.status_code == 200)
meeting_id = None
if r.status_code == 200:
    data = r.json().get("data", {})
    meeting_id = data.get("id")
    check("响应包含 id", meeting_id is not None)
    check("标题匹配", data.get("title") == "自动化测试会议")
else:
    fail("无法获取 meeting_id，跳过后续会议测试", r.text[:200])

if meeting_id:
    r = requests.get(f"{BASE}/api/meetings", params={"userId": UID})
    check("GET /api/meetings 返回列表", r.status_code == 200)
    items = r.json().get("data", [])
    check("列表包含新建会议", any(m.get("id") == meeting_id for m in items))

    r = requests.get(f"{BASE}/api/meetings/{meeting_id}")
    check("GET /api/meetings/{id} 返回详情", r.status_code == 200)
    check("详情 id 匹配", r.json().get("data", {}).get("id") == meeting_id)

# ── 5. 文件上传与管理 ────────────────────────────────────────────────────────
print("\n[5] 文件上传与管理")
file_id = None
if meeting_id:
    docx_content = make_minimal_docx("This is a test agenda file for api_test.")
    r = requests.post(
        f"{BASE}/api/meetings/{meeting_id}/files",
        files={"file": ("test_agenda.docx", io.BytesIO(docx_content),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")}
    )
    check("POST /api/meetings/{id}/files → 上传成功", r.status_code == 200)
    if r.status_code == 200:
        file_id = r.json().get("data", {}).get("id")
        check("响应包含文件 id", file_id is not None)
        check("文件名匹配", r.json().get("data", {}).get("fileName") == "test_agenda.docx")

    r = requests.get(f"{BASE}/api/meetings/{meeting_id}/files")
    check("GET /api/meetings/{id}/files 返回列表", r.status_code == 200)
    files_list = r.json().get("data", [])
    check("文件列表包含上传的文件", any(f.get("id") == file_id for f in files_list) if file_id else False)
    check("文件名为 test_agenda.docx", any(f.get("fileName") == "test_agenda.docx" for f in files_list))

    if file_id:
        r = requests.delete(f"{BASE}/api/meetings/{meeting_id}/files/{file_id}")
        check("DELETE /api/meetings/{id}/files/{fid} → 删除成功", r.status_code == 200)

        r = requests.get(f"{BASE}/api/meetings/{meeting_id}/files")
        files_after = r.json().get("data", [])
        check("删除后文件列表不包含该文件", all(f.get("id") != file_id for f in files_after))
    else:
        fail("file_id 为空，跳过删除测试")
else:
    fail("meeting_id 为空，跳过文件测试")

# ── 6. 发言人摘要生成 ────────────────────────────────────────────────────────
print("\n[6] 发言人摘要")
TEST_SESSION = f"api_test_session_{int(time.time())}"

r = requests.post(f"{BASE}/api/meetings/speaker-summary", json={
    "userId": UID,
    "sessionId": TEST_SESSION,
    "speakerId": "speaker_A",
    "speakerName": "张三",
    "text": (
        "我们今年第一季度的销售额达到了1.2亿元，同比增长15%。"
        "主要增长来自东南亚市场，尤其是印尼和越南。"
        "建议下季度加大在这两个市场的投入，同时保持国内市场的稳定增长。"
    )
})
check("POST /api/meetings/speaker-summary → 200", r.status_code == 200)
if r.status_code == 200:
    data = r.json().get("data", {})
    check("响应包含 summary 字段", bool(data.get("summary")))
    check("speakerName 匹配", data.get("speakerName") == "张三")
    check("摘要长度 > 10 字", len(data.get("summary", "")) > 10)
    check("响应包含 title 字段（主题标题）", bool(data.get("title")))

# ── 7. 摘要持久化查询 ────────────────────────────────────────────────────────
print("\n[7] 摘要持久化")
r = requests.get(f"{BASE}/api/meetings/speaker-summaries/{TEST_SESSION}")
check("GET /api/meetings/speaker-summaries/{sid} → 200", r.status_code == 200)
records = r.json().get("data", [])
check("数据库包含刚生成的摘要记录", len(records) >= 1)
if records:
    rec = records[0]
    check("记录 sessionId 匹配", rec.get("sessionId") == TEST_SESSION)
    check("记录 speakerName 匹配", rec.get("speakerName") == "张三")
    check("记录 summary 非空", bool(rec.get("summary")))
    check("记录 textSnippet 非空", bool(rec.get("textSnippet")))
    check("记录 title 字段已持久化", bool(rec.get("title")))

# ── 8. 参数校验 ──────────────────────────────────────────────────────────────
print("\n[8] 参数校验（@Valid）")
r = requests.post(f"{BASE}/api/meetings", json={"userId": UID})
check("创建会议缺 title → 400", r.status_code == 400)

r = requests.post(f"{BASE}/api/meetings/speaker-summary", json={
    "userId": UID,
    "sessionId": TEST_SESSION,
    # 缺 text
})
check("摘要请求缺 text → 400", r.status_code == 400)

# ── 汇总 ──────────────────────────────────────────────────────────────────────
print(f"\n{'='*50}")
total = passed + failed
print(f"结果：{passed}/{total} 通过")
if failed:
    print("部分测试失败，请检查后端日志。")
    sys.exit(1)
else:
    print("所有测试通过 ✅")
