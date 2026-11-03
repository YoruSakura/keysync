# KeySyncFix：物理机 Codex 新会话接手说明

交接更新：2026-11-03（Asia/Shanghai）。本文是当前交接入口；较早的 `PROJECT_MEMORY.md` 中的虚拟机路径、无法连接设备及旧版本描述属于历史记录。

1.61 正式发布补记（2026-11-03）：用户已确认两项优化并要求推送新代码和发布包，沿用直接合并 main、只留一个正式包的授权。正式版本 **1.61 / code29**，应用源码 `d73a918`，96 项测试、Lint、同签名 Release/R8 与四 ABI 构建通过。包为 `artifacts/KeySyncFix-v1.61.apk`，5,990,838 字节，SHA-256 `46da07e0ceab3c9d679f144acc565a729a6a4b054334b667f51257c4d313d7cd`；旧 1.60 包及说明已归档。平板仍装已实测的 1.61-debug / code28，未因发布操作覆盖安装。最新发布配置见 [docs/VALIDATION.md](docs/VALIDATION.md)，下文未提交／仅本地状态为历史。

本地性能版补记（1.61-debug / code28）：分支 `codex/mouse-performance`，基于 `main` 的 `8d913f3`。光标改为帧回调直接更新原生图层，用户确认明显更顺滑，最近 120 帧间隔全部约 16.7ms。视角独立为 1%–100%、步进 1%，用户在新版 50% 实体确认“转向正常，容易控制”。光标速度保留实际倍率，备份升级 v4。16 项相关测试、Lint、Debug/四 ABI 构建通过，同签名覆盖安装且配置逐字节一致；随后实际保存只新增视角字段，原有设置和方案完整保留。详见 [docs/MOUSE_PERFORMANCE.md](docs/MOUSE_PERFORMANCE.md)。

正式版发布补记（2026-11-02）：用户已确认当前 fork 稳定，授权直接合并、推送 GitHub 并只保留一个正式包。本轮版本为 **1.60 / versionCode 27**，保留包名和迁移签名，使用 Release/R8；此前“未授权推送／仅本地／实验版”的段落均为历史。发布和验证状态统一见 [docs/VALIDATION.md](docs/VALIDATION.md)。

最新功能补记（2026-11-02，1.60）：已安装 **1.60 / versionCode 26**，适配明日方舟的光标联合宏和按住 Alt 的 Observe。原 JSON 与独立平板校准副本均已导入，当前选中 `arknights-tablet-3200x2136`；终末地校准方案保留不变。用户已实测 C 点击、F 时停、D 一键技能、A 一键撤退及 S 快速选取正常。右键划火柴属于旧方案，用户说明新游戏已支持暂停部署，不再作为本次必测项。详细实现、构建与剩余验证见第 16 节。

最新功能补记（2026-11-02）：当前已安装 **1.59 / versionCode 25**，导入 MuMu 后可点开悬浮球，在游戏画面拖动整套触点并保存到当前方案；宏点击和显示图标可分别校准。用户已确认 1.58.1 **松键即停、WASD 四方向正常**。原方案来自 **1920×1080**；平板 3200×2136 的游戏 UI 布局不同，不能仅按全屏归一化拉伸后声称全部对齐。当前仍选中 `WASD-3200x2136` 校准副本，已保存多项布局调整，原始 MuMu 保留。平板使用 **2.4G 鼠标＋无线 ADB**，现场地址 `10.1.1.115:45941`（端口可能变化，下次须重新枚举）。最新验证与剩余事项见本文第 15 节。

最新功能补记（2026-11-01）：当前已安装 1.58 / versionCode 23，多游戏独立多份 MuMu 方案入口改为主页游戏图标下的“方案”；旧方案自动迁移，备份升级 v3 并兼容恢复 v1/v2。具体实现、实机证据与最终 APK 指纹见 `PROJECT_MEMORY.md` 最后一节和 `docs/TOUCH_CORE.md`。下文 1.56 及旧导入入口是历史记录。

本地提交补记（2026-11-01）：已按用户要求将当前 1.56 MuMu 显示修复版的完整源码、测试、参考 JSON 与交接文档保存为 `codex/touch-engine-rewrite` 上的本地提交，最新提交以 `git log -1` 为准。下文的旧 HEAD 和“尚未提交”均是当时的交接记录；APK 留在本地，不纳入源码提交，也未推送远端。

## 1. 本次迁移的目的与边界

本项目此前在虚拟机中开发，不能直接连接 Android 平板、键盘和鼠标，现转到物理机做实机验证。用户已经用同步软件同步项目，不需要重新 clone、打包或手工传输源码；请在物理机上打开现有同步目录。

2026-11-01 实际查到的虚拟机目录为 `/home/sakura/localproject/KeySyncFix`，旧会话中的 `/home/sakura/本地项目/KeySyncFix` 已不存在。物理机路径、操作系统、SDK 和设备序列号应在新会话现场确认，不要沿用这里的绝对路径。

