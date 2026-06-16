param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AdminSecret = $env:ADMIN_API_SECRET,
    [string]$RequestFile = "docs/examples/qa-evaluation-run-request.example.json",
    [string]$OutputDir = "outputs/qa-evaluation",
    [ValidateSet("run", "score")]
    [string]$Mode = "run",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    $repoRoot = Split-Path -Parent $PSScriptRoot
    return Join-Path $repoRoot $PathValue
}

function Require-Value {
    param(
        [string]$Value,
        [string]$Name
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name is required. Pass -$Name or set ADMIN_API_SECRET."
    }
}

function Get-Evaluation {
    param(
        [object]$ResponseData,
        [string]$ModeValue
    )
    if ($ModeValue -eq "run") {
        return $ResponseData.evaluation
    }
    return $ResponseData
}

Require-Value -Value $AdminSecret -Name "AdminSecret"

$requestPath = Resolve-RepoPath -PathValue $RequestFile
if (-not (Test-Path -LiteralPath $requestPath)) {
    throw "Request file does not exist: $requestPath"
}

$requestJson = Get-Content -LiteralPath $requestPath -Raw -Encoding UTF8
$null = $requestJson | ConvertFrom-Json

$endpoint = "$($BaseUrl.TrimEnd('/'))/api/admin/qa-evaluation/$Mode"
if ($DryRun) {
    Write-Host "Dry run OK"
    Write-Host "Endpoint: $endpoint"
    Write-Host "Request:  $requestPath"
    return
}

$headers = @{
    "X-Admin-Secret" = $AdminSecret
}

$startedAt = Get-Date
$response = Invoke-RestMethod `
    -Method Post `
    -Uri $endpoint `
    -Headers $headers `
    -ContentType "application/json; charset=utf-8" `
    -Body $requestJson `
    -TimeoutSec 300

if ($response.code -ne 200) {
    $message = $response.message
    throw "Q&A evaluation request failed, code=$($response.code), message=$message"
}

$outputPath = Resolve-RepoPath -PathValue $OutputDir
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jsonPath = Join-Path $outputPath "qa-evaluation-$Mode-$timestamp.json"
$summaryPath = Join-Path $outputPath "qa-evaluation-$Mode-$timestamp.md"

$response | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$evaluation = Get-Evaluation -ResponseData $response.data -ModeValue $Mode
$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("# Q&A Evaluation Report")
$summary.Add("")
$summary.Add("- Mode: $Mode")
$summary.Add("- Endpoint: $endpoint")
$summary.Add("- Started at: $($startedAt.ToString('yyyy-MM-dd HH:mm:ss'))")
$summary.Add("- Finished at: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))")
$summary.Add("- Total: $($evaluation.total)")
$summary.Add("- Passed: $($evaluation.passed)")
$summary.Add("- Failed: $($evaluation.failed)")
$summary.Add("- Min answer coverage: $($evaluation.minAnswerCoverage)")
$summary.Add("- Min source coverage: $($evaluation.minSourceCoverage)")
$summary.Add("")
$summary.Add("## Case Results")

foreach ($item in $evaluation.results) {
    $status = if ($item.passed) { "PASS" } else { "FAIL" }
    $summary.Add("")
    $summary.Add("### $($item.id) - $status")
    $summary.Add("")
    $summary.Add("- Answer coverage: $($item.answerCoverage) ($($item.answerPointHits)/$($item.expectedAnswerPoints))")
    $summary.Add("- Source coverage: $($item.sourceCoverage) ($($item.sourceKeywordHits)/$($item.expectedSourceKeywords))")
}

if ($Mode -eq "run" -and $response.data.generatedAnswers) {
    $summary.Add("")
    $summary.Add("## Generated Answers")
    foreach ($answer in $response.data.generatedAnswers) {
        $summary.Add("")
        $summary.Add("### $($answer.id)")
        $summary.Add("")
        $summary.Add("- Question: $($answer.question)")
        $summary.Add("- Response type: $($answer.responseType)")
        $summary.Add("- User matched: $($answer.userMatched)")
        $summary.Add("- Source count: $($answer.sourceCount)")
        $summary.Add("")
        $summary.Add($answer.replyText)
    }
}

$summary | Set-Content -LiteralPath $summaryPath -Encoding UTF8

Write-Host "Q&A evaluation completed"
Write-Host "JSON:    $jsonPath"
Write-Host "Summary: $summaryPath"
