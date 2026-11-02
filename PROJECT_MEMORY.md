# KeySync Fix 项目记忆

> 更新日期：2026-11-02（Asia/Shanghai）
> 用途：为后续 Codex session 保留项目背景、已完成工作、本地未提交状态和待验证事项。

正式版发布补记（2026-11-02）：用户已确认当前 fork 稳定，授权直接合并、推送 GitHub 并只保留一个正式包。本轮版本为 **1.60 / versionCode 27**，保留包名和迁移签名，使用 Release/R8；此前“未授权推送／仅本地／实验版”的段落均为历史。发布和验证状态统一见 [docs/VALIDATION.md](docs/VALIDATION.md)。

> **最新：2026-11-02 已安装 1.60 / versionCode 26，开始支持明日方舟的光标联合宏与 Observe。当前选择 `arknights-tablet-3200x2136`；终末地 `WASD-3200x2136` 保留。用户已确认 C、F、D、A、S 正常；右键划火柴按旧方案保留，不再作为必测。具体见本文末尾和 MIGRATION.md 第 16 节。下方版本记录为历史。**

> **当前交接入口：[MIGRATION.md](MIGRATION.md)。项目已转向物理机，当前平板安装 1.59，使用 2.4G 鼠标和无线 ADB。用户已确认松键即停、WASD 四方向正常；最新版本支持整套 MuMu 触点校准，详见本文末尾。下方早期路径、分支、版本和“无法连接设备”的说明保留作历史。**

## 项目目标

