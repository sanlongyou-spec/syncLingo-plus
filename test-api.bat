@echo off
chcp 65001 >nul

echo ===== 测试同声传译后端 API =====
echo.

REM 测试1: 获取音色
echo [1/4] 测试获取音色...
powershell -Command "Invoke-RestMethod -Uri 'http://localhost:8080/api/voice/1' -TimeoutSec 5" | findstr "code"
echo.

REM 测试2: 开始同传会话
echo [2/4] 测试开始同传会话...
powershell -Command "$body = @"
{"userId":1,"sourceLang":"auto","targetLang":"id-ID"}
"@.Replace("`n","").Replace("`r",""); Invoke-RestMethod -Uri 'http://localhost:8080/api/interpretation/start' -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 10"
echo.

REM 测试3: 翻译测试
echo [3/4] 测试翻译...
powershell -Command "$body = @"
{"text":"Hello World","sourceLang":"en","targetLang":"zh-CN"}
"@.Replace("`n","").Replace("`r",""); Invoke-RestMethod -Uri 'http://localhost:8080/api/translate' -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 15"
echo.

REM 测试4: 同传状态
echo [4/4] 测试同传状态查询...
powershell -Command "Invoke-RestMethod -Uri 'http://localhost:8080/api/interpretation/status/test-session' -TimeoutSec 5" | findstr "code"
echo.

echo ===== 测试完成 =====
pause
