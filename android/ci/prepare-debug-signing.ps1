# 仅需 JDK，无需 Android SDK。产物在被 Git 忽略的 android/keystore/，不会输出密钥正文。
# 首次使用：./android/ci/prepare-debug-signing.ps1
# 找回旧云构建的 debug.keystore 后：加 -SourceKeystore <原密钥库路径>（默认 Android debug 密码）
param(
    [string]$CnbSlug = 'sizuku-2025/lumora',
    [string]$SourceKeystore
)
$ErrorActionPreference = 'Stop'
if ($CnbSlug -notmatch '^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+$') {
    throw 'CnbSlug 必须为 CNB 仓库路径，例如 sizuku-2025/lumora。'
}

$keytool = $null
if ($env:JAVA_HOME) {
    foreach ($candidate in @((Join-Path $env:JAVA_HOME 'bin/keytool.exe'), (Join-Path $env:JAVA_HOME 'keytool.exe'))) {
        if (Test-Path -LiteralPath $candidate) { $keytool = $candidate; break }
    }
}
if (-not $keytool) {
    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) { $keytool = $command.Source }
}
if (-not $keytool) {
    $candidate = Join-Path $env:USERPROFILE '.jdks/azul-17.0.9/bin/keytool.exe'
    if (Test-Path -LiteralPath $candidate) { $keytool = $candidate }
}
if (-not $keytool) { throw '未找到 JDK keytool，请配置 JAVA_HOME。' }

$keyDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'keystore'
$keyFile = Join-Path $keyDir 'lumora-preview.p12'
$secretFile = Join-Path $keyDir 'lumora-debug-secrets.yml'
New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
if ($SourceKeystore -and (Test-Path -LiteralPath $keyFile)) {
    throw '预览密钥已存在，拒绝覆盖。请保留现有密钥，并另行核对旧版证书。'
}
if (-not (Test-Path -LiteralPath $keyFile)) {
    $temporary = Join-Path $keyDir ('preview-' + [guid]::NewGuid().ToString('N') + '.p12')
    try {
        if ($SourceKeystore) {
            if (-not (Test-Path -LiteralPath $SourceKeystore -PathType Leaf)) { throw '原密钥库文件不存在。' }
            & $keytool -importkeystore -noprompt -srckeystore $SourceKeystore `
                -srcstorepass android -srckeypass android -srcalias androiddebugkey `
                -destkeystore $temporary -deststoretype PKCS12 -deststorepass android `
                -destkeypass android -destalias androiddebugkey
        } else {
            & $keytool -genkeypair -noprompt -keystore $temporary -storetype PKCS12 `
                -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 3072 `
                -validity 10000 -dname 'CN=Lumora Preview, O=Lumora, C=CN'
        }
        if ($LASTEXITCODE -ne 0) { throw '预览密钥准备失败，未替换已有文件。' }
        Move-Item -LiteralPath $temporary -Destination $keyFile
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
& $keytool -list -keystore $keyFile -storetype PKCS12 -storepass android -alias androiddebugkey
if ($LASTEXITCODE -ne 0) { throw '预览密钥验证失败。' }

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyFile))
$yaml = @"
# 私密文件：只上传 CNB 机密仓库，绝不提交公开源码仓库。请备份此文件与密钥库。
allow_slugs:
  - '$CnbSlug'
allow_branches:
  - 'main'
  - 'master'
allow_events:
  - 'push'
LUMORA_DEBUG_KEYSTORE_B64: '$base64'
"@
[IO.File]::WriteAllText($secretFile, $yaml, [Text.UTF8Encoding]::new($false))
Write-Host "固定预览密钥：$keyFile"
Write-Host "CNB 机密文件：$secretFile"
Write-Host '将机密文件上传到 CNB 机密仓库，再把 .cnb.yml 的 imports 指向它。重复运行会复用同一密钥。'
