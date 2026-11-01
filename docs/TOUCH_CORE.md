# Touch Core / Framework alpha 1.56

当前工作分支 `codex/touch-engine-rewrite`。保留此前未提交修复和 gamepad 文件；gamepad 路线继续暂停。没有改动稳定的 `RootEvdevCapture.cpp`、设备白名单、独占和滚轮归一化。

## 仓库现状与接入点（Phase 0）

- Root：evdev → RootInputService 的单向 Binder 回调 → RootInputConnection 的输入 HandlerThread。
- Shizuku：现有 PointerCapture overlay → FloatingWindowStateManager。
- 原有普通键位：FloatingWindowStateManager → EventHandler → EventInjector 兼容接口。
- 输出权限：InputBackendRouter 固定 Root 或 Shizuku，仍由系统设置选择。没有热切换、没有每 MOVE 启动 shell 或 app_process。
- 长驻 RootService 和缓存的 Shizuku/system_server Binder 可直接复用；没有引入另一个 MAA 进程。
- 旧 EventInjectorImpl/EventManagerImpl 仍保留，使用 `-PtouchCore=false` 编译回退。Root 的旧动画路径仍回到主线程。默认实验 APK 使用新核心。

## 新模块与实现阶段

| 阶段 | 模块 | 已实现 / 限制 |
|---|---|---|
| 1 | domain/touch/TouchSession.kt | 逻辑 ContactId、0–9 pointerId 分配、pointerIndex 动态计算，计划→注入→提交、Cancel/Reset、失败封锁 |
| 1–2 | TouchPipeline.kt | 单 actor、有界有序队列，只合并相邻 MOVE，不跨 DOWN/UP/重置/宏边界；默认 4ms 合并窗口 |
| 2 | FrameworkTouchBackend.kt | 正确的 DOWN/POINTER_DOWN/MOVE/POINTER_UP/UP/CANCEL，全量 pointer 集合、稳定 downTime、displayId、touchscreen/finger |
| 2 | TouchCoreEventInjector.kt | 兼容原编辑器配置；去掉新路径的随机落点和 80ms ValueAnimator；滚轮延时在调度器，不阻塞输出 actor |
| 3 | MumuProfile.kt | 实际 JSON 的 Click、Fire、Swipehold、Staring、Macro、Tip；严格拒绝未知动作，保存完整策略元数据 |
| 4 | GestureControllers.kt | 持续镜头触点、分数位移、独立 X/Y 范围、UP→锚点 DOWN→剩余位移；WASD 同 contact、对角归一化、反向键居中 |
| 3–4 | MumuActionDispatcher.kt | Click/Fire 跟随物理按住；Alt 切换、M 严格按宏 click_rel→exit_staring，宏只释放自己的 contact |
| 5 | TouchDebugRegistry.kt | 队列/处理/后端提交 p50/p95/p99、触点数、重置次数、错误和合并计数；设置页导出 JSON |
| 6 | RootNativeTouchBackend | 未实现。必须先完成 Framework 实机验收，不能以创建成功推断 Android 正确识别 uinput 触屏 |

Integration 文件：InputBackendRouter、SystemInputManagerApi、FloatingWindowStateManager、EventHandler、RootInputConnection、SoftwareCursorController。UI / 持久化文件：MainActivity、MainActivityViewModel、SettingsScreen、DataStoreManager、MappingBackup / MappingBackupStore、strings.xml、app/build.gradle.kts。

## 使用与迁移

1. 安装 `artifacts/keysync-touch-core-1.56.apk`。包名仍为 `com.devoid.keysync.touch`、versionCode=20，可覆盖同签名 1.55 实验包；不能覆盖 `.fix` 或 `.gamepad`。
2. 不导入 MuMu 时，原编辑器的普通映射直接走新 Touch Core，无需重新配置。
3. 设置 → 导入 MuMu 触控方案 → 选择 `参考文档/com.hypergryph.endfield-默认操作方案.json`，确认实际安装游戏的包名。
4. MuMu 方案单独绑定该应用，优先于原编辑器配置；原有配置不覆盖。生效时悬浮层显示导入按键、说明、WASD、宏与 Tip，顶部显示当前模式和切换键；Tip 仅提示，不新增动作。图标位置与实际触控坐标分别解析。不支持在旧编辑器里调整 MuMu 方案，导入方案生效时隐藏添加旧组件入口。设置中“移除 MuMu 方案”后重新启动目标应用即可返回原配置。
5. 从 KeySync 内启动目标应用。该 JSON 的 Alt 切换镜头，WASD 移动，左键攻击，M 点击地图入口后退出镜头。鼠标模式左键在光标处点击，滚轮在光标处滑动；瞄准模式滚轮不滑动。
6. 导入/移除/恢复方案会停止当前映射。离开目标应用、输入断开、后端错误、关闭映射都取消触点；Root 丢失不尝试 Shizuku。
7. 无 MuMu 的备份仍输出 schema v1；含 MuMu 的备份输出 v2，新包同时接受 v1/v2。旧 1.54/1.55 包不能读取 v2，请保留迁移前 v1 备份。

## 明确的第一版策略（不是 MuMu 内部公式）