本次只交接和清理虚拟机 Android 开发残留，不实现新的游戏功能，不升级构建插件，不自动提交、推送或发布。gamepad-fork 路线继续暂停；接下来优先验证已有 Framework Touch Core。

## 2. 最重要：当前源码不能用 Git HEAD 代替

- 当前分支：`codex/touch-engine-rewrite`。
- 当前 HEAD：`f1461761f8cb0a90e0c11f22b2a9e899b6e6ca04`，只是较早的已提交基线。
- 大量用户实机修复和整个新 Touch Core 都还在未提交工作区，包含已修改文件和未跟踪文件。只 clone / pull 远端拿不到这些完整功能。
- 接手时先运行 `git status --short`、`git branch --show-current`、`git diff --stat`，确认同步工作树存在。禁止 `git reset --hard`、`git clean`、强制 checkout、直接拉远端覆盖工作树。
- 必须保留 `app/src/main/cpp/`、`domain/touch/`、Root/Shizuku 后端、备份代码、测试、`参考文档/`、`docs/` 和 `artifacts/` 中尚未跟踪的文件。
- 不修改同步软件配置、不把删除项目当作清理环境。两台机器不要同时编辑或构建同一同步工作树，避免旧缓存和 `.git` 冲突。

远端：`origin` 为 `https://github.com/YoruSakura/keysync.git`，`upstream` 为 `https://github.com/aka-munan/keysync.git`。2026-10-06 的历史审计认为上游 main 已包含在本地基线内；这是历史结论，并非本次重新 fetch 的结果，不要据此直接合并上游 native 实验分支。

## 3. 请按顺序读取这些资料

1. 本文件 `MIGRATION.md`。
2. `docs/TOUCH_CORE.md`：1.56 架构、实际接入点、可配置约定和验收顺序。
3. `PROJECT_MEMORY.md` 最后的“MuMu 文档驱动的 Touch Core（2026-10-08）”及“环境交接（2026-11-01）”章节；前文是历史演变，不能把旧 100ms 抬指方案重新当作新核心规范。
4. `参考文档/com.hypergryph.endfield-默认操作方案.json`：必须读取原 JSON，不能用聊天摘要的近似坐标替代。
5. `app/src/test/java/com/devoid/keysync/domain/touch/`、原 `EventHandlerTest` 和 Root 捕获测试。

910 行原始开发说明曾作为会话附件提供，但不在当前 `参考文档/` 中；附件路径和旧截图位于旧会话状态目录，不保证随项目同步。工程接手应以仓库现有源码、上述文档和原 JSON 为依据，需要原附件时再向用户索取，不凭空补造。

## 4. 产品要求和已确认的硬件条件

- 用户的平板可通过 KSU 临时 Root 正常给应用授予 Root。Root 是主导模式；无 Root 才由用户手动选择 Shizuku/ADB。
- 输入模式放在应用系统设置，不放在悬浮窗。运行中 Root/原生独占捕获失效必须停止映射并释放键鼠，不能自动切换权限后端。
- 小米键盘走平板背部触点/私有连接，不能只筛选 USB 或蓝牙设备。历史用户反馈已经确认键盘操作和鼠标基础操作正常，应保护现有 Root 设备匹配与捕获逻辑。
- 映射只绑定从 KeySync 内启动的目标应用。切到其他应用立即解除捕获/映射，切回目标应用恢复；不为无关前台应用沿用配置。
- 鼠标模式：光标处点击/拖动；滚轮在光标处模拟上滚上划、下滚下划。瞄准模式不把滚轮转成此类滑动。
- 普通技能键按住多久触点保持多久，自然区分短按与长按，不能改回固定短 tap。
- 不声称“0 冲突”或已解决端到端延迟；必须靠真实设备、游戏操作和诊断确认。

## 5. 当前 Touch Core 实现与不应回退的行为

当前包为 `com.devoid.keysync.touch`，versionCode `20`，versionName `1.56-touch-core-alpha-debug`（Debug）。namespace 仍是 `com.devoid.keysync`。

