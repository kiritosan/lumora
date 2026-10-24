#!/usr/bin/env bash
# Sourced by build.sh. Signing material is transient; never publish it as an artifact.
set -euo pipefail

sign_vars=(LUMORA_KEYSTORE_B64 LUMORA_STORE_PASSWORD LUMORA_KEY_ALIAS LUMORA_KEY_PASSWORD)
any_set=0
for v in "${sign_vars[@]}"; do
  if [ -n "${!v:-}" ]; then any_set=1; fi
done

if [ "$any_set" -eq 1 ]; then
  echo "== Release 签名模式 =="
  for v in "${sign_vars[@]}"; do
    if [ -z "${!v:-}" ]; then
      echo "签名变量 $v 缺失：四者必须同时配置，不允许降级为 debug 包。" >&2
      exit 1
    fi
  done
  KEYSTORE_FILE="android/keystore/cnb-release.p12"
  signing_b64="$LUMORA_KEYSTORE_B64"
  tasks=(assembleRelease lintRelease assembleDebugAndroidTest)
  variant="release"
  apk_src="android/app/build/outputs/apk/release/app-release.apk"
  apk_dst="android/artifacts/lumora-release.apk"
  lint_src="android/app/build/reports/lint-results-release.html"
else
  echo "== 固定签名 Debug 构建模式 =="
  if [ -z "${LUMORA_DEBUG_KEYSTORE_B64:-}" ]; then
    echo "缺少 LUMORA_DEBUG_KEYSTORE_B64：请通过 CNB 机密 imports 注入拾光专用的固定预览密钥。" >&2
    echo "使用 android/ci/prepare-debug-signing.ps1 准备机密文件；不再生成随机签名的云端 APK。" >&2
    exit 1
  fi
  KEYSTORE_FILE="android/keystore/cnb-debug.p12"
  signing_b64="$LUMORA_DEBUG_KEYSTORE_B64"
  tasks=(assembleDebug lintDebug assembleDebugAndroidTest)
  variant="debug"
  apk_src="android/app/build/outputs/apk/debug/app-debug.apk"
  apk_dst="android/artifacts/lumora-debug.apk"
  lint_src="android/app/build/reports/lint-results-debug.html"
fi

umask 077
mkdir -p android/keystore
# Install cleanup before decoding so malformed input cannot leave a partial key behind.
trap 'rm -f "$KEYSTORE_FILE"' EXIT
printf '%s' "$signing_b64" | base64 -d > "$KEYSTORE_FILE"
unset signing_b64
if [ ! -s "$KEYSTORE_FILE" ]; then
  echo "密钥库解码为空" >&2
  exit 1
fi
