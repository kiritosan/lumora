# 拾光 Lumora · 原生 Android 工程

口袋里的光影藏馆——纯 Java + 平台 Views/Canvas 的原生应用。
与网页版同根的产品，但代码、数据、资源完全独立：无 WebView、无 TWA、
无第三方运行时依赖、无网络权限、无统计。

## 2.2 · 桌面策展与完整构图

在既有三种尺寸、三种展陈方式和每日精选上继续扩展，无新增权限或运行时依赖。

- **展厅外观**：每个小部件独立选择纸白、墨青夜馆或琥珀暖砂，背景、展签、
  手记和操作文字一起换色；旧小部件默认保持纸白。
- **组合策展**：全部/心动/材质范围可以继续叠加「只看我的收藏」「只看照片」
  「只看有手记」，配置页即时显示匹配件数。没有匹配时展示等待状态，
  不擅自换成其他范围；之后新增或修改符合条件的藏品会刷新小部件。
  固定藏品也必须符合组合条件，删除或移出范围后回退到当前范围。
- **完整构图**：显式开启后，照片按比例居中留边，保留四边及原色，不叠加材质釉光；
  留边跟随展厅外观，三联中的每张照片也独立保留全貌。图像区域使用适应居中，
  避免启动器拉伸卡片后二次裁掉画面。关闭此选项保留原有材质光景渲染。
- **双向浏览**：宽度至少 184dp 的画廊卡、至少 360dp 的横幅卡显示「上件 / 下件」，
  首尾循环；一瞬和较窄卡片保留两项操作。固定展品或只有一件时隐藏上件。
  手动换件保持到当日结束，关闭每日精选后持续保留；展签附当前范围的总件数。
- **手记边界**：「有手记」只筛选，不自动展示正文；仅明确选择「一页手记」时显示。
  新配置独立存于小部件偏好，随删除清理、随启动器 ID 恢复迁移，收藏与备份格式不变。

### 2.2 验证入口

继续使用根 `.cnb.yml` → `android/ci/build.sh`，不新建云构建入口：

| CNB 模式 | 必须通过的任务 | 应核对的附件 |
| --- | --- | --- |
| 只配置固定预览签名 | `assembleDebug lintDebug assembleDebugAndroidTest` | `lumora-debug.apk`、`lint-results-debug.html`、`SHA256SUMS.txt` |
| 四个签名变量齐备 | `assembleRelease lintRelease assembleDebugAndroidTest` | `lumora-release.apk`、`lint-results-release.html`、`SHA256SUMS.txt` |
| 只配置部分签名变量 | 必须显式失败 | 不应产生新 APK |

无任何签名配置时也会失败：云端预览包需要 `LUMORA_DEBUG_KEYSTORE_B64`，不再自动使用临时 debug 密钥。
两种模式还会附带公开的 `signing-certificate.txt`，便于比较新旧 APK 的证书 SHA-256 指纹。

`WidgetExhibitTest` 增加组合范围、空范围、上一件循环、同日保留、独立偏好、
删除清理及新 ID 配置复制的用例；`WidgetAppearanceTest` 检查三主题下的原图四边、
中心颜色及源位图不变，并实际应用三种布局的 RemoteViews 主题操作。
CNB 的测试任务只编译这些用例，实际运行仍需隔离模拟器的 debug 包。

启动器验收：在 API 26 和 API 31+ 的隔离模拟器各添加三种尺寸，检查切换主题、
组合筛选为零件、固定项被删除/不再匹配、三联完整构图、上一件首尾循环、跨日刷新、
缩放、较大系统字体、旋转配置页、删除并重新添加及多个小部件互不干扰。
确认非手记样式不显示正文，配置和浏览前后的收藏导出数据一致。
小部件更新受系统节电与启动器调度影响，继续不保证零点准时切换。

本次改动文件（均位于 `android/`）：

