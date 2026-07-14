# KeySync Fix

本仓库是 [KeySync](https://github.com/aka-munan/keysync) 的修复版，基于 `v1.41-beta` 开发。

本页仅列出此修复版的变更。KeySync 的原有功能、使用方法、Shizuku 配置和构建说明请查看[KeySync 原仓库](https://github.com/aka-munan/keysync)。

## 已修复

- 拆分 WASD、普通按键和可取消按键的内部映射状态，修复 `S`、`1`～`4`、`Esc` 等键值冲突导致的崩溃和映射错乱。
- 修复启用按键映射后，触摸屏幕会导致键盘和鼠标失去响应的问题。
- 修复战斗中频繁进入、退出瞄准模式后，WASD 与鼠标视角无法同时使用的问题。
- 修复多点触控事件乱序、重复按下、已释放触点继续移动以及触控会话时间不一致的问题。
- 修复未绑定鼠标按键时的默认点击与瞄准模式交互，避免自定义映射影响正常鼠标操作。

## 已新增

- 支持将鼠标左键、中键、右键和侧键直接绑定为单个映射按键。
- 新增应用语言选项，支持跟随系统、简体中文和英文。
- 完成配置界面及相关操作文案的简体中文本地化。
- 修复版使用独立应用 ID `com.devoid.keysync.fix`，可与原版 KeySync 同时安装。
- 新增按键冲突、鼠标映射、中文设置和瞄准模式多点触控的回归测试。

## 下载

当前修复版：[`v1.41-beta-fix.1`](https://github.com/YoruSakura/keysync/releases/tag/v1.41-beta-fix.1)

## 上游与许可证

- 上游项目：[aka-munan/keysync](https://github.com/aka-munan/keysync)
- 本仓库继续使用原项目的 [Apache License 2.0](LICENSE)。
