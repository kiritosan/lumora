#!/usr/bin/env python3
"""CNB 配置与构建脚本静态回归检查（不调用 Gradle/网络）。

用法: python android/ci/check-cnb-shape.py [repoRoot]
逐项断言 .cnb.yml 流水线形状、secrets 模板平铺字段、.gitattributes LF 规则、
build.sh 的签名分支逻辑与 trap 顺序。任何一项失败即非零退出。
"""
import os
import re
import sys

FAILURES = []


def check(name, ok, detail=""):
    mark = "OK " if ok else "FAIL"
    print("%s %s %s" % (mark, name, detail))
    if not ok:
        FAILURES.append(name)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
    cnb = os.path.join(root, ".cnb.yml")
    sec = os.path.join(root, "android", "ci", "lumora-secrets.example.yml")
    sh = os.path.join(root, "android", "ci", "build.sh")
    signing = os.path.join(root, "android", "ci", "prepare-signing.sh")
    ga = os.path.join(root, ".gitattributes")

    for p in (cnb, sec, sh, signing, ga):
        check("exists %s" % os.path.relpath(p, root), os.path.isfile(p))

    # ---- .cnb.yml 结构 ----
    try:
        y = open(cnb, encoding="utf-8").read()
    except OSError:
        y = ""
    lines = y.splitlines()
    check("anchor .android: &android", ".android: &android" in y)
    check("docker image sdk34", "image: mobiledevops/android-sdk-image:34.0.1" in y)
    check("gradle volumes", "/root/.gradle:copy-on-write" in y
          and "/root/gradle-dist:copy-on-write" in y)
    check("private signing imports inside anchor",
          re.search(r"^  imports: https://cnb\.cool/sizuku-2025/lumora-secrets/-/blob/main/lumora-debug-secrets\.yml$",
                    y, re.M) is not None)
    check("stages block", re.search(r"^\s{2}stages:", y, re.M) is not None)
    i_build = y.find("- name: Build Native Android")
    i_upload = y.find("- name: Upload APK And Reports")
    check("stage order build->upload", 0 < i_build < i_upload)
    check("build stage script", "script: bash android/ci/build.sh" in y)
    check("upload stage image", "image: cnbcool/attachments:latest" in y)
    check("attachments glob", "android/artifacts/*" in y)
    # 上传段与下一个根级键之间不得再出现 docker:（防旧版双任务结构残留）
    upload_tail = []
    started = False
    for ln in lines:
        if "- name: Upload APK And Reports" in ln:
            started = True
            continue
        if started and re.match(r"^[A-Za-z_.]", ln):  # 下一个根级键
            break
        if started:
            upload_tail.append(ln)
    check("no stray docker on upload stage",
          not any(re.match(r"^\s+docker\s*:", l) for l in upload_tail))
    check("main push uses anchor",
          re.search(r"^main:\s*\n\s+push:\s*\n\s+- <<: \*android", y, re.M) is not None)
    check("master push uses anchor",
          re.search(r"^master:\s*\n\s+push:\s*\n\s+- <<: \*android", y, re.M) is not None)
    check("no tabs in cnb.yml", "\t" not in y)

    # ---- secrets 模板：顶层平铺 ----
    try:
        s = open(sec, encoding="utf-8").read()
    except OSError:
        s = ""
    check("no env: nesting", re.search(r"^env:", s, re.M) is None)
    for v in ("LUMORA_KEYSTORE_B64", "LUMORA_STORE_PASSWORD",
              "LUMORA_KEY_ALIAS", "LUMORA_KEY_PASSWORD"):
        check("top-level %s" % v, re.search(r"^%s:" % v, s, re.M) is not None)
    for k in ("allow_slugs", "allow_branches", "allow_events"):
        check("has %s" % k, k in s)

    # ---- .gitattributes ----
    try:
        g = open(ga, encoding="utf-8").read()
    except OSError:
        g = ""
    check("*.sh eol=lf", re.search(r"^\*\.sh\s+text\s+eol=lf", g, re.M) is not None)
    check("gradlew eol=lf", re.search(r"^gradlew\s+text\s+eol=lf", g, re.M) is not None)

    # ---- build.sh：签名四变量全有或全无 + trap 先于解码 ----
    try:
        b = open(sh, encoding="utf-8").read()
    except OSError:
        b = ""
    check("set -euo pipefail", "set -euo pipefail" in b)
    check("signing helper sourced", "source android/ci/prepare-signing.sh" in b)
    with open(signing, encoding="utf-8") as stream:
        signing_source = stream.read()
    check("sign var list", "LUMORA_KEYSTORE_B64" in signing_source
          and "LUMORA_STORE_PASSWORD" in signing_source and "LUMORA_KEY_ALIAS" in signing_source
          and "LUMORA_KEY_PASSWORD" in signing_source)
    check("any-set detection", 'any_set' in signing_source)
    check("partial config fails", "不允许降级为 debug 包" in signing_source)
    check("fixed debug key required", "LUMORA_DEBUG_KEYSTORE_B64" in signing_source
          and "不再生成随机签名" in signing_source)
    i_trap = signing_source.find("trap 'rm -f \"$KEYSTORE_FILE\"' EXIT")
    i_decode = signing_source.find("base64 -d")
    check("trap before decode", -1 < i_trap < i_decode)
    check("artifacts preclean", "rm -f android/artifacts/lumora-debug.apk" in b)
    check("gradle sha256 verify", "sha256sum" in b and "expect" in b)
    check("apksigner verify", "apksigner\" verify" in b or "apksigner verify" in b
          or "$APKSIGNER\" verify" in b or "APKSIGNER" in b)
    check("no secrets in artifacts",
          "keystore" not in re.sub(r"#.*", "", "\n".join(
              l for l in b.splitlines() if "artifacts" in l)))
    check("no tabs in build.sh", "\t" not in b)

    # ---- app/build.gradle 配套声明 ----
    bg = os.path.join(root, "android", "app", "build.gradle")
    try:
        a = open(bg, encoding="utf-8").read()
    except OSError:
        a = ""
    check("useLibrary test runner", "useLibrary 'android.test.runner'" in a)
    check("useLibrary test base", "useLibrary 'android.test.base'" in a)
    check("useLibrary test mock", "useLibrary 'android.test.mock'" in a)
    check("lint abortOnError true", "abortOnError true" in a)
    check("debug suffix", "applicationIdSuffix" in a)
    check("fixed preview signing", "LUMORA_DEBUG_KEYSTORE_B64" in a
          and "signingConfig signingConfigs.preview" in a and "keystore/cnb-debug.p12" in a)
    check("AGP 8.3.2 classpath",
          "com.android.tools.build:gradle:8.3.2" in open(
              os.path.join(root, "android", "build.gradle"), encoding="utf-8").read())

    print("----")
    if FAILURES:
        print("FAILED: %d checks -> %s" % (len(FAILURES), ", ".join(FAILURES)))
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