- 进入镜头后首次非零鼠标位移建立 DOWN，静止保持；普通攻击不隐式释放 camera。退出只释放 camera，其他合法 contact 保持。
- Range_Window 用“锚点为中心的归一化宽高”与实际显示范围求交。范围几何含义尚未完整实测，这只是本版可调整的实现策略。
- Framework 坐标为当前 display 的逻辑像素；按实际 overlay 尺寸缩放。没有照搬 MuMu `rawX=(1-y)*1080` / `rawY=x*1920` 或 slot 编号。
- Swipehold 半径以显示短边为基准，不猜 first/subsequent policy 的时间单位；新普通 WASD 立即移动，不保留作者固定 80ms 动画。
- 本版线性 camera gain = `sensitivity_x/y / 50`，再乘现有软件灵敏度。这是我们自己的约定，不能证明 MuMu 100 恰好为 50 的两倍。
- 宏 click 保持 24ms，滚轮四次 MOVE 各间隔 12ms；这些是 KeySync 明确设定，非 JSON interval 的反推。
- 默认 MOVE 合并窗口 4ms，可通过 `-PtouchMoveWindowMillis=0..16` 构建 A/B 包；这不是固定 250Hz 物理输入，也不对没有位移的触点发送 MOVE。
- 最多十指；一次样本最多 128 次镜头边界穿越，异常大位移会释放镜头并进入 ERROR_RECOVERY，须重新切换镜头，不进行无限循环。
- 第一版支持 Fire 按住，保留 Fire 标识/原始 input_policy，但不支持 `need_fire_and_drag=true`、自动连发或未知宏指令；导入时明确拒绝未实现操作。
- 后端 DOWN/UP/CANCEL 使用 WAIT_FOR_RESULT，MOVE 使用 ASYNC，保守等待接受、不等待每帧处理完成。未来应实测 WAIT_FOR_FINISH/ASYNC edge 的兼容性，当前不宣称一种模式始终最优。

## 恢复与性能观察

注入 `false`/异常、队列溢出都封锁旧流并请求系统取消，不重放不确定事件。AOSP 的 [InputManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/input/InputManagerService.java) 对 TIMED_OUT 和 FAILED 都可能返回 false，因此移除了旧 SystemInputManagerApi 的 false 重试。

设置 → 导出 Touch Core 诊断。`queueMillisP50P95P99` 是触控命令排队；`processingMillisP50P95P99` 包括规划/恢复等整个处理；`backendMillisP50P95P99` 是后端提交（含 MotionEvent 编码和 Binder 返回）。每组仅最近 256 样本。文件不含按键历史或游戏画面。

截至本轮设备验证，仍未采集内核物理事件时间到 Android 目标窗口接收或游戏视觉响应的完整时序。诊断统计不能证明端到端低延迟，更不能保证 0 冲突。

设计参考：[MaaTouch 长连接 d/m/u/c 协议](https://github.com/MaaAssistantArknights/MaaTouch)、[MAA-Meow 输入工具](https://github.com/Aliothmoon/MAA-Meow/blob/main/app/src/main/java/com/aliothmoon/maameow/maa/InputControlUtils.java)。新状态机是本仓库独立实现，不复制服务/游戏逻辑。

## 实机验收 / 下一阶段

2026-11-01 已在 Android 16 小米平板通过普通 ADB 覆盖安装 MuMu 显示修复包，并确认键鼠同时捕获、导入标记显示及模式提示更新。用户已实测 Alt 切换瞄准和物理鼠标转视角成功。当前修复包见 `artifacts/keysync-touch-core-1.56-mumu-overlay.apk`；其余组合和恢复行为尚未完整验收，详细证据见 `MIGRATION.md` 第 13 节。

本次虚拟环境验证：`./gradlew --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleRelease` 成功；78 项 JVM 测试、0 failures / 0 errors，Lint 0 errors / 77 warnings，Release/R8 成功。Debug APK 已核验 `.touch` / versionCode 20 / 同一 Debug 证书（SHA-256 `339b630de04878f77646e29fdf29a84b6785fa5a5e0b8f1f2cddbda6937428b0`），保留四 ABI Root JNI 库。没有进行设备连接、安装或游戏实测。

- 静止 5 秒镜头保持；连续单向转动多次重置，没有跳回锚点的大幅游戏视角跳变。
- W+D、松一方向、W+S 居中、全松归零；镜头+WASD+攻击三指并存。
- Alt/M 快切；M 点的是实际宏坐标，不是图标坐标；M 后松开仍按住的攻击不会卡指。
- 鼠标模式滚轮/拖动；快速重复滚轮不阻塞其他按键，停止映射立即释放滑动。
- 分别在 Root 和用户手动选择的 Shizuku 下检查旋转、尺寸、长按、技能、窗口切换、真实触屏与游戏 UI 丢弃触控流后的恢复。
- 撤销 Root、拔键鼠、杀 RootService/应用、重新启动会话：触点必须清零，无自动切换 ADB。
- 比较旧 1.55 与 1.56 的镜头/普通键响应和诊断尾延迟，再决定合并窗口与注入模式。JVM 的 125/500/1000 样本测试只是输入突发压力，不是真实 Hz 延迟测试。
- Framework 长期实机稳定后，再研究独立的 RootNativeTouchBackend，验证 uinput 触屏分类、viewport、旋转、SELinux 和完整多指能力，复用同一 Gesture Core。
