# KeySync Fix

[KeySync](https://github.com/aka-munan/keysync) 的键鼠触控映射修复版，支持多游戏、多份 MuMu JSON 方案和游戏内触点校准。

## 下载

正式版 **1.60**：[下载 APK](https://github.com/YoruSakura/keysync/releases/download/v1.60/KeySyncFix-v1.60.apk) · [发布说明](https://github.com/YoruSakura/keysync/releases/tag/v1.60)

Android 10 及以上，单个 APK 包含 arm64-v8a、armeabi-v7a、x86、x86_64。应用 ID 为 `com.devoid.keysync.touch`，沿用现有签名，可覆盖同签名的触控实验版并保留配置；历史 `com.devoid.keysync.fix` 是另一安装包。

## 本 fork 的主要改进

- 重写触控会话和多指事件队列，修复 WASD 键值冲突、粘键、模式切换及过期触点导致的输入失效。
- 支持 Root 独占键鼠捕获和手动选择的 Shizuku 输入模式；切出目标游戏时释放输入，切回后恢复捕获。
- 每个游戏可独立导入、选择、重命名和删除多份 MuMu JSON；退出到 KeySync 切换方案后重新启动游戏即可生效。
- 在游戏内展开悬浮球，可拖动校准按键、轮盘、镜头锚点及宏点击位置，保存到当前方案。
- 鼠标模式滚轮滑动页面，瞄准模式滚轮缩放镜头，页面滚动采用普通 PC 方向。
- 支持当前光标点击、按住鼠标、延时、组合按键及曲线宏；区分 Alt 切换瞄准与按住 Alt 拖动视角。
- 提供简体中文界面、输入状态和配置备份。

## 已验证状态

2026-11-02，在 Android 16 小米平板、实体键盘和 2.4G 鼠标上确认：

| 游戏／功能 | 实测结果 |
| --- | --- |
| 终末地 | WASD 四方向正常且松键即停；Alt 转视角、F1–F4 切人、Space 跳跃、瞄准后左键攻击正常 |
| 明日方舟 | C 点击、F 时停、D 一键技能、A 一键撤退、S 快速选取与松键恢复正常 |
| 通用 | 多游戏独立方案、JSON 导入、布局校准保存／取消、滚轮滑动与缩放通过验证 |

明日方舟 E 对轴、生息演算 Alt 拖视角、肉鸽 W／1 尚待对应场景验证。旧右键“划火柴”宏保留兼容；新版游戏已支持暂停部署。

完整构建结果与未完成项见 [验证记录](docs/VALIDATION.md) 和 [待办](docs/TODO.md)。源方案及平板校准示例位于 [参考文档](参考文档/)；不同屏幕比例下可在游戏内重新校准。

## 构建与开发

使用 JDK 17、Android SDK 35、NDK 27.0.12077973 和 CMake 3.22.1：

```sh
./gradlew --max-workers=1 testDebugUnitTest lintDebug assembleRelease
```

签名参数使用 Gradle 属性 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，密钥与本机配置不纳入仓库。GitHub Actions 执行测试、Lint 和 Release 构建；正式安装包单独核验后发布。

原项目的基础使用方法与 Shizuku 配置请参考 [KeySync 原仓库](https://github.com/aka-munan/keysync)。触控引擎实现见 [TOUCH_CORE.md](docs/TOUCH_CORE.md)。

## 上游与许可证

- 上游：[aka-munan/keysync](https://github.com/aka-munan/keysync)
- 沿用 [Apache License 2.0](LICENSE)。
