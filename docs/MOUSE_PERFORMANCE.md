# 1.61 鼠标性能优化

日期：2026-11-03。性能实测在 `codex/mouse-performance` 完成，基于 `main` 的 `8d913f3`。平板安装 `1.61-debug` / versionCode 28；正式发布包为 **1.61 / versionCode 29**，构建与签名信息见 [VALIDATION.md](VALIDATION.md)。

## 实现

- 旧设置 20% 实际将原始位移乘 2，默认 50% 乘 5，最大 100% 乘 10；同时影响光标和视角。现在视角使用独立设置：1%–100%、步进 1%，默认 50%，最大为原始输入的 1 倍。MuMu 的 X/Y÷50 倍率随后照常应用。
- 光标保留原存储值和实际速度，显示真实倍率：原 50% 显示为 500%，速度相同。可调 10%–1000%、步进 10%。Root 和 Shizuku 共用模式分流路径。
- 光标从 Compose Image 改为同窗 ImageView，在 Choreographer 动画阶段直接更新 translationX/Y，移除帧回调写 Compose 状态后等待快照观察的环节。仍然每帧只画最新坐标；点击、拖动使用即时逻辑坐标，保留边界钳制和按键顺序，没有插值或尾随。
- 新增 `aim_sensitivity` 偏好。备份统一输出 schema v4，兼容恢复 v1/v2/v3，缺失视角设置时为 50%。新字段在备份边界校验有限数值及 0.01–1 范围。

## 设备采样

Android 16 小米平板 25053RP5CC、3200×2136，Xiaomi Keyboard、2.4G Xiaomi Mi USB Receiver Mouse，普通无线 ADB。旧设备包为 1.60 alpha Debug / code26，功能代码与正式 1.60 相同。两版 FrameInterval 均约 16.7ms。旧版悬浮窗已经由系统启用硬件加速，gfxinfo 为 Skia/Vulkan，本次没有把硬件加速标志当作修复。

用户在两版分别实体鼠标缓慢画圈、左右移动约 10 秒。1.61 反馈：**“已移动，明显更顺滑”**。

| 最近 120 帧之间的 119 个间隔 | 旧版 | 1.61 |
| --- | ---: | ---: |
| 约 16.7ms | 46 | 119 |
| 约 33.3ms | 68 | 0 |
| 约 50ms | 3 | 0 |
| 约 150 / 183ms | 各 1 | 0 |

gfxinfo 绘制耗时 p50/p90/p95/p99：旧版 8/14/15/19ms，新版 7/13/14/15ms。总帧数与采样时长不同，不据此计算 FPS。legacy jank 为 36.25% / 38.54%，与新指标 0.06% / 0.05% 差异很大，不能直接当作光标掉帧率。帧间隔和用户反馈支持节奏改善，未测硬件输入至屏幕的端到端延迟。

临时原始数据：`/tmp/keysync-perf-old-motion-frames.txt`、`/tmp/keysync-perf-new-motion-frames.txt`，不作为长期交接依赖。

## 验证

- 16 项相关测试通过：SoftwareCursorController、MappingBackupCodec、MumuProfileStore、GestureController，覆盖最新位置绘制、取消/钳制、备份兼容、新设置独立保存恢复和既有手势。
- 性能调试阶段：Lint 0 errors / 77 warnings / 6 information，Debug、四 ABI JNI 构建成功。发布阶段完整 96 项测试通过，Release/R8 和四 ABI 构建成功，正式包与源码提交核对见 [VALIDATION.md](VALIDATION.md)。
- 同迁移证书覆盖安装，安装前后 DataStore 文件逐字节一致，两游戏方案保留。
- APK：`artifacts/keysync-1.61-mouse-performance-debug.apk`，SHA-256 `81977e214c4fa090f8068a0222072c74414469f74b414382da65368a7f525fee`。
- 没有使用 adb root、su 或 root shell；应用使用原有已授权 RootService。

已在平板实际将视角从 50% 调至 51%，确认 1% 步进，再设回 50% 并收起悬浮球保存。对照升级前 DataStore：只新增 `aim_sensitivity=0.5`，原有全部键值字节一致（包括两游戏方案库与选择）。从 KeySync 打开终末地后，用户用实体 Alt＋鼠标验证新版 50%，确认：**“转向正常，容易控制”**。本轮两个问题均已获得用户实机确认。