| 层级 | 入口 / 文件 | 接手时关注点 |
|---|---|---|
| Root 采集 | `app/src/main/cpp/RootEvdevCapture.cpp` | 白名单、EVIOCGRAB、组合设备节点、小米私有键盘、高分辨率滚轮和断开清理，勿盲目重写 |
| 权限通道 | `RootInputService.kt`、`RootInputConnection.kt`、`ShizukuSystemServerAPi.kt` | 复用长驻服务/Binder，不为每个 MOVE 启动 shell |
| 路由 | `InputBackendRouter.kt`、`SystemInputManagerApi.kt` | 固定会话后端；注入 false/异常不重放不确定事件 |
| 应用生命周期 | `FloatingBubbleService.kt`、`FloatingWindowStateManager.kt` | 前台目标、捕获/失焦、旋转、方案切换与完整释放 |
| 普通映射 | `EventHandler.kt` → `TouchCoreEventInjector.kt` | 原编辑器映射也走新核心；旧 EventInjectorImpl 保留供构建回退 |
| 多指权威状态 | `domain/touch/TouchSession.kt` | ContactId、Android pointerId、pointerIndex 分开；计划→注入→提交 |
| 输出顺序 | `TouchPipeline.kt`、`FrameworkTouchBackend.kt` | 单 actor，相邻 MOVE 合并不跨边沿；正确 DOWN/POINTER_DOWN/MOVE/POINTER_UP/UP/CANCEL |
| 语义动作 | `GestureControllers.kt`、`MumuActionDispatcher.kt` | Camera、Joystick、Attack 独立共存；宏不清空其他合法触点 |
| 配置/诊断 | `MumuProfile.kt`、`MappingBackup*`、`TouchDebugRegistry.kt`、设置页 | 按应用导入/移除 MuMu、备份 v1/v2、诊断导出 |

新核心默认启用：鼠标静止不自动抬指；首次有效位移建立 camera；越界按该 contact 的 UP→锚点 DOWN→剩余位移执行，绝不是保持按下时直接坐标跳回；进入瞄准和普通攻击不清空整条多指流。WASD 对角归一化、反向按键居中、全松才释放。新路径不使用旧固定 80ms WASD 动画。

原 JSON 是触控映射，不是 gamepad。Alt 切换镜头；M 的宏按 `click_rel:(0.072056,0.106250)` 后 `exit_staring`，不能用 M 图标坐标。Tip 仅元数据。MuMu 方案独立保存并优先于原编辑器配置；在设置移除后恢复使用原有键位。

`sensitivity/50` 线性增益、锚点中心 RangeWindow、短边摇杆半径是本版约定，不是已经逆向证实的 MuMu 精确公式。未知 interval 不直接当毫秒。没有硬编码 MuMu 的分辨率、原始触屏轴或 slot。

RootNativeTouchBackend / uinput 虚拟触屏尚未实现；请先验证 Framework 路线。现存虚拟手柄代码不代表这一版本启用了手柄，gamepad 仍暂停。

## 6. 在物理机重建开发环境

以下版本来自仓库与历史成功构建，不需要先升级 Android Studio、AGP 或 Kotlin：

| 依赖 | 配置 |
|---|---|
| Gradle Wrapper | 8.9，使用仓库 `gradlew` / `gradlew.bat`，不依赖全局 Gradle |
| Gradle 运行 JDK | 17；Java/Kotlin 输出目标仍为 11，勿误改成 17 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / Compose plugin | 2.0.21 |
| KSP | 2.0.21-1.0.27 |
| Hilt | 2.55 |
| Android SDK | compileSdk / targetSdk 35，minSdk 29；安装 platform-tools、platforms;android-35、build-tools;35.0.0 |
| NDK / CMake / C++ | NDK 27.0.12077973、CMake 3.22.1、C++17 |

NDK 27.0.12077973 来自本次清理前读取的 `app/.cxx/Debug/*/arm64-v8a/CMakeCache.txt`，与历史成功构建记录对应。当前 `app/build.gradle.kts` 未显式固定 NDK，物理机优先安装该版本；不要随意把最新 NDK 当作唯一兼容版本。若需在代码中固定版本，先确认能通过四 ABI 构建，并作为单独变更记录。

在物理机配置自己的 `JAVA_HOME`、`ANDROID_HOME`，必要时令 `ANDROID_SDK_ROOT` 与之相同；或写本机 `local.properties` 的 `sdk.dir`。不要同步复用 VM 的旧绝对路径。不要把 SDK/NDK/JDK 装回项目同步目录，也不需要复制虚拟机缓存。

Linux/macOS：在项目根目录且 JDK/SDK 环境已配置后运行：

```bash
java -version
./gradlew --version
./gradlew --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Windows PowerShell：

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleRelease
```

依赖使用 google()、Maven Central、Gradle Plugin Portal，以及只供 libsu 的 JitPack 仓库；若网络失败先检查物理机网络/代理，不要盲目改仓库、锁定版本和业务代码。Release 只有提供签名参数时才签名，默认产物 `app-release-unsigned.apk` 不能直接安装。

对照构建可使用 `-PtouchCore=false` 选择旧引擎，或 `-PtouchMoveWindowMillis=0..16` 修改新核心 MOVE 合并窗口（默认 4ms）。这仍是同一 `.touch` 包名，不能未经核验把不同配置的 APK 都标成同一个版本交给用户。

## 7. 调试签名必须保留，不能把它当缓存删除

旧 APK 的 Debug 证书 SHA-256：

```text
339b630de04878f77646e29fdf29a84b6785fa5a5e0b8f1f2cddbda6937428b0
```

