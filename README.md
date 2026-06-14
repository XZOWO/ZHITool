# ZHITool

小米 17 系列背屏歌词工具：音乐播放时在背屏（副屏）实时重绘歌词页，支持逐字歌词、封面取色、息屏继续滚动。

作为 [lyricon（词幕）](https://github.com/tomakino/lyricon) 生态的订阅端获取歌词数据，用 root 把歌词页投放到副屏（display 1）。

## 功能

- 背屏实时歌词（歌曲信息 + 歌词 / Apple Music 式全量滚动）
- 逐字高亮、主副双行、对齐跟随、封面取色渐变背景
- 息屏继续滚动（背屏保活 + 配套修改版词幕）
- 锁屏背屏不返回桌面、后台保活（LSPosed hook）

## 许可证（License）

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整文本见 [LICENSE](LICENSE)。

之所以采用 GPL-3.0：本项目移植/改编了以下 **GPL-3.0** 项目的源码，按 copyleft 要求，整体须以 GPL-3.0 开源：

- 液态玻璃 UI（`app/.../ui/glass/`）、子屏不返回桌面与后台保活 hook（`app/.../xposed/`）移植/改编自 **REAREye**。
- root 投影 / 背屏保活思路参考 **MRSS**。

## 致谢与借鉴（Credits）

本项目站在以下开源项目的肩膀上，特此致谢：

| 项目 | 用途 | 许可证 | 链接 |
|---|---|---|---|
| **REAREye** | 液态玻璃 UI、子屏不返回桌面 / 后台保活 hook | GPL-3.0 | https://github.com/killerprojecte/REAREye |
| **MRSS**（MiRearScreenSwitcher） | root 背屏投影 / 保活思路 | GPL-3.0 | https://github.com/GoldenglowSusie |
| **lyricon（词幕）** | 歌词数据源 / 模型（subscriber、central、model 二进制依赖） | Apache-2.0 | https://github.com/tomakino/lyricon |
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
