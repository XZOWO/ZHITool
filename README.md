# ZHITool

小米 17 系列**背屏工具**：把背屏（副屏 / display 1）变成一块可投屏、可截录、可显示信息的实用屏。一键把任意应用切到背屏，背屏截图 / 录屏，调 DPI / 旋转，背屏常亮与保活，插电充电动画，背屏通知推送，以及音乐播放时的实时背屏歌词。

通过 **Root** 把页面投放到副屏（display 1）并执行高权限操作；背屏歌词支持两套并行数据源——[lyricon（词幕）](https://github.com/tomakino/lyricon) 生态的订阅端，或 [SuperLyric](https://github.com/HChenX/SuperLyric) 的实时逐句广播。播放进度由 ZHITool 直接读取系统媒体会话，息屏也能继续滚动。

## 功能

**投屏与媒体**
- 一键把当前前台应用切换到背屏，并可拉回主屏
- 背屏截图（保存到相册）
- 背屏录屏：黑色胶囊悬浮窗控制

**背屏显示控制**
- 背屏 DPI 调整 / 还原
- 背屏旋转 0° / 90° / 180° / 270°（应用自动复活）
- 背屏常亮、未投放时常亮、背屏遮盖检测（接近传感器）
- 锁屏背屏不返回桌面、后台保活（LSPosed hook）

**背屏增强**
- 充电动画：插电时背屏显示上升的电量液体动画
- 背屏通知推送：选定应用的通知在背屏弹出，支持隐私模式 / 勿扰跟随 / 仅倒扣手机时；多条堆叠浏览
- 实时背屏歌词（词幕）：逐字高亮、主副双行、对齐跟随、封面取色渐变背景、息屏继续滚动
- 实时背屏歌词（SuperLyric）：大封面 + 当前句版式，逐字 / 逐词「随机升起」进场动画，独立字号

**触发方式**
- 应用内工具页、控制中心快捷开关（切换至背屏 / 背屏截图 / 背屏录屏）
- URI 调用 `zhitool://`（兼容 `mrss://`），便于 Tasker / MacroDroid 等自动化

## 使用前提

- 支持背屏的小米设备（小米 17 Pro / 17 Pro Max 等）
- **Root**（Magisk / KernelSU / APatch）
- LSPosed（背屏保活 hook、封面取色等需要；在作用域勾选 `Android` / `系统界面` / `背屏中心` / 本应用）
- 背屏歌词需任选一套数据源：
  - **词幕（lyricon）**：目前需安装[修改版 fork 词幕](https://github.com/XZOWO/lyricon)（官方词幕的新版桥接暂未适配，后续支持），并安装对应音乐 App 的歌词提供者 [LyricProvider](https://github.com/tomakino/LyricProvider)（缺 provider 取不到歌词）
  - **SuperLyric**：安装 [SuperLyric](https://github.com/HChenX/SuperLyric) 模块即可，实时逐句，无需词幕 / provider

## 许可证（License）

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整文本见 [LICENSE](LICENSE)。

## 致谢与借鉴（Credits）

本项目站在以下开源项目的肩膀上，特此致谢：

| 项目 | 许可证 | 链接 |
|---|---|---|
| **REAREye** | GPL-3.0 | https://github.com/killerprojecte/REAREye |
| **MRSS**（MiRearScreenSwitcher） | GPL-3.0 | https://github.com/GoldenglowSusie |
| **lyricon（词幕）** | Apache-2.0 | https://github.com/tomakino/lyricon |
| **LyricProvider** | Apache-2.0 | https://github.com/tomakino/LyricProvider |
| **SuperLyric / SuperLyricApi** | LGPL-2.1 | https://github.com/HChenX/SuperLyric |

> Apache-2.0 的部分（lyricon / LyricProvider）与 GPL-3.0 单向兼容，已并入本项目；SuperLyricApi（LGPL-2.1）作为库链接调用。各第三方版权声明与归属见 [NOTICE](NOTICE)。

## 配套词幕（lyricon）

词幕数据源目前需配合 fork 版词幕：官方 lyricon 的新版桥接（ConnectionRegistry 等）暂未适配，后续支持。fork 基于 lyricon（Apache-2.0），修改说明与源码见对应 fork：

- 原项目：https://github.com/tomakino/lyricon
- 修改版 fork：https://github.com/XZOWO/lyricon

> 注：自 v1.0.0 起播放进度改由系统媒体会话自驱动，息屏滚动不再依赖词幕侧改动；fork 当前主要用于桥接兼容。

## 构建

- AGP 9.1.1 / Gradle 9.5.0 / Kotlin 2.3.21 / compileSdk 37 / minSdk 27 / JDK 17
- `./gradlew :app:assembleRelease`

---

ZHITool 由其作者开发，借鉴上述项目。本项目与 REAREye / MRSS / lyricon / LyricProvider 的原作者无隶属关系。
