param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [string]$RootDir = ''
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RootDir)) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $RootDir = (Resolve-Path (Join-Path $scriptDir '..')).Path
}

function Resolve-ConfigValue {
    param([string]$Value)

    if ($null -eq $Value) { return '' }

    $v = $Value.Trim()
    if (($v.StartsWith('"') -and $v.EndsWith('"')) -or ($v.StartsWith("'") -and $v.EndsWith("'"))) {
        $v = $v.Substring(1, $v.Length - 2)
    }

    if ($v -match '^\$\{([^:}]+):(.*)\}$') {
        $envValue = [Environment]::GetEnvironmentVariable($Matches[1])
        if (-not [string]::IsNullOrWhiteSpace($envValue)) {
            return $envValue
        }
        return $Matches[2]
    }

    return $v
}

function Read-SimpleYaml {
    param([string]$Path)

    $result = @{}
    if (-not (Test-Path $Path)) { return $result }

    $keys = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*$' -or $line -match '^\s*#') { continue }
        if ($line -notmatch '^(\s*)([^:#]+):\s*(.*)$') { continue }

        $indent = $Matches[1].Length
        [int]$level = [Math]::Floor($indent / 2)
        $key = $Matches[2].Trim()
        $rawValue = $Matches[3].Trim()

        $keys[$level] = $key
        foreach ($k in @($keys.Keys)) {
            if ($k -gt $level) { $keys.Remove($k) }
        }

        if ($rawValue -eq '') { continue }

        $pathParts = for ($i = 0; $i -le $level; $i++) {
            if ($keys.ContainsKey($i)) { $keys[$i] }
        }
        $result[($pathParts -join '.')] = Resolve-ConfigValue $rawValue
    }

    return $result
}

function Read-DotEnv {
    param([string]$Path)

    $result = @{}
    if (-not (Test-Path $Path)) { return $result }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*$' -or $line -match '^\s*#') { continue }
        if ($line -notmatch '^\s*([^=]+?)\s*=\s*(.*)\s*$') { continue }

        $key = $Matches[1].Trim()
        $value = $Matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if (-not [string]::IsNullOrWhiteSpace($key) -and -not [string]::IsNullOrWhiteSpace($value)) {
            $result[$key] = $value
        }
    }

    return $result
}

$config = @{}
$configFiles = @(
    (Join-Path $RootDir 'si-backend/src/main/resources/application.yml'),
    (Join-Path $RootDir 'si-backend/src/main/resources/application-prod.yml'),
    (Join-Path $RootDir 'si-backend/src/main/resources/application-dev.yml')
)

foreach ($file in $configFiles) {
    $values = Read-SimpleYaml $file
    foreach ($key in $values.Keys) {
        if (-not [string]::IsNullOrWhiteSpace($values[$key]) -and $values[$key] -notlike 'YOUR_*' -and $values[$key] -notlike 'your_*') {
            $config[$key] = $values[$key]
        }
    }
}

$envOverrides = @{}
$envFiles = @(
    (Join-Path $RootDir '.env'),
    (Join-Path $RootDir 'si-backend/.env')
)

foreach ($file in $envFiles) {
    $values = Read-DotEnv $file
    foreach ($key in $values.Keys) {
        if (-not [string]::IsNullOrWhiteSpace($values[$key]) -and $values[$key] -notlike 'YOUR_*' -and $values[$key] -notlike 'your_*') {
            $envOverrides[$key] = $values[$key]
        }
    }
}

function Get-Cfg {
    param(
        [string]$Path,
        [string]$Default = ''
    )

    if ($config.ContainsKey($Path) -and -not [string]::IsNullOrWhiteSpace($config[$Path])) {
        return $config[$Path]
    }
    return $Default
}

