#!/usr/bin/env bash
# Lumora 原生 Android CNB 构建脚本。
# 环境：mobiledevops/android-sdk-image:34.0.1（自带 SDK 34 + JDK）。
# 产物：android/artifacts/（APK + lint 报告 + SHA256SUMS.txt），不含任何密钥。
set -euo pipefail

cd "$(dirname "$0")/../.."

echo "== 环境检查 =="
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -f "$SDK/platforms/android-34/android.jar" ]; then
  echo "缺少 Android SDK 34 平台（platforms/android-34/android.jar）。" >&2
  echo "期望镜像 mobiledevops/android-sdk-image:34.0.1 提供 SDK；" >&2
  echo "若换镜像请保证 ANDROID_HOME/ANDROID_SDK_ROOT 指向含 android-34 的 SDK。" >&2
  exit 1
fi
APKSIGNER="$SDK/build-tools/34.0.0/apksigner"
if [ ! -x "$APKSIGNER" ]; then
  echo "缺少 build-tools/34.0.0/apksigner：$SDK" >&2
  exit 1
fi
echo "SDK=$SDK"

echo "== JDK 检查（Gradle 8.6 / AGP 8.3.2 需要 JDK 17+）=="
if command -v java >/dev/null 2>&1; then
  java -version 2>&1 | head -n 2
else
  echo "PATH 中没有 java" >&2
  exit 1
fi

GRADLE_HOME=/root/gradle-dist/gradle-8.6
GRADLE="$GRADLE_HOME/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  echo "== 下载 Gradle 8.6（含官方 sha256 校验）=="
  mkdir -p /root/gradle-dist
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --retry 3 --connect-timeout 30 --max-time 600 \
    -o "$tmp/gradle-8.6-bin.zip" \
    "https://services.gradle.org/distributions/gradle-8.6-bin.zip"
  curl -fL --retry 3 --connect-timeout 30 --max-time 120 \
    -o "$tmp/gradle-8.6-bin.zip.sha256" \
    "https://services.gradle.org/distributions/gradle-8.6-bin.zip.sha256"
  expect="$(cut -d' ' -f1 "$tmp/gradle-8.6-bin.zip.sha256" | tr -d '[:space:]')"
  actual="$(sha256sum "$tmp/gradle-8.6-bin.zip" | cut -d' ' -f1)"
  if [ "$expect" != "$actual" ]; then
    echo "Gradle 发行包 sha256 校验失败：$actual != $expect" >&2
    exit 1
  fi
  unzip -q "$tmp/gradle-8.6-bin.zip" -d /root/gradle-dist
  rm -rf "$tmp"
  trap - EXIT
fi
echo "GRADLE=$GRADLE"

# 附件目录只放本次构建的产物：先清掉我们自己命名的旧输出，
# 保证失败时不会把陈旧 APK 当新包上传。
mkdir -p android/artifacts
rm -f android/artifacts/lumora-debug.apk \
      android/artifacts/lumora-release.apk \
      android/artifacts/lint-results-debug.html \
      android/artifacts/lint-results-release.html \
      android/artifacts/SHA256SUMS.txt

sign_vars=(LUMORA_KEYSTORE_B64 LUMORA_STORE_PASSWORD LUMORA_KEY_ALIAS LUMORA_KEY_PASSWORD)
any_set=0
for v in "${sign_vars[@]}"; do
  if [ -n "${!v:-}" ]; then any_set=1; fi
done

if [ "$any_set" -eq 1 ]; then
  echo "== Release 签名模式 =="
  for v in "${sign_vars[@]}"; do
    if [ -z "${!v:-}" ]; then
      echo "签名变量 $v 缺失：四者必须同时配置（见 android/ci/lumora-secrets.example.yml），" >&2
      echo "不允许部分配置静默降级为 debug 包。" >&2
      exit 1
    fi
  done
  umask 077
  mkdir -p android/keystore
  KEYSTORE_FILE="android/keystore/cnb-release.p12"
  # trap 先于解码建立：解码失败/中途退出也不留半成品密钥
  trap 'rm -f "$KEYSTORE_FILE"' EXIT
  echo "$LUMORA_KEYSTORE_B64" | base64 -d > "$KEYSTORE_FILE"
  if [ ! -s "$KEYSTORE_FILE" ]; then
    echo "密钥库解码为空" >&2
    exit 1
  fi
  tasks=(assembleRelease lintRelease assembleDebugAndroidTest)
  variant="release"
  apk_src="android/app/build/outputs/apk/release/app-release.apk"
  apk_dst="android/artifacts/lumora-release.apk"
  lint_src="android/app/build/reports/lint-results-release.html"
else
  echo "== Debug 构建模式 =="
  echo "未配置 LUMORA_* 签名变量：产出 debug 包。"
  echo "注意：debug 预览密钥随构建机器可变，不作为可升级的生产签名。"
  tasks=(assembleDebug lintDebug assembleDebugAndroidTest)
  variant="debug"
  apk_src="android/app/build/outputs/apk/debug/app-debug.apk"
  apk_dst="android/artifacts/lumora-debug.apk"
  lint_src="android/app/build/reports/lint-results-debug.html"
fi

echo "== Gradle: ${tasks[*]} =="
"$GRADLE" -p android --no-daemon --console=plain "${tasks[@]}"

if [ ! -f "$apk_src" ]; then
  echo "构建未产出 APK：$apk_src" >&2
  exit 1
fi
cp "$apk_src" "$apk_dst"
if [ -f "$lint_src" ]; then
  cp "$lint_src" "android/artifacts/lint-results-${variant}.html"
fi
if [ ! -f "$apk_dst" ]; then
  echo "附件 APK 缺失：$apk_dst（不上传陈旧产物）" >&2
  exit 1
fi

echo "== 校验 APK 签名 =="
"$APKSIGNER" verify --verbose --print-certs "$apk_dst" | sed 's/\(.\{160\}\).*/\1/'

echo "== 生成校验和 =="
( cd android/artifacts && sha256sum "$(basename "$apk_dst")" > SHA256SUMS.txt )

echo "== 完成 =="
ls -la android/artifacts/
