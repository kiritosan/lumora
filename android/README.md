# 拾光 Lumora · 原生 Android 工程

口袋里的光影藏馆——纯 Java + 平台 Views/Canvas 的原生应用。
与网页版同根的产品，但代码、数据、资源完全独立：无 WebView、无 TWA、
无第三方运行时依赖、无网络权限、无统计。

## 构建（主路径：Tencent CNB）

本工程面向云端构建，本机不需要 Android SDK。

1. 把本仓库导入 CNB（或推送到已关联 CNB 的仓库）。
2. push 到 `main` 或 `master` 即触发根目录 `.cnb.yml` 流水线：
   - `android/ci/build.sh`：编译 `assembleDebug`/`assembleRelease` +
     `lint` + 测试编译 `assembleDebugAndroidTest`；
   - 附件阶段把 `android/artifacts/*`（APK、lint 报告、SHA256SUMS）挂到流水线记录。
3. 在 CNB 流水线页面的「附件」里下载 APK 安装即可。
   - 未配置签名时产出 `lumora-debug.apk`（debug 密钥随云端构建机而变，
     只做预览，不作为可升级的生产包）。

### 发布签名（可选，长期发布前必须配好）

- 在 CNB 建机密仓库，按 `android/ci/lumora-secrets.example.yml` 填四个
  `LUMORA_*` 变量（PKCS12 密钥库 base64 + 密码 + 别名）。
- 把根 `.cnb.yml` 里 `.android` 锚点内（volumes 之后）注释掉的 `# imports:` 行取消注释，指向该机密文件。
- 四变量齐备 → 流水线自动切 release；缺一即显式失败，绝不静默降级。
- 密钥必须是为拾光新生成的，不复用其它应用的密钥/包名；
  debug 密钥在云端构建机间不稳定，正式积累藏品数据前先配好持久签名。

## 本机构建（可选兜底）

需要 JDK 17+、Android SDK（platforms/android-34 + build-tools/34.0.0）。
`android/local.properties`（不入库）写 `sdk.dir=...`，然后：

```powershell
powershell -ExecutionPolicy Bypass -File android\build.ps1
# 产物：android\app\build\outputs\apk\debug\app-debug.apk
```

`build.ps1` 自动修正误配到 `bin` 的 JAVA_HOME、找 `~/.jdks` 的 JDK17
与 `~/.gradle` 缓存的 Gradle 8.6，默认 `--offline`。

## 测试

`src/androidTest` 用 framework 自带 `android.test` runner（无 androidx 依赖）。
CI 里 `assembleDebugAndroidTest` 只保证编译通过；设备/模拟器上的
`connectedDebugAndroidTest` 需要在隔离模拟器上另行执行，本仓库不做
对个人设备的测试。测试包只针对 debug 构建（`.debug` 包名后缀 + 独立数据区），
不会触达已安装的正式版数据；但 instrumentation 跑在目标应用进程里，
冒烟/AppState 等用例**会读写 debug 应用自己的私有藏品目录与偏好**——
请只在隔离模拟器的 debug 安装上跑。纯数据用例（store/repo/provider）
再用隔离目录/ContextWrapper 自建沙箱，不碰应用真实路径。

覆盖：store 播种/不重播种/.bak 回滚/版本与重复 id 拒绝/未载入拒写/
写盘失败不改内存、草稿拷贝隔离、备份校验（异构/版本/条数/坏图/路径穿越）
与事务恢复（就位失败回滚、重复 id 不留孤儿）、导出与恢复同口径上限
（100 件 / 32MB、照片缺失即失败）、照片导入上限/截断图拒绝/EXIF 旋转与镜像
归正、ShareProvider 只读与穿越拒绝、分享卡 1080×1440、
AppState 单例/busy 互斥/监听注销/暂挂草稿、三页签冒烟与 intent 消费一次性。

## 结构速览

```
app/src/main/java/com/lumora/app/
  Item.java             藏品模型 + 字段校验
  CollectionStore.java  AtomicFile JSON 存储（损坏报错，绝不重播种）
  ImageRepository.java  照片导入/EXIF/限流、备份导出与恢复暂存
  ArtworkRenderer.java  四种程序化光景 + 釉光/追光/分享卡渲染
  ArtworkView.java      触摸+重力追光（仅可见前台注册传感器）
  MainActivity.java     三页签 + 覆盖式编辑 + 意图分发
  HomePanel/StagePanel/EditPanel/ProfilePanel.java
  ShareProvider.java    非导出受限文件出口（分享只读 / 相机写需凭证）
  LumoraWidget.java     「今日一藏」桌面小组件
```
