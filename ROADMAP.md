# 🗺️ Haifeng Player (海风播放器) - 功能路线图 (Roadmap)

本文档记录了在完成底层架构升级到 **AndroidX Media3** 之后，海风播放器可实现的功能增强特性与演进计划。

---

## 📌 规划总览与优先级

| 阶段 | 特性模块 | 核心收益 | 难度 / 复杂度 |
| :--- | :--- | :--- | :--- |
| **Phase 1: 车机交互增强** | 车机自定义操作按钮 (CommandButtons) | 播放页直达：单曲循环、随机播放、快捷切源 | 🟢 低 |
| **Phase 1: 车机交互增强** | 快进 / 快退与倍速调整 | 大幅提升“懒人听书”有声书/播客的收听体验 | 🟢 低 |
| **Phase 2: 队列与媒体透传** | 播放队列透传 (Playback Queue) | 在车机 Now Playing 界面查看并点播待播歌单 | 🟡 中 |
| **Phase 2: 队列与媒体透传** | 现代化车机浏览卡片 (Content Style) | 车机切源菜单升级为网格图标卡片 (Grid Card) | 🟢 低 |
| **Phase 3: 体验与生态拓展** | 第三方音乐源拓展 | 支持网易云音乐、酷狗、Apple Music 等更多应用 | 🟡 中 |
| **Phase 3: 体验与生态拓展** | 车机端全屏歌词模式优化 | 优化仪表盘/中控屏全屏歌词刷新平滑度 | 🔴 高 |

---

## 🚀 详细功能设计

### Phase 1: 车机交互增强 (In-Car Controls Enhancement)

#### 1.1 车机播放页自定义操作按钮 (Custom Command Buttons)
- **现状**：Android Auto 播放界面仅有标准的播放/暂停、上一首、下一首。循环/随机模式或切源只能回到外层菜单。
- **技术实现**：
  - 基于 Media3 的 `setCustomLayout` 与 `setMediaButtonPreferences`。
  - 在播放控制栏两侧点亮项目已有的矢量图标：
    - `ic_shuffle_24dp`：随机播放切换。
    - `ic_repeat_24dp` / `ic_repeat_one_24dp`：循环模式（列表循环 / 单曲循环）。
    - 快捷切源动作：一键在 QQ音乐 / 汽水 / 懒人听书之间轮换。
- **预期收益**：驾驶过程中双手无需离开方向盘或盲操切出播放页。

#### 1.2 快进 / 快退与有声书倍速支持 (Podcast & Audiobook Optimizations)
- **现状**：车机上一首/下一首对于小说和有声书来说会直接跳章，误触成本高。
- **技术实现**：
  - 在 `RemoteMirrorPlayer` 中开启 `COMMAND_SEEK_BACK` (快退 15s) 和 `COMMAND_SEEK_FORWARD` (快进 30s)。
  - 支持 `PlaybackParameters` 倍速透传（1.0x / 1.25x / 1.5x / 2.0x），并在状态更新时同步给车机。
- **预期收益**：听书场景下可以精准重听遗漏内容。

---

### Phase 2: 队列与媒体透传 (Queue & Media Styling)

#### 2.1 播放队列透传 (Playback Queue / Playlist)
- **现状**：车机端仅将当前播放的一首歌封装成单一 `MediaItem`，车机右上角队列图标置灰不可点。
- **技术实现**：
  - 监听第三方播放器的 `remoteCtrl.getQueue()` 与 `onQueueChanged()`。
  - 将队列映射为 Media3 的 `SimpleBasePlayer.MediaItemData` 列表注入 `RemoteMirrorPlayer` 的 `setPlaylist()`。
  - 在 `handleSeek(int mediaItemIndex, ...)` 中提取对应的 `queueId`，通过 `remoteCtrl.getTransportControls().skipToQueueItem(queueId)` 触发切歌。
- **互不冲突保证**：
  - “播放队列”是播放页（Now Playing）的歌曲清单，与外层菜单（Browse Tree）的“切换播放源”分属不同 API，彼此独立。

#### 2.2 现代化车机浏览卡片样式 (Media3 Content Style API)
- **现状**：车机“切换播放源”文件夹采用标准文本列表。
- **技术实现**：
  - 使用 `MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE = EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM`。
  - 为“QQ音乐”、“懒人听书”、“汽水音乐”添加高清矢量大卡片。
  - 支持在听书项目展示“收听百分比”（`EXTRAS_KEY_COMPLETION_PERCENTAGE`）。
- **预期收益**：车机大屏视觉质感显著提升，更符合现代车机设计规范。

---

### Phase 3: 体验与生态拓展 (Ecosystem & Visuals)

#### 3.1 拓展更多国内音频应用源
- **候选应用**：
  - 网易云音乐 (NetEase Cloud Music)
  - 酷狗音乐 / 酷我音乐
  - 喜马拉雅 (Ximalaya)
- **技术路径**：
  - 分析各应用公开暴露的 `MediaBrowserService` 与 `MediaSession` Token，将其接入 `MyMusicService` 的 `SourceConfig` 注册表与 `MusicSessionSniffer` 探测器。

#### 3.2 歌词同步与渲染引擎优化
- **现状**：车机端显示歌词通过元数据副标题/自定义通知或悬浮窗刷新。
- **技术优化**：
  - 结合 Media3 高频且低能耗的 `PositionSupplier` 插值算法，使歌词滚动的毫秒级时间戳更平滑，降低跨进程广播的唤醒频率。

---

## 📅 版本规划建议

- **v1.1 (Next)**:
  - [ ] 增加车机循环模式与随机播放 CommandButton。
  - [ ] 增加车机快进 30s / 快退 15s 按钮支持。
- **v1.2**:
  - [ ] 接入第三方应用播放队列透传 (Queue / Playlist)。
  - [ ] 升级车机切源菜单为 Grid Item 样式。
- **v2.0**:
  - [ ] 引入网易云音乐与喜马拉雅源适配。
  - [ ] 深度重构歌词引擎。