2026-11-01 已用本机 keytool 核对 `/home/sakura/.android/debug.keystore` 与上述证书相同。项目里已保留一份受 Git 忽略的 `.secrets/migration/debug.keystore`，并逐字节核对与原文件一致，目录权限 700、文件权限 600；原文件也保留。该副本供现有同步软件同步到物理机，是旧调试包的签名连续性材料，不是可重建缓存。不要 `git add -f`、公开发布或把私钥内容写到日志。

先确认该文件确实已出现在物理机（同步软件可能单独排除隐藏目录），再复制到物理机 Android 用户目录的 `debug.keystore`。如果物理机已有其他项目的调试密钥，先另存原文件，不能直接覆盖丢失。可改为在本机私有签名配置中指定该旧密钥，但不要提交本机密钥路径/密码。

Linux 默认用户目录为 `~/.android/debug.keystore`；Windows 为 `%USERPROFILE%\.android\debug.keystore`。这是标准 Android 调试密钥：alias `androiddebugkey`，store/key password 均为 `android`；密码只是默认口令，不是用户账号凭据。

构建后用 SDK 自带 `apksigner verify --print-certs` 核对证书，再尝试覆盖升级。新机器自动生成的另一个 Debug 密钥会导致 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；不要以卸载应用作为默认修复，这会丢应用数据。

## 8. 现有 APK 与历史验证

以下三个交付 APK 本次保留，不用重新搬运；以物理机同步到的文件为准：

| 文件 | SHA-256 |
|---|---|
| `artifacts/keysync-touch-core-1.56.apk` | `35c6a164bd8de43d50d80cbd0bef25a17368cefb812f25c77ff6a507b48b001e` |
| `artifacts/keysync-touch-engine-alpha.apk`（1.55） | `1d8e86951f638520b39a910c5cb3f86f481a330814936a41f28d3d187d532e88` |
| `artifacts/keysync-mainline-fix.apk`（历史 .fix 产物） | `1499e5e64eb25fd67dbfe63e63c19cbe0c4c82a3a5fe2247ae870b4247a1d25a` |

注意历史 `.fix` 产物不能仅凭文件名当作严格的旧引擎性能基线；重新做 Legacy A/B 应明确构建参数和 APK 指纹。`.touch` 与 `.fix` / `.gamepad` 包名不同不能互相覆盖。1.56 `.touch` 使用同证书可覆盖 1.55 `.touch`。后续版本延续本分支版本管理，不跟随 gamepad。

最后一次完整验证发生于 2026-10-08：78 项 JVM 测试、0 failures / errors；Lint 0 errors / 77 warnings；Debug 和 Release/R8、四 ABI 成功。2026-11-01 清理前读取现存测试报告再次核对，不能把它写成“今天已重新构建/实机通过”。本次不为文档迁移重新下载整个 SDK。

## 9. 物理机第一轮工作：先复现并采证，不先大改引擎

1. 核对工作树与上述文档、原 JSON、APK、私有 Debug 密钥均已同步；识别真实 OS/SDK/JDK。
2. 恢复 JDK17 / SDK35 / NDK / CMake，核对 Debug 签名，完整运行单测、Lint、JNI、Debug / Release 构建。
3. 用本机 ADB 枚举设备，检查连接和系统授权状态，选定唯一目标后再做实机操作。不要硬编码旧序列号，也不要同时操纵多台设备；Root 提示通过平板的 KSU 正常授权。
4. 先导出用户已有键位配置，核对安装包名和证书，再用覆盖安装；不清数据、不卸载旧包、不擅自安装其他 Root 管理器。
5. 不导入 MuMu，先验收原编辑器：键盘/鼠标正常、短按/长按、鼠标模式拖动和滚轮、目标应用切入切出、捕获/Root 丢失释放。
6. 设置中导入原 JSON，确认实际游戏包名；验证 Alt、WASD 对角/反向、镜头+移动+攻击三指、静止 5 秒镜头持触、连续单向多次重置、M 地图宏及 M 后松开攻击。
7. 验证旋转/尺寸、真实触屏、NPC 对话/奖励界面、快速 Alt/M、断键鼠、重启服务、撤销 Root 后是否无卡指；安全检查设备状态，不能反复通过清配置掩盖问题。
8. 导出设置页 Touch Core 诊断，并收集必要 logcat / dumpsys input / 有界 getevent 观察；先排除输入采集、动作层、队列、注入、游戏上下文中的具体问题再改代码。

需要记录：设备型号/Android版本/屏幕尺寸方向、输入设备名称与节点、Root/Shizuku 模式、APK包名/版本/证书、是否启用 MuMu、灵敏度、MOVE 窗口、复现操作、诊断和日志时间。不要在日志中额外收集账号密码等输入内容。

现有诊断只记录最近 256 个应用内排队、处理、后端提交耗时的 p50/p95/p99。它不包括硬件→Input Capture 的完整时间，更不等于游戏视觉响应。物理机阶段需分别测量可行的端到端时序，不能直接用 Binder 返回快证明延迟已解决。

