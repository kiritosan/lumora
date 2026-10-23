# Project workflow

- Static HTML/CSS/JavaScript application; no frontend build step or runtime package dependencies.
- Run a local preview from this directory with `python -m http.server 8080 --bind 127.0.0.1`.
- Browser regression tests: install `playwright==1.49.1` with Python pip and run `python -m playwright install chromium` if its browser is not installed. Run `python -m unittest test_showcase -v` from this directory. Tests start their own local HTTP server and isolated browser contexts; they do not access the user's browser collection.
- JavaScript checks: `node --check physics.js`, `node --check physics-feedback.js`, and `node --check sw.js`. Inline JavaScript is exercised by the browser tests. Run `git diff --check` before finishing changes.
- Collectibles are stored in IndexedDB. Preserve existing user data; presentation-only interactions must not overwrite collections.
- Optional cloud sync lives in `cloud.js`: it calls Supabase PostgREST (`/rest/v1/shiguang_items`) with the user's own project URL + anon key + a shared sync key stored in `localStorage`. `supabase-setup.sql` creates the required table. Item payloads are AES-GCM encrypted client-side when `crypto.subtle` is available; rows are keyed by SHA-256(sync key) so the server never sees the raw key. Sync is last-write-wins on `item.updated`; deletions push tombstones.
- New app assets must be added to `sw.js` precache entries with a cache-version bump. Existing service-worker users may need to refresh again after the update is activated.
- Windows terminal commands use PowerShell syntax, not Bash heredocs.

# Android 原生工程（android/）

- `android/` 是独立的纯 Java 原生应用（AGP 8.3.2 + Gradle 8.6，compileSdk/targetSdk 34，minSdk 26）：平台 Views/Canvas，无 WebView/TWA，无任何第三方运行时依赖，无网络权限。与网页版同产品不同实现，数据互相隔离（v1 不含网页收藏的自动迁移/云同步）。
- android/ 下的资源不是网页 Service Worker 缓存资产：改动 Android 工程**不**更新 `sw.js` 的 SHELL 清单与 CACHE 版本；反之亦然。
- 构建主路径是 Tencent CNB：push 到 `main`/`master` 触发根 `.cnb.yml` → `android/ci/build.sh`（assembleDebug|assembleRelease + lint + assembleDebugAndroidTest）→ 附件阶段发布 `android/artifacts/*`（APK、lint 报告、SHA256SUMS）。本机不需要 SDK；可选兜底 `android/build.ps1`（自动修正误配 JAVA_HOME，用 `~/.jdks` JDK17 与 `~/.gradle` 缓存的 Gradle 8.6，`--offline`）。
- Release 签名只走 CNB 机密仓库 imports（模板 `android/ci/lumora-secrets.example.yml`）；四个 `LUMORA_*` 缺一即显式失败，绝不静默降级为 debug/未签名包。密钥为拾光专用，不复用其它应用。
- `android/local.properties`、`android/**/build/`、`android/keystore/`、`android/artifacts/`、APK/密钥库均不入库。
- 测试用 framework 自带 `android.test` runner（无 androidx）；CI 仅编译测试，设备/模拟器运行需在隔离环境另行执行，不在个人设备上跑破坏性测试。
