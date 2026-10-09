# Android 扩展运行时迁移决策

## 结论

已选择 **方案 3：改用带扩展运行时的 Android Chromium 浏览器内核**。这不是继续增强 `android.webkit.WebView`，而是把当前 WebView 浏览器壳迁移到真正带 Chromium Extension System 的浏览器工程。

当前候选基线为 [Cobalt](https://github.com/AurielSolaris/Cobalt)：其公开构建说明标明基于 Chromium 140，Android 已编译扩展子系统，并同时运行 MV2 与 MV3。它的 README 还说明 Android 壳和 Chromium 引擎是分层的，便于把本项目的空间、标签、书签和下载 UI 迁移到新壳中。

## 不会做的事情

- 不删除或改写 `app/src/main/assets/dola30-original/` 中的原始 JS；迁移前后继续用 `SOURCE-MANIFEST.json` 校验字节和 SHA-256。
- 不把 `dola30-android-adapter.js` 冒充成 MV3 等价物；迁移到扩展运行时后，原始 `manifest.json`、Service Worker、content scripts 和扩展页面应由扩展系统加载。
- 不重新实现用于绕过平台能力、水印或授权检查的请求篡改逻辑。合法的页面增强、状态管理、提示词转换和用户确认流程可以继续迁移。

## 目标拓扑

```text
当前 MainActivity/BrowserActivity
        │
        └── WebView（仅页面 JS + Android bridge）  ← 迁移后移除为默认引擎

目标：Android Chromium browser shell
        ├── Chromium 140 extension system
        │     ├── manifest.json
        │     ├── service-worker.js
        │     ├── content-panel.js
        │     └── skill-pack-main.js / duration-main.js
        └── Doulala shell
              ├── 8 个空间与隔离 profile
              ├── 标签、书签、下载中心
              └── Android 文件选择和工作台桥接
```

## 迁移验收门槛

1. 在扩展管理页加载原始 `manifest.json`，不改文件内容。
2. Service Worker 能启动，扩展状态在进程重启后仍可恢复。
3. `content_scripts` 在指定 host 上运行，扩展消息通道可用。
4. `chrome.storage`、`chrome.tabs`、`chrome.scripting` 等原脚本实际调用路径逐项通过测试。
5. 资源、Cookie、下载和 8 个空间功能回归测试通过。
6. 与当前 Android WebView 版并行保留，确认新内核稳定后再切换默认入口。

## 指纹隐私扩展

首个可移植模块已放在 `app/src/main/assets/fingerprint-privacy/`，是独立 MV3 扩展，不修改原始 Dola30 资源。它提供本地报告（UA、平台、语言、时区、屏幕、Canvas/WebGL 可见信号）以及用户主动开启的 Canvas 读回限制和 WebGL 调试渲染器隐藏。报告不联网；默认不改变页面行为。

该模块不伪造 User-Agent、设备身份或请求头，也不用于绕过平台风控。真正的浏览器级防指纹仍以 Cobalt 内核的实现为准。

## 构建现实

这条路线需要完整 Chromium 源码、GN/Ninja、Android SDK/NDK、较大的磁盘和 GitHub Actions 专用 runner；不是在现有 `app/build.gradle` 增加一个依赖就能完成。当前仓库仍可构建 WebView 版，直到 Chromium 引擎迁移完成。