| 文件 | 用途 |
| --- | --- |
| `app/src/main/java/com/lumora/app/WidgetExhibit.java` | 组合筛选、外观偏好、双向选择与配置清理 |
| `app/src/main/java/com/lumora/app/WidgetConfigureActivity.java` | 策展选项、实时匹配数量与旋转恢复 |
| `app/src/main/java/com/lumora/app/WidgetAppearance.java`（新增） | 三主题配色与照片完整构图 |
| `app/src/main/java/com/lumora/app/LumoraWidget.java` | 双向操作、主题应用与三联渲染 |
| `app/src/main/res/layout/widget_compact.xml` | 一瞬布局的适应居中画面与统一控件 |
| `app/src/main/res/layout/widget_wide.xml` | 横幅布局及上一件入口 |
| `app/src/main/res/layout/widget_collectible.xml` | 画廊布局及上一件入口 |
| `app/src/main/res/drawable/widget_bg_night.xml`（新增） | 墨青夜馆背景 |
| `app/src/main/res/drawable/widget_bg_sand.xml`（新增） | 琥珀暖砂背景 |
| `app/src/main/res/values/strings.xml` | 上一件文字与无障碍描述 |
| `app/src/androidTest/java/com/lumora/app/WidgetExhibitTest.java` | 筛选、浏览及独立偏好回归用例 |
| `app/src/androidTest/java/com/lumora/app/WidgetAppearanceTest.java`（新增） | 原图像素保留与三布局主题应用用例 |
| `app/build.gradle` | 版本升级为 2.2.0（6） |
| `README.md` | 交互、构建产物及设备验收说明 |

## 2.1 · 收藏色谱与桌面展览

- 藏馆新增「收藏色谱」：在后台对照片的小图采样，按画面主色分为朱砂、晨金、
  苔绿、海蓝、鸢尾、蔷薇与素色；色样来自实际像素，程序画作使用渲染小图。
  照片缺失时单独显示「照片暂不可用」，不拿占位图取色。它是近似色彩浏览，
  不是专业色彩分析；不改照片、材质或藏品数据。
- 新增「手记展览」：把已有手记排成留白的引文卡片，与画廊、时间档案共享搜索、
  心动、材质与个人收藏筛选。观看方式随旋转/进程状态恢复。
- 三种尺寸的小部件都可点「布展」独立配置：全部/心动/指定材质，固定一件，
  每日精选或手动浏览，以及一件光景/三联展陈/一页手记。
  三联最多展示三件不同藏品，点击画面打开第一件；固定作品被删除后回退到当前范围。
  手记仅在明确选择手记样式后显示，其他样式不展示手记文字。
- 每日精选按设备本地日期稳定选择，手动换件保持到当日结束；关闭每日精选后一直保留
  手动选择。系统约每小时请求更新，受省电/启动器调度影响，不保证零点准时切换。
  小部件删除会清理自己的配置，恢复/迁移会映射新小部件 ID。

本次变更从 CNB 已通过构建的 `a243da3b` 开始，保留此前的触感托盘、光学放大镜、
分享工作室与装裱功能。预览分支 `feat/collection-lenses` 的 push 也会触发 `.cnb.yml`，
无需合并到主分支即可在 CNB 检查 APK、lint 与测试包编译。
接入固定签名后，该预览分支还需在机密文件的 `allow_branches` 中显式授权；
当前生成的机密配置只允许 `main` / `master`，默认请在主分支验证固定签名构建。
新增测试涵盖真实像素取色/缺失照片/只读性、严格范围、独立配置、跨日轮换与固定展品回退；
CNB 编译这些 instrumentation 用例，设备运行仍需隔离模拟器。

## 2.0 · 器物、光影与掌心把玩

- **五种器物装裱 + 完整原图**：光桌支持「无边」「画廊」「拱窗」「徽章（黄铜浮雕圆章）」「相纸（宝丽来手写展签）」五种器物形态，以及 100% 零裁切、零色偏的「完整原图」检视模式。
- **四种展厅灯温与全息色散**：支持暖卤素、美术馆昼光、青月冷光、烛火琥珀四档展厅聚光灯；配合蜜金拉丝、海盐晶面、银雾丝绢三材质的双重全息色散流光带，随手机倾角或指尖抚摸实时流动。
- **微距检视与光学寻光镜（Loupe）**：支持双击 2.1× 微距漫游画作细部、左右轻扫切换藏品，以及开启 2.35× 黄铜圈光学寻光放大镜检视局部肌理。
- **虫漆暗金典藏背板**：3D 翻面后呈现黑曜石底色、篆刻式火漆黄铜编号章与私密手记长卷。
- **掌心物理把玩沙盘（Tactile Study）**：从藏馆或光桌一键召唤天鹅绒暗盘，将最多 8 件馆藏器物化为可拖拽抛掷、弹性碰撞、失重悬浮、倾斜感应与磁吸/斥力互动的实体卡片，碰撞时绽放材质微光涟漪与触感反馈；全程只读快照，不污染藏品数据。

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
   - 只配置 `LUMORA_DEBUG_KEYSTORE_B64` 时产出固定预览签名的 `lumora-debug.apk`。
   - 四个正式签名变量齐备时产出 `lumora-release.apk`；正式签名配置优先。
   - 没有签名配置时显式失败，不再生成每次可能更换签名的预览包。