Framework 长期稳定后再考虑独立 RootNativeTouchBackend / uinput A/B；不恢复 gamepad 开发，不先重写整套 UI，不合并未经核验的上游实验代码。

## 10. 虚拟机清理记录

2026-11-01 已先写入本文、核对历史构建记录和保存 Debug 签名，再执行清理；没有重新下载 SDK、构建 APK 或连接设备。

已删除的可重建内容如下（用户目录均指虚拟机 `/home/sakura`）：

| 范围 | 已删除路径 |
|---|---|
| 项目构建产物和本地缓存 | `app/build/`、`app/.cxx/`、`.gradle/`、`.kotlin/` |
| Android 下载缓存和残留端口元数据 | `~/.android/cache/`、`~/.android/adb.5037` |
| Gradle 的 Android 元数据 | `~/.gradle/android/` |
| KeySync 的旧 Kotlin 构建统计 | `~/.gradle/kotlin-profile/`；删除前逐个确认 58 个 profile 的 PROJECT_PATH 均属于本项目 |
| 仅本项目使用的 Gradle 8.9 分发及通知缓存 | `~/.gradle/wrapper/dists/gradle-8.9-bin/`、`~/.gradle/notifications/8.9/` |

删除前目标磁盘占用合计 **624,910,336 字节，约 596 MiB（625 MB）**；紧接清理时核对的文件系统可用空间增量也是 624,910,336 字节。项目本身由约 466 MiB 降至 154 MiB。源码目录内删除的都是可重建产物；这些删除可能按现有同步规则传播，物理机需要重新生成缓存。

历史专用环境 `~/.cache/keysync-build/`（SDK/NDK/JDK17）、`~/Android/`、`/opt/android-sdk`、`/opt/android-studio`、`/usr/lib/android-sdk` 在本轮开始时已不存在，不计入本次释放空间。未卸载其他项目共用的 SDKMAN Java、系统 Java、Gradle 8.8 及共享依赖缓存，也未删除 Codex 运行时或改动同步软件。

已保留：全部源码和未提交改动、`.git/`、源码中的 `gradle/` Wrapper、参考 JSON、文档、三个 `artifacts/` 交付 APK、原 `~/.android/debug.keystore`、ADB 身份密钥及项目内私有签名副本。清理前后对排除本次文档和可重建目录的 144 个文件做内容清单哈希比对，一致；已跟踪改动的 diff 哈希、分支/HEAD、三个交付 APK 的 SHA-256 也全部一致。没有 commit、push、拉取或覆盖远端。

## 11. 给新会话的启动提示词

> 请先完整读取同步目录根部 MIGRATION.md，再读取 docs/TOUCH_CORE.md 与 PROJECT_MEMORY.md 最后的交接章节。这个工作树包含大量未提交修复，不能 reset/clean 或用远端覆盖。用户现在是在能连接 Android 的物理机上接手，优先恢复构建环境和旧 Debug 签名、检查同步完整性，再做当前 1.56 Framework Touch Core 的实机复现与诊断。暂不开发 gamepad，不引入 uinput 后端，不先大改引擎。源码已经同步，无需重新迁移或 clone。先说明实际分支、未提交状态、构建依赖、可用设备与签名情况，再按实机证据推进。

## 12. 本次物理机核验（2026-11-01）

- 已确认物理机为 Linux x86_64；工作树仍在 `codex/touch-engine-rewrite` / `f1461761f8cb0a90e0c11f22b2a9e899b6e6ca04`，保留 55 个既有工作区状态项，没有 reset、clean、commit、push 或拉取远端。
- 已恢复用户目录 JDK 17.0.21（`/home/sakura/.local/jdks/temurin-17`）、Gradle 8.9、SDK `/home/sakura/Android/Sdk` 的 Platform 35、Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1 和 platform-tools 37.0.1。
- 使用 JDK 17 运行 `./gradlew --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleRelease` 成功：78 项 JVM 测试通过，0 failures/errors；Lint 0 errors、77 warnings；Release/R8 和四 ABI JNI 均通过。构建期间按需安装了 Build Tools 34，未改项目配置。
- `.secrets/migration/debug.keystore` 的证书仍为 `339b630de04878f77646e29fdf29a84b6785fa5a5e0b8f1f2cddbda6937428b0`；物理机原有 `~/.android/debug.keystore` 是另一把密钥，未覆盖。用迁移密钥显式签名的 Debug APK 已核对为包名 `com.devoid.keysync.touch`、versionCode 20、`1.56-touch-core-alpha-debug`，可用于同证书覆盖安装验证。
- 本节初次核验时 ADB 尚未接入设备；随后平板接入后的修复与实际验证见第 13 节。

## 13. 平板接入与 MuMu 显示修复（2026-11-01）

