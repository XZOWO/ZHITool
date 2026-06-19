# ZHITool

小米 17 系列**背屏工具**：把背屏（副屏 / display 1）变成一块可投屏、可截录、可显示信息的实用屏。一键把任意应用切到背屏，背屏截图 / 录屏，调 DPI / 旋转，背屏常亮与保活，插电充电动画，背屏通知推送，以及音乐播放时的实时背屏歌词。

通过 **Root** 把页面投放到副屏（display 1）并执行高权限操作；背屏歌词作为 [lyricon（词幕）](https://github.com/tomakino/lyricon) 生态的订阅端获取歌词数据。

## 功能

**投屏与媒体**
- 一键把当前前台应用切换到背屏，并可拉回主屏
- 背屏截图（保存到相册）
- 背屏录屏：黑色胶囊悬浮窗控制，可调码率，录到 Movies；可选内录系统声音 / 麦克风（合成）

**背屏显示控制**
- 背屏 DPI 调整 / 还原
- 背屏旋转 0° / 90° / 180° / 270°（应用自动复活）
- 背屏常亮、未投放时常亮、背屏遮盖检测（接近传感器）
- 锁屏背屏不返回桌面、后台保活（LSPosed hook）

**背屏增强**
- 充电动画：插电时背屏显示上升的电量液体动画
- 背屏通知推送：选定应用的通知在背屏弹出，支持隐私模式 / 勿扰跟随 / 仅倒扣手机时；多条堆叠浏览
- 实时背屏歌词：逐字高亮、主副双行、对齐跟随、封面取色渐变背景、息屏继续滚动

**触发方式**
- 应用内工具页、控制中心快捷开关（切换至背屏 / 背屏截图 / 背屏录屏）
- URI 调用 `zhitool://`（兼容 `mrss://`），便于 Tasker / MacroDroid 等自动化

## 使用前提

- 支持背屏的小米设备（小米 17 Pro / 17 Pro Max 等）
- **Root**（Magisk / KernelSU / APatch）
- LSPosed（背屏保活 hook、封面取色等需要；在作用域勾选 `Android` / `系统界面` / `背屏中心` / 本应用）
- 背屏歌词功能需安装 [lyricon（词幕）](https://github.com/XZOWO/lyricon)

## 许可证（License）

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整文本见 [LICENSE](LICENSE)。

之所以采用 GPL-3.0：本项目移植 / 改编了以下 **GPL-3.0** 项目的源码，按 copyleft 要求，整体须以 GPL-3.0 开源：

- 液态玻璃 UI（`app/.../ui/glass/`）、子屏不返回桌面与后台保活 hook（`app/.../xposed/`）移植 / 改编自 **REAREye**。
- 背屏投影 / 切换 / 截图 / 录屏 / DPI / 旋转 / 充电动画 / 通知推送等思路与流程参考 **MRSS**。

## 致谢与借鉴（Credits）

本项目站在以下开源项目的肩膀上，特此致谢：

| 项目 | 用途 | 许可证 | 链接 |
|---|---|---|---|
| **REAREye** | 液态玻璃 UI、子屏不返回桌面 / 后台保活 hook | GPL-3.0 | https://github.com/killerprojecte/REAREye |
| **MRSS**（MiRearScreenSwitcher） | 背屏投影 / 切换 / 截录 / 充电动画 / 通知等方案 | GPL-3.0 | https://github.com/GoldenglowSusie |
| **lyricon（词幕）** | 背屏歌词数据源 / 模型（subscriber、central、model 二进制依赖） | Apache-2.0 | https://github.com/tomakino/lyricon |
| **LyricProvider** | 各音乐 App 歌词提供者（配套生态） | Apache-2.0 | https://github.com/tomakino/LyricProvider |

> Apache-2.0 的部分（lyricon / LyricProvider）与 GPL-3.0 单向兼容，已并入本项目；其版权声明与归属见 [NOTICE](NOTICE)。

## 配套修改版词幕（lyricon）

为实现「息屏继续传歌词进度」，本项目对 lyricon 的 `central`（进度泵的息屏停止逻辑）做了改动。该改动基于 lyricon（Apache-2.0），修改说明与源码请见对应 fork：

- 原项目：https://github.com/tomakino/lyricon
- 修改版 fork：https://github.com/XZOWO/lyricon
- 本项目的修改：`lyric/bridge/central` 的 `PlayerBinder`——息屏不停进度泵，并为 Manual 模式（如 Apple Music）补本地外推。

## 构建

- AGP 9.1.1 / Gradle 9.5.0 / Kotlin 2.3.21 / compileSdk 37 / minSdk 27 / JDK 17
- `./gradlew :app:assembleRelease`

---

ZHITool 由其作者开发，借鉴上述项目。本项目与 REAREye / MRSS / lyricon / LyricProvider 的原作者无隶属关系。
