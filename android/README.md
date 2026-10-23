# 拾光 Lumora · 原生 Android 工程

口袋里的光影藏馆——纯 Java + 平台 Views/Canvas 的原生应用。
与网页版同根的产品，但代码、数据、资源完全独立：无 WebView、无 TWA、
无第三方运行时依赖、无网络权限、无统计。

## 1.2 · 装裱与来信

- 首页以原生 Canvas 的拱窗光景作为视觉签名，优化展签字距与卡片留白。
  「我的收藏」可隐藏示例，长按画廊或时间档案中的藏品可心动、编辑、制作分享卡。
- 光桌新增无边 / 画廊 / 拱窗三种装裱，装裱偏好只存本机设置。
  「完整原图」保留照片构图与颜色，无裁切、无釉光；不修改照片文件或藏品字段。
- 分享前打开本地工作室：美术馆 / 夜色 / 来信三种版式，后台生成实时预览；
  最终输出 1080×1440 PNG。手记默认不带出，开启后显示最多六行的摘录。
  可交给系统分享面板，或通过系统文件选择器直接保存图片，不申请存储权限。
- 切换版式或关闭窗口后丢弃过期预览；文件选择器期间的待保存卡片信息随 Activity 状态恢复。
- 提升桌面小组件文字对比度、字号与操作区域；API 31 的桌面尺寸提示使用独立资源版本。

命令行只读查看当前提交的云构建（公开页面，不需要浏览器或令牌）：

```sh
python3 android/ci/cloud_status.py
# 或 python3 android/ci/cloud_status.py --sha <完整提交 SHA>
```

返回码：0 已成功；1 已失败/取消；2 构建中或尚未出现；3 无法读取状态。
新增像素级 instrumentation 用例覆盖分享手记默认不泄露、三版式尺寸、原图边缘与色彩保留；
CI 编译这些用例，实际运行需要隔离模拟器。

## 1.1 · 私人光影藏馆

- **藏馆**：每日重逢优先挑选个人收藏，同一天保持稳定；支持标题/手记/编号搜索、
  心动与材质组合筛选，以及最近、最早、标题、心动优先四种排序。
  画廊展示全部结果，时间档案按月份排列；搜索、筛选、排序和视图在旋转后保留。
- **光桌**：触摸与倾斜追光、翻面手记、随机重逢；「静观」提供深墨青背景、
  独立的上下张控制和返回退出。传感器只在当前前台画作可见时启用，尊重系统关闭动画设置。
- **手记**：三种材质的即时预览与说明，六条轮换灵感提示；提示不自动填入手记。
  离开有改动的编辑页会提示舍弃，保存时冻结输入并显示进度。
- **我的**：收藏者卡片、个人藏品/心动/收藏日统计、可翻月的收藏日历
  （点亮日期可打开当日藏品）、材质光谱。个人统计、日历和光谱不含示例藏品。
- **桌面**：一瞬（方形）、光景（横幅）、今日一藏（画廊）三种小组件，
  每个组件独立记住浏览位置，支持下一件与点击进入。
- **视觉**：暖纸渐变、墨青展签、香槟细节、原生线描导航和统一触摸涟漪。

以上浏览行为均不写藏品数据，不变更存储格式；收藏、照片、备份格式保持兼容。
Android 资源与网页版完全独立，不涉及网页 Service Worker 更新。

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

测试源码另覆盖每日精选稳定性、组合过滤与排序的只读性、土耳其语区域搜索、
闰月与示例排除、静观退出与空集合回退、灵感提示不改手记。
这些 instrumentation 用例由 CI 编译，运行仍需隔离模拟器。

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
  CollectionInsights.java  只读排序/每日重逢/本地日期统计
  CollectionJournal.java   可访问的月历与材质光谱
  GalleryMark.java         原生线描导航
  HomePanel/StagePanel/EditPanel/ProfilePanel.java
  ShareProvider.java    非导出受限文件出口（分享只读 / 相机写需凭证）
  LumoraWidget.java     「今日一藏」桌面小组件
```