### 预览版覆盖更新（一次配置）

Android 覆盖安装需要保持包名、签名证书一致，且 `versionCode` 不低于已安装版本。
每次发布仍应递增 `app/build.gradle` 中的 `versionCode`；不要通过改包名或删除应用数据解决更新。
预览版包名始终为 `com.lumora.app.debug`，正式版始终为 `com.lumora.app`，两者互不覆盖。

1. 若能找回**正在使用的旧云构建**的 `debug.keystore`，优先导入原密钥：

   ```powershell
   ./android/ci/prepare-debug-signing.ps1 -SourceKeystore '旧构建的 debug.keystore 路径'
   ```

   这会保留原证书与私钥，仅转换成 PKCS12 格式；默认采用 Android debug 的
   `androiddebugkey` 别名与 `android` 密码。不是从 APK 中提取私钥，APK 本身无法恢复私钥。

2. 没有旧密钥时，为拾光生成一次独立的预览密钥：

   ```powershell
   ./android/ci/prepare-debug-signing.ps1
   ```

   只需要 JDK，无需 SDK。密钥保存在被 Git 忽略的 `android/keystore/lumora-preview.p12`，
   配套的 `lumora-debug-secrets.yml` 包含 CNB 机密配置，重复执行会复用同一密钥，拒绝覆盖已有密钥。
   **新密钥无法覆盖旧签名的安装**；没有旧私钥时先从旧应用导出收藏备份，
   再完成一次新签名版本的安装与备份恢复，之后持续使用同一签名即可覆盖更新。

3. 新建一个 CNB 机密仓库，将生成的 `lumora-debug-secrets.yml` 上传到该私密仓库，
   把根 `.cnb.yml` 的 `imports` 指向该文件的 blob 地址。
   当前已接入 `sizuku-2025/lumora-secrets` 的 `main/lumora-debug-secrets.yml`。
   参考 `android/ci/lumora-debug-secrets.example.yml`；仓库路径按实际调整，默认仅允许 main/master push 引用。
   **不要上传到公开 Lumora 仓库、流水线附件或日志。** 标准 debug 密码是公开约定，
   安全性依赖密钥文件保持私密。请同时保留离线备份。

4. 连续构建两版，确认 `signing-certificate.txt` 中证书 SHA-256 指纹相同。
   在隔离模拟器先安装旧版、写入测试收藏，再直接安装新版，确认收藏、照片和小部件配置保留。
   仅 APK 编译通过不能证明升级成功；不要对个人设备执行卸载或清数据测试。

云端的 `.android/debug.keystore` 会随构建环境改变，Gradle 缓存也不等于签名备份。
`prepare-signing.sh` 在构建期间解码固定密钥，并在失败或退出时删除临时文件。
CNB 无法读取私密密钥或变量缺失时会主动失败；本地默认 debug 构建不受此限制。

不需要 SDK 的签名回归检查：

```powershell
python android/ci/check-cnb-shape.py
python -m unittest discover -s android/ci -p test_signing.py -v
```

测试使用临时目录中的测试密钥，覆盖固定密钥复用、旧密钥导入后证书一致、
无密钥拒绝、Release 四变量缺一拒绝、模式优先级、坏 base64 拒绝及退出清理。

### 发布签名（可选，长期发布前必须配好）

- 在 CNB 建机密仓库，按 `android/ci/lumora-secrets.example.yml` 填四个
  `LUMORA_*` 变量（PKCS12 密钥库 base64 + 密码 + 别名）。
- 把根 `.cnb.yml` 里 `.android` 锚点内（volumes 之后）的 `imports:` 改为该正式签名机密文件地址。
- 四变量齐备 → 流水线自动切 release；缺一即显式失败，绝不静默降级。
- 密钥必须是为拾光新生成的，不复用其它应用的密钥/包名；
  预览版与正式版分别维护固定密钥，不复用；正式积累藏品数据前先配好持久发布签名。

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
