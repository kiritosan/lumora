# 拾光 Lumora

口袋里的光影藏馆。本仓库包含**两个互相独立**的形态：

- **网页版（根目录）**：静态 HTML/CSS/JS 收藏馆，无构建步骤，
  本地预览 `python -m http.server 8080 --bind 127.0.0.1`。
- **原生 Android 版（`android/`）**：纯 Java 原生应用，
  完全离线运行，与网页版不共享代码与数据（v1 不含网页收藏的
  自动迁移或云端同步）。

## Android 版怎么构建

主路径是 Tencent CNB 云端构建，**本机无需安装 Android SDK**：

1. 仓库接入 CNB 后，push 到 `main`/`master` 触发 `.cnb.yml` 流水线；
2. `android/ci/build.sh` 完成编译、lint、测试编译；
3. APK 与 lint 报告在流水线「附件」中：`lumora-debug.apk`
   （配置 `LUMORA_*` 签名机密后为 `lumora-release.apk`）。

细节、发布签名配置与可选的本机兜底构建见 `android/README.md`。

## 网页版验证

```powershell
node --check physics.js; node --check physics-feedback.js; node --check sw.js
python -m unittest test_showcase -v   # 浏览器回归（Playwright）
```

## 环境约定

- Android：AGP 8.3.2（buildscript classpath 方式）、Gradle 8.6、JDK 17、
  compileSdk/targetSdk 34、minSdk 26；CI 镜像 `mobiledevops/android-sdk-image:34.0.1`。
- 隐私：应用无网络权限、无分析；照片与数据仅存本机应用私有目录。