- 已接入唯一平板 `0D070400044C1540`，型号 `25053RP5CC`、Android 16、HyperOS `OS3.0.304.0.WOTCNXM`；物理屏幕 2136×3200，本轮游戏横屏逻辑尺寸 3200×2136。目标游戏 `com.hypergryph.endfield`。
- 用户要求非必要禁止 root 操作。本轮只通过普通 ADB 操作、`run-as com.devoid.keysync.touch` 读取自身调试数据；没有执行 `adb root`、`su` 或 root shell。应用沿用用户既有 Root 输入模式，通过自身 RootService 捕获键鼠。
- 已备份偏好数据并确认 MuMu 原文完整存在、旧编辑器键位为 `[]`。虚拟按键不可见的原因是渲染层只读取旧编辑器列表，没有显示独立的 MuMu 方案。
- 新增 `ui/overlay/MumuOverlay.kt`，在 MuMu 方案生效时显示导入键位、WASD、瞄准、宏和 Tip。图标位置、说明、可见性与缩放在 codec 边界解析为类型化数据；执行坐标保持独立，M 仍按宏的 `click_rel` 执行。Tip 只显示提示，不注册输入动作。
- 采用浅色文字与深色背景，提高默认透明度下的可读性；显示中文鼠标按键名称、Alt/Shift 等可读标签。顶部显示当前鼠标／瞄准模式及实际切换键，并随 dispatcher 状态更新。MuMu 生效时不显示旧编辑器标记，并隐藏不生效的“添加旧组件”入口。
- 实际输入为私有连接的 `Xiaomi Keyboard`（`event8`）和外置蓝牙 `Mi PTMS3 Mouse`（本轮 `event15`）。初始缺鼠标时用户确认物理鼠标未开启；开启后已有外置鼠标匹配规则即可捕获。未保留本轮一度尝试的内置鼠标白名单扩展。鼠标断开曾产生 `-19 / ENODEV`，映射随之停止；重新连接并启动映射后恢复捕获。
- 已用旧 Debug 证书覆盖安装 `.touch` 包，保留配置，未卸载或清数据。用户实际反馈：**按 Alt 后显示瞄准模式，移动物理鼠标可以转动游戏视角**。实机截图也确认导入标记、说明和模式提示均已显示，瞄准后软件光标隐藏。
- 本轮相关测试：`MumuProfileTest` 9 项、`MappingBackupCodecTest` 3 项，全部通过；Lint 0 errors / 77 warnings（另 6 条信息），Debug、Release/R8 和四 ABI JNI 构建通过。早先 78 项全量测试属于第 12 节的环境恢复核验；本轮采用上述 12 项相关测试，不能合称全量实机通过。
- 仍未完成真实游戏中的镜头＋移动＋攻击组合、技能长按、地图宏与对话恢复等完整验收，不能据此宣称所有 bug 已修复。gamepad、uinput 触屏后端继续暂停。原有 `artifacts/` 历史 APK 不覆盖；本轮修复包另存为 `artifacts/keysync-touch-core-1.56-mumu-overlay.apk`。
- 最终修复包 SHA-256：`c09e8beeb2329170dd082f3c286a00621d2f59457889b6c03265e56eea767e2d`；仍为 `.touch` / versionCode 20 / `1.56-touch-core-alpha-debug`，已核对旧 Debug 证书并覆盖安装。历史原版 1.56 指纹仍是 `35c6a164bd8de43d50d80cbd0bef25a17368cefb812f25c77ff6a507b48b001e`，不要混淆两者。

## 14. WASD 校准、捕获交接修复与无线调试（2026-11-02，1.58.1）

- 原 MuMu 移动圆心约 (483,1630)，本平板当前游戏轮盘圆心约 (444,1754)。保留原方案，导入独立 `WASD-3200x2136` 校准方案，仅调整 Swipehold 的触控中心和显示中心；不改通用方向算法。文件 `artifacts/com.hypergryph.endfield-平板轮盘校准-3200x2136.json`，SHA-256 `3a4f70c71200fe2d8a6dabb7c0bc60462b2194144256020c8d00a35cc43f339a`。
- 用户随后反馈短按变成持续输入。旧版实机日志显示 S DOWN 进入 Android 后约 20ms 才被 Root 独占，S UP 未进入 Android；InputReader 留有一个按下键，系统持续重复 S。暂停映射后用户短按并松开 S，已确认系统残留清零。
- `RootEvdevCapture.cpp` 改为先打开允许的节点，读取 `EVIOCGKEY` 等待全部键鼠按钮释放，清掉交接前已发给 Android 的事件，再统一 `EVIOCGRAB`；交接期间出现按下时先解除独占，让完整按下/松开经过 Android 后再捕获。未改白名单、方向映射或滚轮归一化，未增加任意超时抬指。
- 本次修复已通过 17 项相关 JVM 测试（Gesture 5、MuMu 9、设备分类 2、捕获恢复 1）、Lint 0 errors / 77 warnings、Debug / Release/R8 与四 ABI JNI 构建；这些是此前构建结果，本轮无线连接恢复未重复构建。APK `artifacts/keysync-touch-core-1.58.1-capture-handoff.apk` 的 SHA-256 为 `aaf42b47a05d8e7405cb3dfde2758d6d2b4d14e0b75914282bac2da4150f67ce`，沿用迁移 Debug 证书覆盖安装，偏好保留。
- 本轮无线连接恢复时重新核对已安装 `.touch` / 24 / `1.58.1-touch-core-alpha-debug`，系统按键残留为 0。11:07:09 日志确认捕获 `event15`（Xiaomi Mi USB Receiver Mouse）、`event19`（同接收器键盘节点）和 `event8`（Xiaomi Keyboard），无需扩展设备白名单。校准方案仍选中，原 MuMu 方案保留；已进入游戏可走动场景。
- 用户随后明确确认：**松键即停，四方向正常**。此前“按住 W 启动”的测试未出现等待释放日志，不能声称交接等待分支已由实机覆盖。普通 ADB 注入按键不能替代实体 evdev 捕获验证。
- 本轮仅用普通无线 ADB 与应用自身 run-as，无 `adb root` / `su` / root shell。当前分支本地基线仍为 `8d9de43`；滚轮、多方案、校准和交接修复保留在未提交工作区，未推送或发布。