$envValues = [ordered]@{
    SPRING_PROFILES_ACTIVE          = 'prod'
    DB_HOST                         = 'host.docker.internal'
    DB_PORT                         = '3306'
    DB_NAME                         = 'si_backend'
    DB_USERNAME                     = Get-Cfg 'spring.datasource.username' 'root'
    DB_PASSWORD                     = Get-Cfg 'spring.datasource.password'
    AZURE_SPEECH_KEY                = Get-Cfg 'azure.speech.key'
    AZURE_SPEECH_REGION             = Get-Cfg 'azure.speech.region' 'southeastasia'
    AZURE_ASR_LANGUAGES             = Get-Cfg 'azure.speech.asr.language' 'zh-CN,id-ID,en-US'
    AZURE_ASR_END_SILENCE_TIMEOUT_MS = Get-Cfg 'azure.speech.asr.end-silence-timeout-ms' '1200'
    AZURE_ASR_SEGMENTATION_SILENCE_TIMEOUT_MS = Get-Cfg 'azure.speech.asr.segmentation-silence-timeout-ms' '1000'
    AZURE_ASR_SEGMENTATION_STRATEGY = Get-Cfg 'azure.speech.asr.segmentation-strategy'
    AZURE_ASR_SEGMENTATION_MAXIMUM_TIME_MS = Get-Cfg 'azure.speech.asr.segmentation-maximum-time-ms' '0'
    AZURE_ASR_SENTENCE_SEGMENTATION_ENABLED = Get-Cfg 'azure.speech.asr.sentence-segmentation-enabled' 'true'
    AZURE_ASR_MAX_SEGMENT_ZH_CHARS  = Get-Cfg 'azure.speech.asr.max-segment-zh-chars' '50'
    AZURE_ASR_MAX_SEGMENT_WORDS     = Get-Cfg 'azure.speech.asr.max-segment-words' '50'
    AZURE_ASR_MAX_SEGMENT_CHARS     = Get-Cfg 'azure.speech.asr.max-segment-chars' '80'
    AZURE_TRANSLATOR_KEY            = Get-Cfg 'azure.translator.key'
    AZURE_TRANSLATOR_REGION         = Get-Cfg 'azure.translator.region'
    GOOGLE_TRANSLATE_API_KEY        = Get-Cfg 'google.translate.api-key'
    OPENAI_API_KEY                  = Get-Cfg 'openai.api-key'
    OPENAI_BASE_URL                 = Get-Cfg 'openai.base-url' 'https://api.openai.com/v1'
    OPENAI_REFERER                  = Get-Cfg 'openai.referer'
    OPENAI_TITLE                    = Get-Cfg 'openai.title' 'syncLingo-plus'
    OPENAI_COMPRESSION_ENABLED      = Get-Cfg 'openai.compression-enabled' 'true'
    OPENAI_COMPRESSION_MIN_TEXT_LENGTH = Get-Cfg 'openai.compression-min-text-length' '80'
    OPENAI_COMPRESSION_MODEL        = Get-Cfg 'openai.compression-model' 'gpt-5-nano'
    OPENAI_SUMMARY_MODEL            = Get-Cfg 'openai.summary-model' 'gpt-5-mini'
    OPENAI_DOCUMENT_SUMMARY_MODEL   = Get-Cfg 'openai.document-summary-model' 'gpt-5'
    OPENAI_COMPRESSION_MAX_OUTPUT_TOKENS = Get-Cfg 'openai.compression-max-output-tokens' '512'
    OPENAI_SUMMARY_MAX_OUTPUT_TOKENS = Get-Cfg 'openai.summary-max-output-tokens' '1200'
    OPENAI_DOCUMENT_SUMMARY_MAX_OUTPUT_TOKENS = Get-Cfg 'openai.document-summary-max-output-tokens' '4000'
    CARTESIA_API_KEY                = Get-Cfg 'cartesia.api-key'
    CARTESIA_API_URL                = Get-Cfg 'cartesia.api-url'
    CARTESIA_DEFAULT_VOICE_ID_ZH    = Get-Cfg 'cartesia.default-voice-id-chinese'
    CARTESIA_DEFAULT_VOICE_ID_ID    = Get-Cfg 'cartesia.default-voice-id-indonesian'
    CARTESIA_DEFAULT_VOICE_ID_EN    = Get-Cfg 'cartesia.default-voice-id-english' 'default'
    JWT_SECRET                      = Get-Cfg 'jwt.secret'
    CORS_ALLOWED_ORIGINS            = Get-Cfg 'app.cors.allowed-origins'
    BOT_API_URL                     = Get-Cfg 'bot.api.url' 'http://host.docker.internal:3978'
}

foreach ($key in @($envOverrides.Keys)) {
    $envValues[$key] = $envOverrides[$key]
}

$required = @(
    'DB_PASSWORD',
    'AZURE_SPEECH_KEY',
    'GOOGLE_TRANSLATE_API_KEY',
    'OPENAI_API_KEY',
    'CARTESIA_API_KEY'
)

$missing = @()
foreach ($name in $required) {
    $value = $envValues[$name]
    if ([string]::IsNullOrWhiteSpace($value) -or $value -like 'YOUR_*' -or $value -like 'your_*') {
        $missing += $name
    }
}

if ($missing.Count -gt 0) {
    throw "Missing required backend config values: $($missing -join ', ')"
}

$lines = foreach ($entry in $envValues.GetEnumerator()) {
    if (-not [string]::IsNullOrWhiteSpace($entry.Value) -and $entry.Value -notlike 'YOUR_*' -and $entry.Value -notlike 'your_*') {
        "$($entry.Key)=$($entry.Value)"
    }
}

Set-Content -LiteralPath $OutputPath -Value $lines -Encoding ASCII
