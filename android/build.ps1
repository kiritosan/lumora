# Lumora 本机构建兜底脚本（可选）。
# 主路径是 CNB 云端构建（见 android/README.md）；本脚本仅在已装好
# Android SDK 34 + build-tools 34.0.0 的机器上可用，默认走 --offline。
# 用法：powershell -ExecutionPolicy Bypass -File android\build.ps1 [-Task assembleDebug]
param(
    [string]$Task = "assembleDebug"
)
$ErrorActionPreference = 'Stop'
$androidDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $androidDir

# --- JDK 17：先修正常见的 JAVA_HOME 误配（指向 ...\bin） ---
$jh = $env:JAVA_HOME
$javaHome = $null
if ($jh -and (Test-Path (Join-Path $jh 'bin\java.exe'))) {
    $javaHome = $jh
} elseif ($jh -and (Test-Path (Join-Path $jh 'java.exe'))) {
    $javaHome = Split-Path -Parent $jh   # JAVA_HOME 误指到 bin 目录
} else {
    foreach ($cand in @("$env:USERPROFILE\.jdks\azul-17.0.9",
                       "$env:USERPROFILE\.jdks\azul-17.0.9\bin")) {
        $check = if ($cand.EndsWith('\bin')) { Split-Path -Parent $cand } else { $cand }
        if (Test-Path (Join-Path $check 'bin\java.exe')) { $javaHome = $check; break }
    }
}
if (-not $javaHome) {
    throw "未找到 JDK 17。请安装 JDK 17+ 或将 JAVA_HOME 指向其根目录。"
}
$env:JAVA_HOME = $javaHome
Write-Host "JAVA_HOME=$javaHome"

# --- SDK：local.properties 或 ANDROID_HOME ---
$sdkDir = $null
$lp = Join-Path $androidDir 'local.properties'
if (Test-Path $lp) {
    foreach ($line in Get-Content $lp) {
        if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') {
            # .properties 转义：\\\\ → \，\: → :
            $sdkDir = $Matches[1]
            $sdkDir = $sdkDir -replace '\\\\', '\'
            $sdkDir = $sdkDir -replace '\\:', ':'
            $sdkDir = $sdkDir.Trim()
        }
    }
}
if (-not $sdkDir -and $env:ANDROID_HOME) { $sdkDir = $env:ANDROID_HOME }
if (-not $sdkDir -and $env:ANDROID_SDK_ROOT) { $sdkDir = $env:ANDROID_SDK_ROOT }
if (-not $sdkDir -or -not (Test-Path (Join-Path $sdkDir 'platforms\android-34\android.jar'))) {
    throw "未找到含 android-34 平台的 SDK。本机不需要构建时可直接用 CNB（见 README）；`n需要本机构建请安装 SDK 并在 android\local.properties 写 sdk.dir=<路径>。"
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $sdkDir }
Write-Host "SDK=$sdkDir"

# --- Gradle 8.6：优先 wrapper 缓存中的已展开发行包 ---
$gradle = $null
$dist = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.6-bin\*\gradle-8.6" `
    -ErrorAction SilentlyContinue | Select-Object -First 1
if ($dist) {
    $gradle = Join-Path $dist.FullName 'bin\gradle.bat'
} else {
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) { $gradle = $cmd.Source }
}
if (-not $gradle) {
    $wrapper = Join-Path $androidDir 'gradlew.bat'
    if (Test-Path $wrapper) { $gradle = $wrapper }
    else { throw "找不到 Gradle 8.6（缓存发行包/PATH/gradlew 均不可用）" }
}
Write-Host "Gradle=$gradle"

& $gradle -p $androidDir --offline --console=plain $Task
exit $LASTEXITCODE