## 15. MuMu 整套布局校准（2026-11-02，1.59）

- 用户补充原方案来自 1920×1080，且不止轮盘、其他键位也错位。引擎原来直接将归一化点分别乘当前宽高；16:9 与平板近 3:2 的游戏 HUD 布局不同。没有加入未经证实的全局缩放/锚定公式，也未更改通用摇杆方向；实现当前游戏、当前方案的直接触点校准。
- 导入方案运行时点开悬浮球：实际触点用十字标出，拖动普通按键、攻击、轮盘、镜头锚点时同时移动显示标记；Tip/宏图标仅修改显示，宏按下/松开动作中的每一个 click_rel 单独提供触点。左右箭头选择重叠目标，保存写入当前 JSON 并重建 dispatcher，取消丢弃本次调整。编辑时沿用原有暂停捕获和释放触点流程。
- 新 `MumuLayoutEditor.kt` / `MumuLayoutCalibration.kt`；codec 为图标保留源 keymaps 索引，存储只替换指定 ID 的 JSON，备份 v3 原样保留这些调整。所有其他策略、名称、ID、选择及其他游戏/方案保持不变。显示和注入统一使用 width−1 / height−1 坐标范围。
- 16 项相关测试通过（MuMu 11、方案存储 5）；Lint 0 errors / 77 warnings / 6 informational，Debug、Release/R8、四 ABI JNI 构建成功。最终 APK `artifacts/keysync-touch-core-1.59-layout-calibration.apk`，SHA-256 `f0f0625ca97b579ecd610df340afdbf42526f15059bf4276e6c888e245899bad`，同证书覆盖安装，安装前后偏好逐字节相同。
- 用户先在新界面调整了十余个位置；按其明确授权由助手继续，已保存并保留这些调整。随后实测临时拖动 R 再取消，方案库与选择完全不变；再将攻击调整为约 (2674.65,1764.87)、Tab 锁定为 (2150.77,1969.82)，保存后从 DataStore 确认实际坐标和显示同步。原 MuMu 方案、WASD 圆心 (444,1754)、info/param 及所有无关偏好保持不变。已导出 `artifacts/com.hypergryph.endfield-平板布局校准-3200x2136.json`。
- 当前场景可见 F1–F4 队伍、R、跳跃、闪避、攻击、锁定等按钮；截图确认标记基本重合。用户已明确确认实体 **F1–F4 切人、Space 跳跃、Alt 瞄准后左键攻击均正常**。技能、连携等未显示的按钮不能据当前画面验收。校准副本名称仍为 `WASD-3200x2136`，其中已包含其他键位的调整。
- 实测期间 12:18:12 出现过一次 `InputManager rejected or timed out`；旧流按既有错误处理停止并清零触点，之后从 KeySync 重新启动恢复。未加入重放/自动重试，原因尚未定位，不能记作已解决。后续保存和恢复捕获日志正常。
- 全程普通无线 ADB / 应用 run-as，无 root shell；所有后续源码与文档仍未提交、推送或发布。

## 16. 明日方舟的光标与按键联合宏（2026-11-02，1.60）