本项目是 Android 键鼠到虚拟触控映射工具 [KeySync](https://github.com/aka-munan/keysync) 的修复 fork，主要用于《明日方舟：终末地》平板键鼠操作。用户的磁吸键盘和鼠标需要同时支持：

- WASD 虚拟左摇杆。
- 鼠标移动操作镜头（瞄准模式）。
- `1`～`4`、`Esc` 等游戏必需按键。
- 鼠标左/中/右键可作为独立映射键，未映射时仍保留普通点击。
- 普通映射按键按下多久，虚拟手指就持续多久，从而在同一游戏按键上同时支持点按技能和长按大招。

用户要求：修复先在自己的 fork 中使用，不向上游提交 PR；许可证跟随上游 Apache License 2.0。

## 仓库与 Git 状态

- 本地路径：`/home/sakura/本地项目/KeySyncFix`
- 当前分支：`fix/input-keycode-collisions`
- 当前 HEAD：`f146176 Fix input recovery and preserve key holds`
- fork remote：`origin = https://github.com/YoruSakura/keysync.git`
- 上游 remote：`upstream = https://github.com/aka-munan/keysync.git`
- `origin/main` 和 `origin/fix/input-keycode-collisions` 当前都指向 `f146176`。
- 已有发布标签：`v1.41-beta-fix.1`（指向 `24c2a20`）。
- 用户之前要求仓库公开；README 中的公开下载地址为 `https://github.com/YoruSakura/keysync/releases/tag/v1.41-beta-fix.1`。
- **当前工作区很脏，包含多轮用户实机测试后的未提交修复，严禁 reset、checkout 或覆盖。**
- 最新本地修复尚未 commit、push 或发布。
- 本文件 `PROJECT_MEMORY.md` 也是本次 session 新建的未跟踪文件，后续提交修复时可一并纳入版本管理。

## 已提交并推送的主要工作

1. `9b65fd2 Fix input keycode collisions`
   - 将 WASD 方向状态、普通实体键与可取消按键映射拆分存储。
   - 修复 WASD 位掩码 `1/2/4/8` 与 Android `Back`、数字键等 keycode 冲突导致的崩溃。
   - 保留《终末地》所需的 `1`～`4`、`Esc` 与 WASD 同时操作。

2. `5fce8b5 Add Chinese localization and language settings`
   - 配置界面新增语言选项：跟随系统、简体中文、英文。
   - 配置页及相关操作文案已汉化。
   - Debug 修复版使用 `com.devoid.keysync.fix`，可与原版共存。

3. `aa64cb2 Restore input capture and support mouse key mappings`
   - 支持将鼠标按键直接绑定为单个映射键，包括右键闪避。
   - 未绑定鼠标按键时，保留普通光标点击和开火模式。
   - 尝试恢复触屏后丢失的键鼠输入捕获。

4. `24c2a20 Fix aim toggle multi-touch desync`
   - 重构多点触控事件顺序、pointer ID 管理、释放和取消。
   - 修复频繁切换瞄准模式后 WASD 与镜头不能并行的一部分问题。

5. `c1560ea Rewrite README for the fix release`
   - README 仅说明 fork 的修复/新增功能，原有功能引导至上游。

6. `f146176 Fix input recovery and preserve key holds`
   - 强化悬浮输入捕获丢失后的恢复逻辑。
   - 普通按键映射改为完整跟随实体按下/抬起生命周期：短按是点按，长按是长按，没有额外开关。
   - 增加捕获恢复和长按回归测试。

## 当前本地未提交修复

### 1. 跨游戏配置和旧触控流清理

用户复现过：从 KeySync 启动游戏 A，退出 A 后启动 B，却继续使用 A 的配置；划掉 KeySync 后再给 B 加载配置，又可能令 B 的映射、触屏和鼠标全部失效。

当前本地处理：

- 每次选择应用都向已运行的 `FloatingBubbleService` 重新发送 package name，不再因服务已运行而跳过。
- `FloatingWindowStateManager.loadButtonsConfig()` 使用 generation 防止异步加载旧配置回写覆盖新配置。
- 切换时立即清空旧映射，然后再加载新配置。
- 通过 Shizuku 调用 `IInputManager.cancelCurrentTouch()` 取消上一进程遗留的系统触控流；无法调用时回退到本地 `ACTION_CANCEL`。

### 2. 软件光标高延迟/卡顿

用户报告 KeySync 软件光标明显比 Android 系统光标延迟，严重时会“闪现”。

当前本地处理：

- `CapturedPointerMotion.kt` 累加 Android 批处理 `MotionEvent` 的全部 historical relative deltas，避免丢失中间鼠标位移。
- `SoftwareCursorController.kt` 使逻辑光标位置立即更新，视觉状态按 `Choreographer` 每帧最多提交一次。
- Compose 光标使用 `graphicsLayer` 位移，避免每个鼠标事件导致全屏重组/重排。
- 点击使用最新逻辑位置，不等待下一次画面渲染。
- 未按下鼠标键时，不再生成无意义的虚拟 pointer move。

### 3. 触屏/窗口焦点丢失后的捕获恢复

- 新增 `PointerCaptureFrameLayout.kt`，通过 `onPointerCaptureChange()` 即时感知捕获丢失/恢复，不只依赖轮询。
- 触屏导致捕获中断时清理短暂按键/pointer 状态；捕获稳定后再恢复逻辑瞄准模式。
- 进入瞄准模式时会重建整条合成多点触控流，并重放仍在实体按下的 WASD/普通按键。

### 4. NPC 对话/奖励界面后 WASD 与镜头同时失效

用户已稳定复现：

1. 在瞄准模式下与 NPC 交互，游戏进入强制视角/对话。
2. 对话结束出现“点击任意位置离开”的奖励界面，仍在瞄准模式下点击关闭。
3. 回到战斗后 WASD 和鼠标镜头都失效；再按中键后两者也无法并行。

根因判断：旧实现从开启瞄准到关闭始终按住一根“镜头手指”。游戏在对话/奖励 UI 切换时会作废这根触控，但 KeySync 仍认为它有效；之后 WASD 加入这条过期多点触控流时就会互相污染。

当前本地处理：

- 瞄准模式变为逻辑状态与实际 camera pointer 分离。
- 鼠标开始移动时才创建 camera pointer；停止移动 100ms 后自动抬起，但不退出瞄准模式、不显示光标。
- 瞄准状态下按下鼠标键前，主动释放旧 camera pointer；这为关闭对话/奖励 UI 建立了触控边界。
- 下一次鼠标移动创建全新 camera pointer，WASD 可作为独立 pointer 继续加入。
- 已添加“瞄准移动 → 鼠标点击关闭对话/奖励 → WASD → 再次移动镜头”的回归测试。

### 5. 从 KeySync 内启动目标应用一直黑屏

最新用户报告：在 KeySync 内点击启动需要映射的其他应用，目标应用可能停在黑屏，无法加载。

根因判断：目标 Activity 异步启动时，KeySync 全屏可聚焦 overlay 立即开始抢回窗口焦点/鼠标捕获，可在游戏或硬件加速 Activity 创建首个渲染表面时打断它。同时强制 `FLAG_ACTIVITY_REORDER_TO_FRONT` 也可能复用已失效的旧 Activity surface。

当前本地处理：

- 先向悬浮服务发送目标 package，让已运行的 overlay 先释放焦点。
- 延迟 250ms 再调用目标应用的 launch intent，给同进程服务留出处理时间。
- 不再覆盖 launcher 提供的 flags，只补充 `FLAG_ACTIVITY_NEW_TASK`；移除强制 `REORDER_TO_FRONT`。
- 目标应用启动后的 2500ms 内，全屏映射 overlay 保持 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`，允许目标窗口稳定绘制。
- 2500ms 后自动恢复 overlay 可聚焦状态和键鼠捕获，不需要点击悬浮窗。
- 这条修复已经编译、单测和 Lint 验证，但当时没有 ADB 设备，**尚需用户实机确认黑屏是否消失**。

### 6. KSU Root 原生输入后端（未提交）

用户的平板已通过 KSU 越狱模式/CVE 临时提权，可以向 App 正常授予 root。当前本地新增 Root 后端：

- 使用 libsu 6.0.0 启动与 App 生命周期绑定的 `RootService`，不使用常驻无主 daemon。
- Root 可用时，由 RootService 直接调用系统 `IInputManager.injectInputEvent()`；会验证 root UID 和 App 调用者 UID，再清除 Binder 调用身份。
- 保留 Shizuku 输入注入供无 Root 设备手动选择；同一条多点触控流固定在同一后端，失败时先取消当前流，不会把结果不确定的 `ACTION_DOWN` 在另一后端重放。
- 注入失败后会冻结旧触控流，等待 `EventManager` 与 `EventHandler` 本地状态按队列顺序清空，再接收新输入；不再把 Root 永久、静默降级到可能未授权的 Shizuku。
- App 把 Android `InputDevice.isExternal` 判定出的键鼠名称/vendor/product 白名单传给 RootService；C++/JNI 只会在 `/dev/input/event*` 中独占匹配该白名单且具备完整键盘或鼠标特征的节点。
- 明确排除 `INPUT_PROP_DIRECT`/`ABS_MT_POSITION_*` 触屏设备；只有键盘和鼠标都能成功 `EVIOCGRAB` 时才关闭旧的悬浮窗捕获。
- 键盘或鼠标任一类别断开、读取失败或无法加入 epoll 时，会立即退出原生捕获并释放剩余设备。
- 配置悬浮窗展开、目标 App 启动宽限、Root 服务或客户端死亡时，会停止原生线程、关闭 fd 并释放 `EVIOCGRAB`。
- Linux 扫描码通过 Android 自带 `Generic.kl` 转换成 Android keycode，并为 `Esc`/`1`～`4`/WASD 保留最小回退表。
- 高频鼠标移动使用有序 Binder 队列，只合并相邻移动，不跨越鼠标按键边界；原生层在同一 evdev 帧遇到鼠标按键前会先冲刷此前的相对位移，避免点击和拖拽释放落在旧坐标。
- 不使用 `setenforce 0`、不修改全局 `/dev/input` 权限、不向实体触屏节点写 `sendevent`、不在每个输入事件上重复启动 `su`。

这批 Root 代码已通过编译、R8、单测和 Lint，但 **RootService、SELinux 下的 `/dev/input` 访问、键鼠设备识别和实际延迟仍需 KSU 实机验证**。不应宣称绝对“0 冲突”。

### 7. Root 主导的显式模式和作者逻辑修复（1.52-beta-fix）

- 输入模式移动到 App 的常规设置页，新增 `ROOT` / `SHIZUKU` 持久配置；默认 Root，没有 `AUTO` 模式。
- Root 只在设置选择 Root 时申请；切换模式会停止当前映射服务，下一次会话才使用新后端。
- `InputBackendRouter` 固定使用设置指定的后端，不再在 Root、Shizuku 之间运行时自动选择或热切换。
- Root 模式必须同时满足 RootService 注入就绪、原生层成功 `EVIOCGRAB` 完整键盘和鼠标；原生捕获未成功时禁止恢复旧的全屏 Pointer Capture。
- Root 服务/原生捕获/注入任一项在游戏中丢失，立即取消现有合成触控、释放设备并停止映射服务；不会转入 Shizuku/ADB。
- 设置页可直接看到 Root 授权状态和原生捕获状态/失败原因，并可手动重新检测；Root 连接增加 10 秒超时，避免授权拒绝后永远停在“连接中”。
- 原生捕获把 `ENODEV`、`EACCES`/`EPERM`、`EBUSY` 等错误上报到 App，不再把捕获失败伪装成普通空闲状态。
- 固定开火/开镜使用鼠标键时是上下文绑定：瞄准模式使用配置的游戏触控位置；鼠标模式必须使用软件光标位置点击菜单。普通 Variable/Cancelable 鼠标键映射仍在两种模式下保持显式映射。
- 修复全局按键设置保存的并发竞态，并补上开镜键对所有已有游戏固定映射的迁移；现在按组件类型迁移三个固定动作。
- 修复 W+S、A+D 等相反方向同时按下时摇杆卡在上一个方向的问题；相反方向会中和，松开后恢复剩余方向。
- 触控随机范围会规范化并防御旧配置中的负尺寸，避免反向区间崩溃。
- 前台服务通知补齐 small icon；通知权限存在时会显示当前是 Root 独占捕获还是 Shizuku 兼容模式。
- 1.43 首次实机验证显示 Root 注入已就绪、Android 能找到键鼠，但原生层报“未能匹配完整组合”并按设计关闭映射。根因是安全匹配同时要求名称和 VID/PID 精确一致，且假设鼠标移动与按键位于同一 evdev 节点；复合接收器/平板键鼠通常不满足。
- 1.44 改为有有效硬件 ID 时按 VID/PID 匹配，名称作为零 ID 设备的后备；鼠标移动与鼠标按键允许来自同一外设的不同 event 节点，点击前会冲刷所有鼠标节点的待处理位移。仍排除直接触屏节点。
- 1.45 实机诊断已确认鼠标复合节点匹配成功，剩余失败为“没有找到完整键盘节点”。原生扫描现在先读取键盘能力，再应用直接触控保护：只有同时具备完整字母键、空格和回车的键盘盖/键盘触控板组合节点才允许通过；普通触屏仍被排除。
- 对 Android 没有提供可对应名称或有效 VID/PID 的设备，1.45 增加严格的纯物理全键盘兜底：必须具备完整键盘特征、不是直接触屏、不是虚拟总线；不会兜底抓取音量/电源键、游戏手柄、普通触屏或 uinput 虚拟设备。
- 用户补充键盘盖通过小米平板背部触点和厂商私有协议连接，并非普通 USB/蓝牙设备；设置页当时只列出常驻的 `uinput_nav` 和 `Mi PTMS3 Mouse`。`uinput_nav` 在无键盘盖的小米设备也可能存在，不能仅凭名称无条件独占。
- 1.46 将键盘节点资格从无关的 `A+Z+空格+回车` 改为 KeySync 实际需要且更能排除导航/媒体节点的完整 `W+A+S+D` 能力；Android 标记为内部、但非虚拟且属于字母键盘的输入设备也会传入 Root 白名单。
- 1.46 允许名称明确包含 Xiaomi/Nanosic 且具备 WASD 的厂商虚拟键盘桥作为安全兜底；普通 `uinput_nav`、触屏、音量/电源键和不具备 WASD 的虚拟节点仍不会被抓取。
- 1.47 实机确认设备寻找已经正常，但复现“中键进入瞄准并移动视角 → 打开游戏页面 → 中键回到鼠标模式 → 左键无法点击 UI”。根因不是光标无限漂移，而是作者逻辑先处理“左键已绑定开火”，导致鼠标模式仍点击固定开火坐标，完全忽略可见软件光标。
- 1.47 调整鼠标模式优先级：固定 FIRE/SCOPE 鼠标键在鼠标模式按软件光标绝对坐标注入，在瞄准模式才按固定映射位置注入；普通自定义鼠标键映射语义不变。新增完整模式切换回归测试，也保留未映射点击拖动、瞄准开火和普通右键映射测试。
- 1.47 同时修复按住鼠标键拖动时的边缘坐标分叉：软件光标被屏幕边界钳制后，旧代码仍把完整原始位移累加到合成触点，导致可见光标留在边缘而实际触点继续越界。现在触点只接收光标钳制后真正生效的位移，并把最大可点击坐标限制为 `width-1`/`height-1`，避免边缘点击落到屏幕外。
- 1.48 为连续瞄准移动增加相机触点边界重建：跟踪相机虚拟手指的绝对位置，触及屏幕边界时按队列顺序抬起并回到瞄准锚点，再继续应用后续相对位移。移动队列使用新 epoch 分隔重建前后事件，避免长期移动越界，也不把重建后的位移合并到旧手势。
- 1.49 消除鼠标伪键码与 Android 实体键码的命名空间碰撞：旧 `64/128/256` 分别与 `KEYCODE_EXPLORER`、`KEYCODE_MEDIA_CLOSE`、`KEYCODE_TV_CONTENTS_MENU` 相同，会让实体键与鼠标共享 pointer/state。新左/右/中键码改为负数，并在 DataStore 读取时同时迁移最早的 `1002/1003/1004` 和当前的 `64/128/256`；全局按键与每个游戏的 Variable/Fixed/Cancelable 映射均兼容。
- 1.50 根据实机列出的 `Mi PTMS3 Mouse` 和用户确认的背部触点私有协议，补充 PTMS 系列小米键盘盖命名：Android 候选发现和原生虚拟总线安全兜底现在识别 `PTMS + Keyboard/Kbd` 与明确的 `Mi ... Keyboard`，仍要求底层节点具备完整 WASD 能力。`Mi PTMS3 Mouse` 不会被误标为键盘，常驻的通用 `uinput_nav` 仍明确不作为键盘盖放行。
- 1.51 进一步修复菜单鼠标点击的通用触控流问题：游戏可能在打开页面时自行作废 KeySync 的旧多点触控流，而本地仍残留 WASD、技能或 camera pointer；旧实现会把菜单左键作为该旧流的 `ACTION_POINTER_DOWN`，部分游戏 UI 会完全忽略。现在鼠标模式下每次新的光标按下都会先按队列发送 `ACTION_CANCEL` 并清空本地 pointer，再以可见软件光标坐标建立全新的 `ACTION_DOWN → ACTION_UP`。新增真实 `EventInjectorImpl` 异步队列回归，覆盖“旧技能 pointer + 瞄准 camera → 中键退出 → 左键菜单”的完整顺序和坐标。
- 1.52 修复服务存活期间坐标边界固定不变：游戏切换横竖屏、窗口尺寸或分辨率后，旧软件光标可能仍落在上一坐标系，造成可见光标与注入点击不一致甚至屏外点击。现在服务配置变化、游戏启动宽限结束以及全屏 overlay 实际布局尺寸变化都会更新边界；软件光标立即钳回有效最后像素，活动 camera pointer 会释放并在下一次移动时从新边界内的锚点重建。新增光标缩屏钳制与瞄准中改尺寸的回归测试。
- 1.53 将映射会话绑定到从 Key Sync 内启动的目标包：通过 Root/Shizuku 特权的 `IActivityTaskManager` 每 150ms 检查前台任务，切出后立即取消合成触控并释放 Root `EVIOCGRAB` 或 Shizuku Pointer Capture，切回同一包后再重新捕获；不会因为前后台切换停止服务或切换后端。
- 1.53 增加鼠标滚轮触控：Root 原生层读取 `REL_WHEEL`，Shizuku/Pointer Capture 读取 `ACTION_SCROLL`/`AXIS_VSCROLL`；只在鼠标模式中，以软件光标位置为起点生成完整的纵向滑动，上滚对应上划、下滚对应下划，瞄准模式不注入滚动。
- 1.54 根据实机反馈修复 Root 鼠标模式滚轮无响应：原生层同时识别传统 `REL_WHEEL` 和小米键盘盖/高分辨率设备可能使用的 `REL_WHEEL_HI_RES`，双协议同时上报时去重，仅高分辨率上报时跨 `SYN_REPORT` 累积到完整滚轮刻度；Root 回调统一使用每刻度 120 单位并在 App 层还原为滚轮刻度。
- 1.54 为滚轮生成的触摸滑动加入真实事件时序：`DOWN`、4 个 `MOVE` 和 `UP` 之间各间隔 12ms，总手势约 60ms，避免同一毫秒内完成的事件序列被游戏 UI 丢弃。瞄准模式行为没有改变，滚轮仍只在鼠标模式注入。
- 原生失败诊断现在能区分：未匹配安全节点、缺键盘节点、缺鼠标移动节点、缺鼠标主键节点、权限/SELinux 拒绝或节点占用。

## 当前未跟踪文件

这些文件是当前本地修复的一部分，不是可删除的临时文件：

- `app/src/main/java/com/devoid/keysync/service/CapturedPointerMotion.kt`
- `app/src/main/java/com/devoid/keysync/service/PointerCaptureFrameLayout.kt`
- `app/src/main/java/com/devoid/keysync/service/SoftwareCursorController.kt`
- `app/src/test/java/com/devoid/keysync/service/SoftwareCursorControllerTest.kt`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/RootEvdevCapture.cpp`
- `app/src/main/java/com/devoid/keysync/data/external/InputBackendRouter.kt`
- `app/src/main/java/com/devoid/keysync/data/external/LinuxKeyCodeMapper.kt`
- `app/src/main/java/com/devoid/keysync/data/external/RootEvdevNative.kt`
- `app/src/main/java/com/devoid/keysync/data/external/RootInputConnection.kt`
- `app/src/main/java/com/devoid/keysync/data/external/RootInputListener.kt`
- `app/src/main/java/com/devoid/keysync/data/external/RootMouseScroll.kt`
- `app/src/main/java/com/devoid/keysync/data/external/RootInputProtocol.kt`
- `app/src/main/java/com/devoid/keysync/data/external/RootInputService.kt`
- `app/src/main/java/com/devoid/keysync/data/external/SystemInputManagerApi.kt`
- `app/src/main/java/com/devoid/keysync/data/external/SystemActivityTaskManagerApi.kt`
- `app/src/test/java/com/devoid/keysync/data/external/RootMouseScrollTest.kt`

## 最新构建与验证

最近一次完整命令：

```bash
export JAVA_HOME="/home/sakura/.cache/keysync-build/jdk17"
export ANDROID_HOME="/home/sakura/.cache/keysync-build/android-sdk"
export ANDROID_SDK_ROOT="/home/sakura/.cache/keysync-build/android-sdk"
./gradlew --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleRelease
```

结果：

- `BUILD SUCCESSFUL`
- JVM 单元测试：44，failures 0，errors 0。
- Android Lint：通过。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Debug APK SHA-256：`25439a9592d1f0bc07b1582b19336681e49fa16600d6f71e322f84d682feeda6`
- Release/R8：通过，未签名 APK 为 `app/build/outputs/apk/release/app-release-unsigned.apk`。
- Root JNI 库已打包 `arm64-v8a`/`armeabi-v7a`/`x86`/`x86_64` 四种 ABI。
- APK 的 debug 包名为 `com.devoid.keysync.fix`，版本号为 `18`，版本名为 `1.54-beta-fix`；使用同一 Android Debug 证书（SHA-256 `339b630de04878f77646e29fdf29a84b6785fa5a5e0b8f1f2cddbda6937428b0`）签名，可覆盖同证书的旧 `.fix` 调试版。

## 下一步

1. 1.52 已经用户实机确认键盘、鼠标移动和点击正常；1.53 实机发现鼠标模式滚轮无响应，已经由 1.54 针对 Root 高分辨率滚轮和触摸滑动时序修补。请覆盖安装 1.54 最新本地 Debug APK，重点实机回归：
   - 从 Key Sync 启动目标应用，确认映射启用；切到其他应用后约 150ms 内键鼠归还系统，切回同一目标应用后自动恢复。
   - 从桌面手动打开未由 Key Sync 启动的其他应用，确认不会套用旧映射。
   - 在鼠标模式将光标放在可滑动区域，确认上滚=上划、下滚=下划；瞄准模式中滚轮不触发滑动。
   - 从 KeySync 冷启动目标游戏。
   - overlay 服务已运行时，再从 KeySync 启动同一游戏。
   - 游戏 A 切换到游戏 B，确认 B 使用自己的配置且不黑屏。
   - 启动后等待约 2.5 秒，确认键鼠捕获自动恢复。
   - NPC 对话 → 奖励界面点击退出 → WASD 与鼠标镜头同时操作。
   - 真实触屏后键鼠自动恢复。
   - 连续点按/长按技能键，以及鼠标右键闪避。
   - 设置页选择 Root 后，状态显示“已获得 Root，特权注入已就绪”。
   - 启动游戏后，Root 捕获状态/通知确认键盘和鼠标均被原生独占，Android 系统不再同时处理同一设备。
   - 展开 KeySync 悬浮菜单时键鼠立即归还系统；折叠后再次自动接管。
   - 在 KSU 临时撤销 Root 或杀死 RootService，确认当前映射立即关闭、出现提示，且没有切换到 Shizuku/ADB。

2. 当前 Codex 是不能连接外设的虚拟开发环境，不再尝试 ADB/USB 设备验证。实机结果优先通过设置页显示的 Root/捕获状态、停止映射提示和用户截图反馈定位。

3. 用户确认实机稳定后：
   - 检查完整 diff，不覆盖用户修改。
   - 视需要更新版本号和 README 修复列表。
   - 重新运行 `testDebugUnitTest lintDebug assembleDebug`。
   - 只在用户明确要求时 commit/push/release；不建立上游 PR。

## gamepad-fork 独立路线（0.1.0-gamepad-alpha）

- 该路线从已验证的 1.54-beta-fix 本地工作区分叉，必须完整保留 `REL_WHEEL`/`REL_WHEEL_HI_RES` 去重、高分辨率残量累积、120 units 归一化和约 60ms 触控滑动时序。
- 独立包名为 `com.devoid.keysync.gamepad`，`versionCode=1`，`versionName=0.1.0-gamepad-alpha`；不跟随触控主线版本号，可与主线包并存。
- RootService 在开始安全 `EVIOCGRAB` 前创建 `BUS_VIRTUAL` 的 `/dev/uinput` 标准手柄；停止捕获、目标 App 失焦、Root 丢失、Binder 客户端死亡或原生捕获失败时，都先释放按键/归中摇杆再销毁虚拟设备。
- 通用手柄轴为 `ABS_X/Y` 左摇杆、`ABS_Z/RZ` 右摇杆、`ABS_GAS/BRAKE` 扳机和 `ABS_HAT0X/Y` 方向键；右摇杆选用 Z/RZ 是为了在 Android `Generic.kl` 下得到标准 `AXIS_Z/AXIS_RZ`。
- 鼠标 `REL_X/REL_Y` 按报告间隔转换为速度，使用噪声阈值、响应曲线、死区补偿和二维单位圆归一化；最后输入后目标 6ms 主动回中。回中检查是鼠标活动后才启动的一次性任务，空闲时不进行 500Hz 轮询。
- WASD 映射左摇杆，对角线乘 `1/sqrt(2)`；语义按键初版为左键→X、右键/左右 Shift→B、空格→A、F→Y、E→RB、中键→R3、1–4→`LB + X/Y/B/A`。重叠来源通过全量状态重算，避免松开一个来源时提前释放仍被另一来源按住的按键。
- 这是通用 Android 手柄 PoC，没有伪装 Sony VID/PID，也不宣称 DS4 兼容。DS4 必须经过静态协议分析和实机验收后再决定。
- 虚拟环境无外设，只能验证状态机单测、Lint、JNI/四 ABI 编译和 APK 元数据；`/dev/uinput` 权限、Android 手柄识别和 6–8ms 回中抖动必须实机验收。

## 实现注意事项

- KeySync 的 WASD、镜头、技能键共享一条 Android 合成多点触控流；修改 pointer 生命周期时必须验证 `ACTION_DOWN/POINTER_DOWN/MOVE/POINTER_UP/UP/CANCEL` 顺序。
- 不要恢复用 WASD 位掩码直接作为普通 `keyMap` 索引的旧实现。
- 不要把普通按键改回固定短触；用户明确要求同一键自然区分点按和长按。
- 未映射鼠标键应保留普通光标点击；普通 Variable/Cancelable 显式鼠标映射应按配置触发；固定 FIRE/SCOPE 鼠标键只在瞄准模式使用映射坐标，鼠标模式必须点击软件光标位置。
- 不要同时运行其他映射工具做对照，否则多个注入器会干扰 pointer ID 和捕获结果。
- 当前没有连接的 ADB 设备，与窗口焦点、黑屏、HyperOS 调度相关的最终结论都需要实机验证。

## 上游同步审计（2026-10-06）

- 已执行 `git fetch upstream --tags`。
- 上游 `main` 最新提交为 `d072b0f`（2026-02-27），当前本地修复基线 `f146176` 已包含它；共同祖先就是 `d072b0f`，提交关系为本地领先 6、落后 0，因此无需对 `upstream/main` 做合并。
- 上游 `feature/native` 最新提交为 `b5646de`，从共同祖先 `32c5181` 分叉；相对当前分支是本地 10、上游 3。对它做合并预演会在 README、Gradle、Manifest、Shizuku 后端、EventInjector 和 FloatingWindowStateManager 产生内容冲突，并删除当前分支的多组测试、语言设置和输入恢复代码，不能直接整体合并。
- 上游实验原生实现可作为思路参考，但静态审查发现：只选择第一个键盘节点、没有完整鼠标/滚轮通路；`InputReader.cpp` 使用 `grantpt()` 而非 `EVIOCGRAB`；JNI 全局保存来自其他线程的 `JNIEnv*`，线程安全和生命周期不成立；读取循环没有可靠的停止/设备断开清理。因此应继续使用当前 Root evdev 捕获骨架，按接口吸收可验证的片段。
- 当前共享目录已包含 `gamepad-fork` 的未提交改动：Root 捕获回调直接驱动 `/dev/uinput` 虚拟手柄，包名为 `com.devoid.keysync.gamepad`、版本 `0.1.0-gamepad-alpha`。重构时必须先明确触控映射与虚拟手柄是两种后端/产品模式，再拆分共享的设备捕获、前台会话、状态复位和错误上报，不能把两套语义继续堆在 `RootInputService` 的同一组回调中。

## 重构准备方向

1. 保留当前 `RootEvdevCapture.cpp` 的白名单、`EVIOCGRAB`、SYN 帧冲刷、滚轮高分辨率归一化和断开清理；将原生事件转为统一的不可变 `InputEvent` 流。
2. 将 `RootInputService` 拆成设备捕获会话、触控注入 Sink、虚拟手柄 Sink 和前台目标会话四层；每个会话只能绑定一个 Sink，Root 丢失、目标失焦或客户端死亡时走同一套 reset/release 流程。
3. 让 `FloatingWindowStateManager` 只负责 UI 状态和目标包生命周期，把输入模式切换与权限/设备状态交给独立的 session coordinator。
4. 先为设备筛选、Root 回调时序、手柄状态重算、触控 pointer 生命周期建立 JVM/原生可测的纯状态测试，再逐步替换现有大类，避免一次性合并上游实验分支。

## MAA 路线核对（2026-10-06）

- MAA 的公开说明把 `adb input` 定位为低版本/兼容性回退，并明确指出它的滑动容易拖飞、速度会被放慢；能使用 `minitouch` 或 `maatouch` 时不推荐 `adb input`。这证明的是“长期驻留触控代理 + ADB/Shizuku/Root 权限”可行，不等同于证明每次启动一个 `adb shell input` 命令也能满足高频鼠标镜头。
- MaaTouch 公开协议是持续连接、批量提交的 `d/m/u/c` 触点流，适合作为新 Touch Core 的协议参考；它的 `DOWN/UP`、MOVE 合并和提交边界应独立于 Android App UI 线程。
- 当前游戏手柄共享工作区的 `RootInputConnection.inject()` 需要在重构前复核 MotionEvent Parcel 序列、触控模式与手柄模式的路由，不能把 gamepad 版当前状态当成稳定触控基线。

## 触控引擎激进重构分支（2026-10-06）

- 当前分支：`codex/touch-engine-rewrite`，从当前未提交工作区创建，未执行 reset、clean、覆盖或提交。
- `gamepad-fork` 已暂停开发，现有虚拟手柄文件保留但 RootService 不再创建 `/dev/uinput` 或把 evdev 回调送入手柄控制器；本分支 APK 改为独立包 `com.devoid.keysync.touch`，版本 `1.55-touch-engine-alpha-debug`。
- `SystemInputManagerApi`、Root Binder 协议和 Shizuku 后端现在区分异步 MOVE 与等待结果的 DOWN/UP/CANCEL；高频 MOVE 不再逐帧等待 system_server 完成。
- `EventInjectorImpl` 改为控制命令队列 + 合并 MOVE 唤醒通道：多个鼠标样本在一个触控帧中更新所有 pointer，只发一个 `ACTION_MOVE`；控制事件优先，重建/取消前会按 epoch 丢弃或冲刷正确的 MOVE。
- Root 输入回调从主线程 Handler 移到专用 `KeySyncRootInput` HandlerThread，避免 Compose/UI 工作阻塞物理输入分发；`EventManagerImpl` 不再为每个 MotionEvent 创建 pointer 数组切片。
- 53 个 JVM 测试已通过队列重构验证；真实延迟仍需实机对比，虚拟环境无法连接小米键鼠或游戏。
- 本轮构建命令 `./gradlew --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleRelease` 已通过：53 tests、0 failures、0 errors，Lint 0 errors，四 ABI 原生库均打包。Debug APK SHA-256：`89faeed5691af67c922793c5857033e87fdf1b618fa8b7dfbe2756ecfa54563f`。

## 映射备份与恢复（2026-10-06）

- 由于 `com.devoid.keysync.fix` 与 `com.devoid.keysync.touch` 包名不同，两个 APK 不能直接覆盖升级；它们可以并存安装。
- 新增跨版本 JSON 备份格式 `keysync-keymap-backup` v1，内容包括全局鼠标键位、按钮大小、长按/可取消设置、加入的应用、所有应用映射、鼠标灵敏度和悬浮窗透明度；输入后端选择、Root/Shizuku 授权和运行状态不导入。
- 设置页新增“导出备份”和“恢复备份”，使用 Android 系统文件选择器；恢复前有覆盖确认，文件先做大小、版本、包名、坐标、数量和浮点范围校验，再通过单次 DataStore 编辑写入。
- 当前实验版 APK：`artifacts/keysync-touch-engine-alpha.apk`，包名 `com.devoid.keysync.touch`，版本 `1.55-touch-engine-alpha-debug`，SHA-256 `1d8e86951f638520b39a910c5cb3f86f481a330814936a41f28d3d187d532e88`。
- 当前主线兼容版 APK：`artifacts/keysync-mainline-fix.apk`，包名 `com.devoid.keysync.fix`，版本 `1.54-beta-fix`，SHA-256 `1499e5e64eb25fd67dbfe63e63c19cbe0c4c82a3a5fe2247ae870b4247a1d25a`；两者使用同一备份格式。

## MuMu 文档驱动的 Touch Core（2026-10-08）

- 已完整读取用户提供的 910 行开发文档和 `参考文档/com.hypergryph.endfield-默认操作方案.json`。以原 JSON 做单测资源，不以摘要近似坐标代替原文件。JSON 是触控方案（is_joystick=false、virtual_joystick.enable=false）。
- 当前仍在 `codex/touch-engine-rewrite`，保留所有此前未提交修复；未 commit/push/release，也没有恢复 gamepad-fork 开发。稳定 RootEvdevCapture.cpp 未修改。
- 新增 `domain/touch/` 独立模块：TouchSessionManager、TouchPipeline、FrameworkTouchBackend、TouchCoreEventInjector、CameraGestureController、JoystickGestureController、MuMu JSON codec / dispatcher、TouchDebugRegistry。
- 新核心按计划→注入→提交维护多指，分开逻辑 ContactId / Android pointerId / pointerIndex。失败封锁旧会话；只合并相邻 MOVE，不跨 DOWN/UP/Rebase/Cancel。默认合并窗口 4ms，可用 `-PtouchMoveWindowMillis=0..16` 编译调参。
- InputBackendRouter 默认走新核心，复用原长驻 RootService / Shizuku Binder 权限通道，不增加逐 MOVE shell。旧 EventInjectorImpl/EventManagerImpl 保留，`-PtouchCore=false` 可构建旧行为回退。Root 丢失仍停止映射，无自动 Shizuku/ADB 切换。
- 移除了新路径的鼠标静止 100ms 自动抬指、开火先释放 camera、进入瞄准清空整条多指流和 WASD 固定 80ms 动画。镜头持触，越界只对 camera UP→锚点 DOWN→继续剩余位移，支持多次穿越；普通 WASD 对角归一化、反向键保持居中；滚轮调度不阻塞其他按键。
- SystemInputManagerApi 不再对 false 重放事件：AOSP 对 TIMED_OUT 也返回 false，不是确定未投递。正常拒绝/超时/异常交给新核心清理与停止。
- 修复 RootInputConnection 每 64 条回调后错误切回 MainHandler；核心键鼠仍在专用线程，Compose 软件光标只在主线程 Choreographer 渲染，逻辑位置加同步保护；旧动画路径的 Root 输入仍回主线程。
- 系统设置新增“导入 MuMu 触控方案”、按应用移除方案回到旧键位、“导出 Touch Core 诊断”。不覆盖原编辑器配置，不进行自动视觉 Context 识别。MuMu 不在旧编辑器里编辑；实际 JSON 原文和未知单位的 policy 参数保留。
- 原 JSON 的 Click / Fire 跟随按住，Swipehold 单触点，Staring Alt 切换，Macro M 严格执行 `click_rel:(0.072056,0.106250)` 再 exit_staring；不使用 M 编辑器图标坐标。Tip 仅元数据。左右 Shift/Alt/Ctrl 多来源持按保护。
- 灵敏度 sensitivity/50、锚点中心的 RangeWindow 几何、短边基准摇杆半径是当前明确的实现约定，不宣称是 MuMu 精确公式。没有把 reset_wait_interval=8 认定为 8ms，没有硬编码 1920×1080/raw 轴/slot。
- 含 MuMu 配置的备份升级 schema v2；新包接受 v1/v2，无 MuMu 时继续输出 v1 兼容旧版本。旧 1.54/1.55 不接受 v2，迁移前应保留 v1 备份。恢复 UI 修复了合并应用列表的显示，并停止现有映射以免继续使用旧内存配置。
- APK：`artifacts/keysync-touch-core-1.56.apk`，`com.devoid.keysync.touch` / versionCode=20 / `1.56-touch-core-alpha-debug`，同 Debug 证书，可覆盖安装 1.55 实验包，不能覆盖 `.fix` 或 `.gamepad`。旧 artifacts APK 未覆盖。
- 1.56 APK SHA-256：`35c6a164bd8de43d50d80cbd0bef25a17368cefb812f25c77ff6a507b48b001e`。
- 完整命令 `./gradlew --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleRelease` 成功：78 tests、0 failures、0 errors，Lint 0 errors / 77 warnings，Release/R8 与四 ABI 均通过。测试包括实际 JSON、1000 taps、5000 随机多指操作、边界余量、多指 rebase、失败与溢出封锁、快速 Alt/M、跨模式攻击松键、滚轮不阻塞、共享修饰键和旧编辑器接入。
- 诊断仅最近 256 应用内排队 / 状态处理 / 后端提交耗时 p50/p95/p99，不含物理输入到游戏的端到端延迟，不宣称 0 冲突。未连接外设或尝试 ADB，真实 MotionEvent/Game 长期验收仍需用户反馈。
- RootNativeTouchBackend / uinput 触屏尚未实现，必须 Framework 先实机稳定。阶段接入、参数约定、限制、文件列表与验收见 `docs/TOUCH_CORE.md`。

## 环境交接（2026-11-01）

- 用户要求转到可连接 Android 设备的物理机开发。源码已通过同步软件同步，无需手工搬运、重新 clone 或重新创建分支。新会话先完整阅读根目录 `MIGRATION.md`，再看 `docs/TOUCH_CORE.md`、上一节和参考原 JSON。
- 本轮实际虚拟机目录为 `/home/sakura/localproject/KeySyncFix`，旧 `/home/sakura/本地项目/KeySyncFix` 已不存在。物理机路径和操作系统由新会话现场确认。
- 仍为 `codex/touch-engine-rewrite` / HEAD `f1461761f8cb0a90e0c11f22b2a9e899b6e6ca04`。25 个已跟踪文件的修改与大量未跟踪源码、测试、原生引擎完整保留，不能用远端 HEAD 覆盖；没有提交、推送、拉取或发布。
- 已写 `MIGRATION.md`，涵盖当前 1.56 Touch Core、Root 主导策略、暂停的 gamepad、设备条件、构建、旧签名、APK 指纹和实机验证顺序。本轮未改业务代码，未连接设备，未重新构建；78 项测试和 Lint/四 ABI 成功均为读取旧报告确认的 2026-10-08 历史结果。
- 从清理前的 CMakeCache 确认历史 NDK 为 `27.0.12077973`，CMake `3.22.1`；配套 JDK17、Gradle8.9、SDK35，物理机按迁移文档恢复，不复用 VM 绝对路径。
- 原 `~/.android/debug.keystore` 已保留，并复制到受 Git 忽略的 `.secrets/migration/debug.keystore`（目录 700、文件 600），副本与原文件一致，Debug 证书 SHA-256 为 `339b630de04878f77646e29fdf29a84b6785fa5a5e0b8f1f2cddbda6937428b0`。物理机先检查隐藏私有目录是否随既有同步规则到达；保留该签名才能继续覆盖旧 `.touch` 调试包，不公开或强制提交密钥。
- 已清理项目 `app/build`、`app/.cxx`、`.gradle`、`.kotlin`，用户目录 Android 缓存、Gradle Android 元数据、逐个确认属于本项目的旧 Kotlin profile，以及 Gradle8.9 分发/通知缓存；释放 **624,910,336 字节，约 596 MiB**。删除范围与核验记录见迁移文档。历史 `~/.cache/keysync-build` 专用 SDK/NDK/JDK17 已先于本轮不存在；其他项目共用的 Java/Gradle、Codex 运行时和同步软件保留。
- 清理前后校验的源码/配置/交付文件清单、已跟踪 diff、分支/HEAD 与三个 `artifacts/` APK 哈希均一致；参考 JSON、Gradle Wrapper、`.git`、ADB 身份密钥和签名均保留。下一步在物理机恢复环境和签名、构建核验、备份配置后覆盖安装，再用实机诊断推进 Framework Touch Core；gamepad 继续暂停。

## 物理机核验（2026-11-01）

- 已在同步目录 `/home/sakura/local本地开发/KeySyncFix` 完成交接核对。当前为 Linux x86_64，分支 `codex/touch-engine-rewrite`，HEAD `f1461761f8cb0a90e0c11f22b2a9e899b6e6ca04`；原有 55 个工作区状态项保持不变，没有 reset、clean、commit、push 或拉取远端。
- 物理机已具备 JDK 17.0.21（`/home/sakura/.local/jdks/temurin-17`）、Gradle 8.9、SDK `/home/sakura/Android/Sdk` 的 Platform 35、Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1、platform-tools 37.0.1。项目没有写入新的 `local.properties`。
- 使用 JDK 17 完整运行 `./gradlew --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleRelease` 成功：78 项 JVM 测试、0 failures、0 errors；Lint 0 errors、77 warnings；Release/R8 和四 ABI JNI 成功。该次构建只产生被 Git 忽略的 `app/build/`、`app/.cxx/` 等缓存。
- 物理机默认 `~/.android/debug.keystore` 与迁移副本不同，未覆盖默认密钥；迁移副本 `.secrets/migration/debug.keystore` 证书 SHA-256 为 `339b630de04878f77646e29fdf29a84b6785fa5a5e0b8f1f2cddbda6937428b0`。使用迁移副本显式签名的 Debug APK 已验证包名 `com.devoid.keysync.touch`、versionCode 20、版本名 `1.56-touch-core-alpha-debug`，适合覆盖同证书旧 `.touch` 包。
- 本节初次核验时 ADB 尚未列出设备；之后设备接入和实机修复见下一节。

## MuMu 悬浮显示与实机瞄准确认（2026-11-01）

- 用户后续授权直接通过 ADB 排查，并要求非必要禁止 root。仅使用普通 ADB、调试包自身 `run-as`；没有使用 `adb root` / `su` / root shell。应用继续运行既有 Root 输入后端。连接的唯一平板是 `0D070400044C1540` / `25053RP5CC`，Android 16、HyperOS `OS3.0.304.0.WOTCNXM`，横屏逻辑 3200×2136。
- MuMu 配置实际完整保存在 `mumu_profile_com.hypergryph.endfield`；旧键位列表为空。原渲染器只显示旧编辑器列表，因此导入后虚拟按钮、Alt、提示和宏标记全部不可见，不能误判为 JSON 没有导入。
- `FloatingWindowStateManager` 暴露当前已解码 MuMu profile；`FloatingBubbleService` 在方案生效时改用 `MumuOverlay`，不再同时显示旧键位，隐藏添加旧组件入口。新悬浮层展示普通键、Fire、WASD、Staring、Macro 和 Tip，并展示按键说明；Tip 不参与动作调度。
- `MumuProfileCodec` 增加类型化 `MumuIcon` / `MumuTip`，读取图标位置、说明、可见性与缩放；显示与触控执行坐标分开。M 显示“地图”，执行仍是 `click_rel:(0.072056,0.106250)` 后 `exit_staring`。原 JSON/policy 原文依然保留。
- 提高标记对比度；中文鼠标按键名称和可读 Alt/Shift 标签；顶部实时显示“鼠标模式 · 按 Alt 切换瞄准”或“瞄准模式 · 按 Alt 显示鼠标”。此层展示映射和状态，通过方案中的实体按键触发动作，不是触屏按钮或 MuMu 编辑器。
- 用户最初未开启物理鼠标；开启后日志确认 `Xiaomi Keyboard` / `event8` 和外置蓝牙 `Mi PTMS3 Mouse` / `event15` 同时捕获成功。内置 `Xiaomi Mouse` 为系统禁用的私有节点，没有证据需要扩大捕获范围，因此撤回本轮尝试的内置鼠标识别扩展。断鼠标曾触发 `-19 ENODEV` 停止映射，重新启动映射后恢复。
- 已使用迁移旧证书覆盖安装调试包，偏好备份前后逐字节一致。**用户已实测确认 Alt 切换后显示瞄准模式，移动物理鼠标可以转动游戏视角**；截图确认导入标记可见、模式提示更新且瞄准时光标隐藏。没有使用 ADB 合成按键冒充真实 evdev 验收。
- 本轮跑过 12 项相关测试：MuMu profile 9 项＋备份 codec 3 项，0 failures/errors；新增一项验证显示位置/隐藏/缩放不会改变触控目标，原 JSON 测试补充地图及 Tip 说明。Lint 0 errors / 77 warnings（另 6 条信息），Debug、Release/R8、四 ABI JNI 通过。之前 78 项全量测试是环境恢复阶段记录，本轮未重新全量运行。
- 版本仍为 `.touch` / 20 / `1.56-touch-core-alpha-debug`；历史 `artifacts/keysync-touch-core-1.56.apk` 不覆盖，本轮另存 `artifacts/keysync-touch-core-1.56-mumu-overlay.apk`，以指纹区分。Debug 必须使用 `.secrets/migration/debug.keystore` 显式签名，不能使用本机不同的默认密钥。
- 最终修复 APK SHA-256 为 `c09e8beeb2329170dd082f3c286a00621d2f59457889b6c03265e56eea767e2d`，已用旧证书核验并再次覆盖安装。实体 Alt／鼠标已在同一修复实现上验证；收尾只补充了非空自定义镜头说明的显示，最终安装会将映射模式重置为鼠标模式。
- 剩余：真实多指组合、技能长按、地图宏、对话后恢复等尚未完整实机验证；不要把已通过的显示与 Alt/视角操作说成所有问题已解决。gamepad/uinput 继续暂停；未 commit/push/publish，所有既有未提交工作保留。详情见 `MIGRATION.md` 第 13 节。

## 当前版本本地提交（2026-11-01）

- 用户要求把当前版本代码提交到本地 fork。完整源码、构建配置、测试、参考 JSON、Touch Core 文档和交接记忆一并保存为 `codex/touch-engine-rewrite` 上的本地提交；最新提交以 `git log -1` 为准，前文的旧 HEAD 与未提交状态为历史记录。
- 4 个 `artifacts/*.apk` 继续保留在磁盘，不纳入源码提交；签名密钥、机器配置与构建缓存保持忽略。没有推送远端或发布版本。
- 本次仅保存现有版本，代码未再变更；验证沿用上节已完成的测试、构建和用户实体 Alt／鼠标实测结果。

## MuMu 滚轮按模式滑动与缩放（2026-11-01，1.57）

- 用户要求先记录 MuMu 高级策略缺口，再优先修复滚轮：鼠标模式滚动列表，瞄准／开火模式缩放镜头。已明确“自动切换”仅指滚轮根据当前模式选行为，不包括自动识别页面或自动切换 Alt。
- 已新增 `docs/TODO.md`，记录 WASD 分段策略、镜头等待／增益公式、MuMu 滚轮精确参数、键鼠透传、Fire interval、宏时序、外观和剩余实机验收；当前 JSON 关闭的功能不作为本轮已启用缺口。
- 原 MuMu 瞄准模式直接忽略滚轮；鼠标模式反复调用 `injectSwipe` 会取消同一指针的上一段延迟手势，快速滚动可能退化为 DOWN→UP。新 `WheelGestureController` 在 dispatcher 层累积滚轮位移，逐帧移动同一触点，越界时只释放并重建自身触点；零位移边缘不生成点击。
- 瞄准模式滚轮生成中心水平双指张开／合拢。暂时抬起 camera 实际触点，缩放期间不处理镜头 MOVE，但保留逻辑瞄准状态、WASD 和攻击触点；缩放结束后下一次鼠标移动自然重建 camera。模式切换、reset、旋转和 close 取消滚轮任务并释放其触点。原编辑器滚轮路径未改。
- 新手势采用 KeySync 明确约定的距离与时序，不声称已还原 MuMu 的 `wheel_policy` 公式。版本升级 `.touch` / 21 / `1.57-touch-core-alpha-debug`；旧 Debug 证书覆盖安装，安装前后偏好逐字节一致；普通 ADB 操作未使用 root shell。
- 相关 21 项测试通过：Wheel 4、MumuProfile 9、GestureController 5、MappingBackup 3；Lint 0 errors / 77 warnings（另 6 条信息）；Debug、Release/R8、四 ABI JNI 均成功。用户已开启物理鼠标，游戏重新从 KeySync 启动，日志确认键鼠同时捕获；实际列表／缩放反馈待记录。
- 本轮 APK 为 `artifacts/keysync-touch-core-1.57-wheel.apk`，SHA-256 `33df198bca75312da567474b3233bab277981736c80a83d59d667a0933c32180`。原有 APK 和本地提交 `8d9de43` 保留；本轮修改尚未提交或推送。
- 用户随后实测反馈：**列表滑动和瞄准模式镜头缩放都正常**，但列表方向希望符合普通 PC 手感，避免苹果自然滚动的反向。已将 MuMu 列表改为上滚→页面向上（手指下划）、下滚→页面向下；镜头仍上滚拉近、下滚拉远。此项用户新要求取代早期“上滚=上划”的约定；旧编辑器路径暂未迁移。
- 为区分方向修订，最终版本调整为 `.touch` / 22 / `1.57.1-touch-core-alpha-debug`，并更新滚轮方向及边缘回归测试。
- 方向修订后再次运行 4 项滚轮测试、Lint 和 Debug / Release/R8 构建，全部成功，Lint 仍为 0 errors / 77 warnings。最终包 `artifacts/keysync-touch-core-1.57.1-wheel.apk` 的 SHA-256 为 `7a9b5550a85bfed6b137401b5d76d1d46459c88dfd9b0d9aec2148a74b412cbf`，已核对旧 Debug 证书并覆盖安装。两种游戏手势的用户实测属于此前 1.57；最终列表方向由触控帧测试确认，未要求用户重复测试。

## 多游戏独立管理多份 MuMu 方案（2026-11-01，1.58）

- 用户要求添加任意游戏后，可给该游戏导入多份 MuMu JSON，退出游戏到 KeySync 选择后再启动；各游戏的方案库和当前选择独立，不要求游戏内热切换。新增主页每个游戏图标下的“方案”入口，导入目标直接绑定所选游戏，不从文件名猜包名、不限定终末地。
- 新增 `MumuProfileLibrary` / `SavedMumuProfile`：每份保存独立 ID、名称和完整 JSON，每个游戏保存 nullable `selectedId`（null 使用原编辑器）。新导入自动选中；可重命名、逐份删除和保留 JSON 切回原编辑器。删除当前方案后明确回到原编辑器，不自动选另一份。导入、选择或删除当前方案停止旧映射，下次从 KeySync 启动游戏重新加载；重命名不影响输入。
- `MumuProfileStore` 保存到 `mumu_library_<package>`；DataStore 的 `MumuProfileMigration` 在首次访问时原子迁移旧 `mumu_profile_<package>`，原 JSON 包装为名为“MuMu”且保持选中的方案，并移除旧键。引擎继续通过 `getMumuProfile(package)` 获取该游戏的当前 JSON，原触控调度、滚轮方向和动作支持范围不变。删除游戏时遵循现有 deleteDataOnRemove 设置，启用后也删除该游戏全部 MuMu 方案。
- 备份扩展 schema v3，完整保存所有 JSON、名称、ID 和选择；继续恢复 v1/v2，v2 单方案自动转换。恢复只替换备份涉及游戏的方案库，其他游戏保留。无方案库时仍可导出 v1；包含方案库时需要 1.58 或更新版本恢复，备份仍受 4MiB 文件上限约束。
- 16 项相关 JVM 测试通过：新存储/迁移/跨游戏隔离/多方案选择与删除/备份恢复 4 项、现有备份 codec 3 项、MuMu parser 9 项。Debug、Release/R8、四 ABI JNI 和 Lint 均通过，Lint 0 errors / 77 warnings（另 6 条信息）；未扩大全量测试范围。
- 通过普通 ADB / 应用自身 run-as 覆盖安装，并核对真实设备 DataStore：迁移前后原 JSON 逐字相同，保持选中，所有其他键位/设置一致。用户解锁系统文件选择器后，已通过真实界面导入第二份 JSON、重命名、两份 JSON 互切、切回编辑器、删除当前方案并验证回到编辑器。测试方案和临时文件已清理，重新选中原方案；重启应用及最终 APK 再次覆盖后，偏好与最初迁移状态完全一致。
- 本轮未使用 adb root / su / root shell。尝试从 KeySync 启动游戏补查运行状态时，物理鼠标已不在当前输入设备列表，映射服务未持续运行；游戏显示服务器要求重新登录。没有在此状态下声称重新完成实体键鼠验收，也未改变捕获白名单或处理用户游戏登录。
- 最终包：`artifacts/keysync-touch-core-1.58-profiles.apk`，`.touch` / versionCode 23 / `1.58-touch-core-alpha-debug`，沿用迁移 Debug 证书；SHA-256 `8f6fae1737accdb2d925e5d0578af6c8aa12d512ef24dd60019fbade43516d97`。已覆盖安装，保留原配置。原滚轮修复及本轮多方案代码尚未再次提交、推送或发布；本地基线仍是 `8d9de43`。

## 平板 MuMu 轮盘位置校准（2026-11-01）

- 用户反馈单按 WASD 斜走。核对原 JSON、当前所选 MuMu profile 和引擎：键码确为 W/A/S/D，单轴向量按屏幕上下左右生成。实机截图直接显示导入圆心在游戏轮盘圆心右上方；3200×2136 上原触控圆心为 (483.3065, 1629.9976)，游戏静止轮盘圆心约 (444, 1754)。游戏屏幕全幅且方向正常；JSON 没有来源屏幕尺寸，不能把该游戏 UI 在不同屏幕比例上的布局当作纯归一化缩放。
- 本轮采用配置校准，不改通用 WASD 方向算法、不添加游戏包名或坐标硬编码。由当前已导入的原 JSON 复制 `artifacts/com.hypergryph.endfield-平板轮盘校准-3200x2136.json`，将 Swipehold 的 rel_work_position 与 icon.rel_position 改为 x=444/3199、y=1754/2135；半径和其他字段逐项比较保持相同。原参考 JSON 未改。
- 通过应用正常文件选择器导入为 `WASD-3200x2136` 并选中。已用普通 ADB / 应用 run-as 核对：旧 profile 完整保留、新 profile JSON 与校准文件一致，其他所有偏好未改变。当前版本仍为 1.58 / versionCode 23，不需新 APK，未重新运行构建或添加低价值测试。
- 校准后目标点为 W=(444,1640.218)、A=(330.218,1754)、S=(444,1867.782)、D=(557.782,1754)，半径113.782px。这是此平板当前游戏 UI 的专用方案，不能当作任意游戏/分辨率的全局修复。
- 首次从 KeySync 重新启动游戏补查时，外置蓝牙鼠标已从 dumpsys input 消失，映射服务未持续运行，游戏也显示服务器重连失败。用户随后已恢复鼠标和游戏；从 KeySync 重新启动映射后，实机截图确认 WASD 标记圆心已与实际游戏轮盘重合。已请求实体 W/A/S/D 验证，四方向行走结果尚待反馈，不能将几何校准直接写成全部方向实机通过。无 adb root / su / root shell。临时下载文件已移除，导入后的方案数据保留。

## Root 捕获交接修复与无线调试（2026-11-02，1.58.1）

- 校准后用户反馈“按一下变成持续性输入”。旧版实机证据指向独占交接：S DOWN 已在约 23:00:22.928 进入 Android，Root 约 23:00:22.947 才捕获键盘，之后 S UP 被独占接走；系统 `sg-input` 持续产生 S ACTION_DOWN，repeatCount 超过 11,000，InputReader 的 Xiaomi Keyboard 留有 1 个按下键。暂停映射、回到 KeySync 后由用户短按并松开 S，已确认残留清零。
- `RootEvdevCapture.cpp` 的 `open_safe_devices()` 现在只打开并分类允许节点。新交接逻辑读取 `EVIOCGKEY`，按住时保持未独占，每 10ms 检查，直到键鼠按钮全部释放；清掉已传给 Android 的 fd 队列后统一 grab，并再次读取按钮状态。若交接中发生按下，解除所有 grab，让这一按完整经过 Android 后再交接。保留取消/错误清理流程，不增加超时自动抬指，不改键码、白名单、方向或滚轮算法。
- `.touch` 升为 versionCode 24 / `1.58.1-touch-core-alpha-debug`，APK 为 `artifacts/keysync-touch-core-1.58.1-capture-handoff.apk`，SHA-256 `aaf42b47a05d8e7405cb3dfde2758d6d2b4d14e0b75914282bac2da4150f67ce`；沿用迁移 Debug 证书覆盖安装。升级前后偏好逐字节一致，校准方案保持选中。
- 修复构建已通过 GestureController 5、MumuProfile 9、RootInputDeviceClassifier 2、InputCaptureRecoveryController 1，共 17 项测试；Lint 0 errors / 77 warnings（另 6 条信息），Debug、Release/R8、四 ABI JNI 成功。2026-11-02 恢复无线调试时核对现有测试报告和 APK 指纹，没有重复构建。
- 此前按住 W 启动测试中，一轮因蓝牙鼠标休眠未捕获，另一轮捕获成功但未出现 `Waiting for keyboard and mouse buttons to be released before capture` 日志，因此不能声称已实测覆盖等待分支；松键停止和四方向直走结果一直未确认。不要把中断会话或静态截图当作验收通过。
- 用户已为连接稳定改用 2.4G 鼠标，无线 ADB 已配对。11:02 起现场重新枚举到唯一设备 `10.1.1.115:45941`，型号仍为 25053RP5CC；无线端口可能变化，下次仍先运行 `adb devices -l`。普通 `dumpsys input` 确认接收器 `Xiaomi Mi USB Receiver Mouse`（event15）、接收器键盘（event19）和 `Xiaomi Keyboard`（event8），当时系统没有按键残留。
- 从 KeySync 启动游戏后，11:07:09.647 日志确认上述三个节点成功捕获，无需新增白名单。通过应用 run-as 读取方案库，原 `MuMu` 与 `WASD-3200x2136` 均保留，后者仍选中；游戏已进入可走动的室内场景。本轮已请求用户实体短按 W/A/S/D，验证松键即停及四方向，等待反馈。
- 全程普通无线 ADB / 应用 run-as，没有 adb root、su 或 root shell；应用继续使用原有 RootService。当前本地基线仍为 `8d9de43`，后续源码与文档未再次提交、推送或发布。

## 整套 MuMu 布局校准（2026-11-02，1.59）

- 用户确认 1.58.1 **松键即停、WASD 四方向正常**；补充原 JSON 来自 **1920×1080**，并指出其他按键也错位。平板为 3200×2136，原来直接归一化铺满屏幕的坐标不适应游戏在不同比例下重新排布 HUD。来源分辨率不是足以推导任意游戏新布局的公式；本轮添加按方案直接校准实际触点，未引入未经证实的通用自动缩放/锚定。
- 新增 `MumuLayoutEditor.kt` 和 `ui/overlay/MumuLayoutCalibration.kt`；点开悬浮球即进入校准，停止捕获并释放旧触点。实际触点用十字显示，拖动普通按键、Fire、Swipehold 和 Staring 同步修改 rel_work_position / icon.rel_position；Tip 和宏图标只改显示位置，宏 press/release 中每一个 click_rel 独立编辑。箭头可选中重叠目标，保存更新当前游戏指定 profile ID 的 JSON 并重建 dispatcher，取消保留原保存数据。错误保存只在 IO 边界提示并保留草稿。
- codec 的 MumuIcon 保留 keymaps 索引供编辑，原策略、键码、名称、ID、其他方案和游戏不变；备份 v3 已直接包含这些 JSON 变化。显示点与触控统一 width−1 / height−1；没有改摇杆方向、半径或捕获白名单。
- 16 项相关测试通过（MuMu 11、MumuProfileStore 5），覆盖实际宏落点、轮盘/按键编辑与策略保留、方案隔离和备份；Lint 0 errors / 77 warnings / 6 informational，Debug / Release/R8、四 ABI JNI 构建通过。最终 APK `artifacts/keysync-touch-core-1.59-layout-calibration.apk`，SHA-256 `f0f0625ca97b579ecd610df340afdbf42526f15059bf4276e6c888e245899bad`，`.touch` / 25 / `1.59-touch-core-alpha-debug`，沿用迁移 Debug 证书覆盖安装。安装前后偏好逐字节一致。
- 用户先在新界面调整了十余个位置，后明确授权助手继续、本人暂不操作。已将其调整保存为 checkpoint，没有丢弃。实机临时拖动 R 后取消，DataStore 方案库与选择完全不变；随后将攻击改为约 (2674.65,1764.87)、Tab 锁定改为 (2150.77,1969.82)，保存后核对实际坐标及显示同步。原 MuMu 完整保留，轮盘仍为 (444,1754)，所有无关偏好及 info/param 不变。副本仍名为 `WASD-3200x2136`，已包含其他键位修改；导出文件 `artifacts/com.hypergryph.endfield-平板布局校准-3200x2136.json`。
- 用户最终明确确认 **F1–F4 切人、Space 跳跃、Alt 瞄准后左键攻击全部正常**。截图可见队伍、R、跳跃、闪避、攻击、锁定等已基本对齐；技能/连携等隐藏按钮尚不能验收，不将本轮记为所有动作均已通过。
- 12:18:12 曾出现一次 RootService 的 `InputManager rejected or timed out`，旧触控流停止并清零，悬浮映射结束；没有 app 崩溃证据。之后从 KeySync 重新启动恢复，后续保存/捕获和实体测试成功。此问题尚未定位，已单独记录待办，未加重放或自动重试。
- 全程普通无线 ADB 与应用 run-as，无 adb root、su 或 root shell；本地基线仍为 `8d9de43`，本轮代码/文档未提交、推送或发布。

## 明日方舟联合宏适配（2026-11-02，1.60）

- 已完整导入用户明日方舟 JSON：7 Click、8 Macro、1 Observe。原图说明为 1600×900、144+ 帧，平板实际 3200×2136。新增测试 fixture `参考文档/com.hypergryph.arknights-默认操作方案.json`，根部用户原件不改。
- 新增当前光标点击、sleep、左键按住/释放、scan 11/158、curve_rel、首点等待、终点保持和 release_all。宏扫描码与 Windows VK 分开；11 对应配置数字 0 暂停触点，158 对应 Android BACK。TouchSession / Framework / Root IPC 支持有序 KeyEvent，停止/失败/断连清理注入按键。宏 release_all 使用正常 UP 交接拖放，失焦停止仍 CANCEL；协程等待不阻塞 actor，取消后阻止迟到 DOWN。
- Observe 为按住 Alt 拖动、松开恢复光标；Staring 仍为 Alt 切换。新提示、锚点校准可用。x/y÷50 仍是 KeySync 约定；宏等待/超屏曲线/保持的精确 MuMu 语义未获官方证实，不宣称零帧。
- 用户在演习中实体确认 C 点击、F 时停、D 一键技能、A 一键撤退正常；随后对 S 验收步骤回复“正确”，确认暂停中指向干员、按住 S 选中并保持时停、松开恢复且无卡输入。用户指出新版游戏已支持暂停部署、旧划火柴脚本早于该更新，因此不再为旧右键功能继续扩展或强制实机验收；原宏保留。普通 ADB 已在暂停中正常部署望，作为场景准备，不能替代实体输入验证。
- 保留 `arknights-mumu-1600x900` 原方案，当前选中 `arknights-tablet-3200x2136`。后者只调整 Q/2 触点与图标、A/D 最后一次固定点击：撤退 (1555,865)，技能 (2300,1465)。校准 JSON 位于 `artifacts/com.hypergryph.arknights-平板布局校准-3200x2136.json`，SHA-256 `91de7e772083557863c55f3a7fdaca82beb1c91b3a2d5455ff87ccd3a6e56b69`。对照 DataStore 确认终末地库完全不变，原 JSON 未改。
- 40 项相关 JVM 测试通过，Lint 0 errors / 77 warnings，Debug / Release/R8 / 四 ABI JNI 成功；同迁移 Debug 证书覆盖安装 `.touch` / 26 / 1.60，安装前后偏好逐字节相同。最终 APK `artifacts/keysync-touch-core-1.60-arknights-macros.apk`，SHA-256 `ffa04c53505112ef92baea30a150140eb13013972d02516cfac02b309bfbf4a8`。
- 全程普通无线 ADB / run-as，无 root shell；旧 RootService 随应用正常退出，新进程重新捕获 Xiaomi Keyboard 与 2.4G 接收器 event8/15/19。E、生息演算 Alt 拖视角、肉鸽 W/1 仍待相应场景。分支仍为 `codex/touch-engine-rewrite`，基线 `8d9de43`，本轮未提交、推送或发布。

## 正式版发布与主分支合并（2026-11-02）

- 用户已明确授权将当前源码与验证状态推送至 `YoruSakura/keysync`，直接合并到 `main` 并整理为一个正式发布包；不再受较早“尚未要求推送/发布”记录限制。
- 正式版本 1.60 / 27，应用 ID `com.devoid.keysync.touch`，同签名 Release/R8 包。完整单测 96 项通过，Lint 0 errors / 77 warnings，四 ABI 构建通过。当前操作/待确认项完整列于 `docs/VALIDATION.md`，保留已确认的 C/F/D/A/S 和终末地操作，不把尚未测试的专用场景写成通过。
- README 更新为正式下载入口、多游戏方案及当前验证表。GitHub Actions 改为测试、Lint、Release 构建校验，移除自动发布重复 APK 的流程；正式包经本地签名核验后统一上传。
- 原始 JSON 与两游戏最新平板校准 JSON 纳入 `参考文档/`；APK、旧包归档、用户根部附件、私钥及本机配置保持本地，不进入 Git。旧预发布包先归档再从 GitHub Releases 移除，历史标签与提交保留。

- 正式发布包：`artifacts/KeySyncFix-v1.60.apk`，5,990,390 字节，SHA-256 `f9172b4b99303efd2982db9dc1f075da3ff6d4f2ac8bfdde39a7bb6ab8751698`。源码提交 `c781887`，正式发布入口 `https://github.com/YoruSakura/keysync/releases/tag/v1.60`；历史 1.41 预发布已归档到本地 `artifacts/github-release-archive/`。