- 用户提供 `com.hypergryph.arknights-默认操作方案.json` 和两张 MuMu 说明截图。原文为 7 个 Click、8 个 Macro、1 个 Observe；说明推荐 1600×900、144+ 帧。原文件不改，另存测试 fixture 到 `参考文档/`。平板当前仍为 3200×2136，不能据 16:9 来源声称全部触点已适配。
- 增加当前光标点击、显式 sleep、左键按住/释放、Linux scan 11/158、从光标开始的 curve_rel、首点等待、终点保持和 release_all。Windows VK 只负责物理输入绑定；宏 scan 11 调用已绑定的数字 0 暂停落点，scan 158 注入 Android BACK。触摸和 KeyEvent 在同一 actor 中顺序输出，等待留在宏协程，不阻塞其他按键；同键 release 程序可取消尚在等待的 press 程序，reset 阻止迟到 DOWN。
- Macro release_all 发送正常 UP，保证从拖动进入部署朝向；失焦/停止/失败仍发送 CANCEL。按键由会话记录并在 reset/recover 时 UP；RootService 在停止捕获、客户端死亡、原生捕获失败时也清理按键。新 IPC 使用独立 transaction，未改变原 MotionEvent 协议。
- Observe 按住 Alt 拖动、松开抬指并恢复原光标；与终末地 Staring 的 Alt 切换分开。显示提示和校准锚点同时支持。灵敏度仍用 KeySync 的 x/y÷50，未宣称逐项复刻 MuMu input_policy。
- 当前文件含显式等待/曲线的宏不再额外插入 24ms，原 click-only 宏保留 24ms。曲线终点 `(2.714441,4.826255)` 按原文保留为超屏坐标；until_release_cmd 当前解释为保持触点并继续执行。右键顺序由 JVM 测试覆盖，但该曲线语义和游戏零帧效果未得到实机确认。用户说明 2026 年游戏更新已支持暂停部署，旧脚本早于该功能，本次不再强制验证划火柴；原宏保留。
- 40 项相关 JVM 测试通过（Touch Session 6、Pipeline 3、Gesture 5、Wheel 4、MuMu 11、明日方舟 6、方案存储 5）；Lint 0 errors / 77 warnings，Debug、Release/R8、四 ABI JNI 成功。最终包 `artifacts/keysync-touch-core-1.60-arknights-macros.apk`，SHA-256 `ffa04c53505112ef92baea30a150140eb13013972d02516cfac02b309bfbf4a8`，证书仍为原迁移 Debug 证书。已覆盖安装，安装前后偏好逐字节相同；旧 RootService 随应用正常退出，新进程重新捕获 event8/15/19，无 root shell。
- 从正常文件选择器导入 `arknights-mumu-1600x900`，再导入并选中 `arknights-tablet-3200x2136`。已对照 DataStore 确认原文一致、终末地库完全不变。演习中实测看到技能/撤退按钮比原方案偏下；校准副本只改 Q/2 的触点和图标、A/D 宏的最终固定点击：撤退 (1555,865)、技能 (2300,1465)。原版和肉鸽 W/1 坐标保留。校准文件 `artifacts/com.hypergryph.arknights-平板布局校准-3200x2136.json`，SHA-256 `91de7e772083557863c55f3a7fdaca82beb1c91b3a2d5455ff87ccd3a6e56b69`。
- 用户已在演习中使用实体键鼠确认 **C 点击、F 时停、D 一键开技能、A 一键撤退均正常**。普通 ADB 曾在暂停状态正常部署“望”，只用于准备演习和查看触点，不能冒充实体捕获验证。用户随后对 S 的验收步骤回复“正确”：暂停中指向干员，按住 S 选中并保持时停，松开后恢复、无卡输入。E 对轴、生息演算 Alt 实际移动视角及肉鸽 W/1 尚待相应验证。
- 当前源码仍在 `codex/touch-engine-rewrite` / 本地基线 `8d9de43` 后的未提交工作区；未提交、推送或发布。保留全部此前修改和两游戏独立方案。

## 17. 正式版发布与主分支合并（2026-11-02）

- 用户已明确授权将当前源码与验证状态推送至 `YoruSakura/keysync`，直接合并到 `main` 并整理为一个正式发布包；不再受较早“尚未要求推送/发布”记录限制。
- 正式版本 1.60 / 27，应用 ID `com.devoid.keysync.touch`，同签名 Release/R8 包。完整单测 96 项通过，Lint 0 errors / 77 warnings，四 ABI 构建通过。当前操作/待确认项完整列于 `docs/VALIDATION.md`，保留已确认的 C/F/D/A/S 和终末地操作，不把尚未测试的专用场景写成通过。
- README 更新为正式下载入口、多游戏方案及当前验证表。GitHub Actions 改为测试、Lint、Release 构建校验，移除自动发布重复 APK 的流程；正式包经本地签名核验后统一上传。
- 原始 JSON 与两游戏最新平板校准 JSON 纳入 `参考文档/`；APK、旧包归档、用户根部附件、私钥及本机配置保持本地，不进入 Git。旧预发布包先归档再从 GitHub Releases 移除，历史标签与提交保留。

- 正式发布包：`artifacts/KeySyncFix-v1.60.apk`，5,990,390 字节，SHA-256 `f9172b4b99303efd2982db9dc1f075da3ff6d4f2ac8bfdde39a7bb6ab8751698`。源码提交 `c781887`，正式发布入口 `https://github.com/YoruSakura/keysync/releases/tag/v1.60`；历史 1.41 预发布已归档到本地 `artifacts/github-release-archive/`。
