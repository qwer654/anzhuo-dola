# 豆啦啦 · Android 移植版

这是 Windows WPF/WebView2 版本的 Android 原生重写预览工程。当前源码版本为 **Android 0.2.1**；它不是把 EXE 重打包成 APK，也不是桌面版的完整替代品。实现状态以源码和下方边界为准，尚未宣称真实设备或平台网页登录已经验收。

## 原始 JS 保留与 Android 兼容边界

用户提供的 Dola30 MV3 JavaScript 已原样纳入 APK 资源目录 [`app/src/main/assets/dola30-original/`](app/src/main/assets/dola30-original/)；`duration-main.js`、`skill-pack-main.js`、`content-panel.js`、`host-bridge.js`、`service-worker.js`、`manifest.json`、`rules.json` 与响应样例均未改写或删除，`SOURCE-MANIFEST.json` 保存字节数和 SHA-256。

Android WebView 没有 Chrome MV3 的 `chrome.runtime`、`chrome.debugger`、`webRequest` 和桌面 WebView2 host bridge，因此这些原脚本会作为编译资源保留，但不会假装已经等价执行。当前运行时继续使用 Android 安全子集；原脚本中涉及拦截平台请求、移除水印或绕过平台能力限制的部分不会被自动启用。

### Android WebView 适配层

APK 额外注入 [`app/src/main/assets/dola30-android-adapter.js`](app/src/main/assets/dola30-android-adapter.js) 与纯文本工具 [`app/src/main/assets/prompt-tools.js`](app/src/main/assets/prompt-tools.js)。在 `doubao.com`/`dola.com` 白名单页面上，菜单中的 **30S 工具**和页面浮动按钮可以由用户主动切换 15/30 秒本地状态，并把提示词中的时间轴转换为全片百分比；结果可复制后由用户自行检查和提交。

适配层保留原脚本使用的公开状态名（`setDola30s`、`getDola30sStatus`）以便页面兼容，但不会注入 Chrome API，不改写 `fetch`/XHR 请求，不伪造平台能力，不移除水印，也不会自动发送。平台最终是否提供 30 秒能力仍由网页和账号权限决定。原始 MV3 文件继续原样打包，未删除任何函数。

## 已选择的后续架构：Chromium 扩展运行时

已选择方案 3：不再把 Android WebView 伪装成 MV3，而是迁移到带 Chromium Extension System 的 Android 浏览器内核。候选基线与迁移门槛记录在 [`docs/CHROMIUM_EXTENSION_ENGINE.md`](docs/CHROMIUM_EXTENSION_ENGINE.md)。在真正的扩展内核接入前，当前 WebView 版继续作为可构建、可回退版本；不会删除原始 JS 或现有功能。

首个独立扩展模块已加入 [`app/src/main/assets/fingerprint-privacy/`](app/src/main/assets/fingerprint-privacy/)：提供本地指纹诊断，以及用户主动开启的 Canvas/WebGL 隐私限制。它不上传报告、不伪造 User-Agent 或请求头。

## 0.2.0 已实现范围

- **8 个隔离账号空间**：每个空间运行在独立 Android 进程与 Chromium 数据目录；空间内网页标签共享登录态，空间之间不共享 Cookie/网页存储。
- **移动浏览器**：HTTPS 地址、前进/后退、刷新、系统文件选择器上传；每个空间最多 **6 个按需创建的标签**。只恢复标签 URL 与当前选中标签，不恢复表单、历史记录或页面内状态。
- **书签**：每个空间最多 **200** 条本地书签；当前没有书签导出/跨设备同步。
- **白名单资源观察**：仅观察页面已有 JSON/fetch/XHR/DOM/Performance 中的图片/视频 URL；不请求 `fallback_api`、不修改响应，也不保证原图或无水印版本。
- **Cookie 手动迁移（有限快照）**：通过 SAF 由用户主动导出/导入当前完全相同 origin 的快照，仅保存名称和值。导入写入根路径、`Secure`、`HttpOnly`、`SameSite=Lax` 会话 Cookie；原始 Domain、Path、过期时间及其它属性会丢失，因此不是完整登录备份，不能保证恢复登录。快照含明文凭证，应仅保存在用户控制的位置并在用后删除。
- **下载中心**：基于 Android `DownloadManager`，提供状态刷新、打开、分享、SAF 另存、删除；下载记录上限 100 条，应用私有外部下载目录会在卸载时删除。不会自动附加 Cookie/认证头，需登录或已过期的链接可能失败或过期。
- **本地创作工作台**：快捷文本 CRUD、复制或填入当前网页；UTF-8 文本按空行导入并编辑分镜、三态手动状态、JSON 备份。不会伪造平台生成状态或自动发送。
- **Android WebView 30S 工具**：白名单页面的本地 15/30 秒状态、提示词时间轴转换、结果复制；所有动作均由用户确认，不拦截网络请求或自动发送。

## 用 GitHub Actions 编译

仓库为 `qwer654/anzhuo-dola`。在 **Actions → Android APK → Run workflow** 手动运行，或推送 `app/`、Gradle 文件、测试或工作流后自动运行。构建完成后从 workflow run 的 **Artifacts** 下载 `doulala-android-debug`，解压后安装 `app-debug.apk`，并可用同目录 SHA-256 文件校验完整性。

主工作流使用 GitHub 托管的 Android SDK、JDK 17、Gradle 8.9 和 Node.js 20，执行 `node --test tests/media-probe.test.cjs tests/dola30-source.test.cjs tests/dola30-android-adapter.test.cjs`，随后运行 `testDebugUnitTest lintDebug assembleDebug`、APK 签名验证并上传报告。Java 单元测试已编写，但当前没有云端验证结果，不能在此声明通过。另有手动触发的 `Android UI smoke` 工作流，在 Android 15/API 35 模拟器运行 `connectedDebugAndroidTest`；当前没有该 0.2.1 工作流运行结果。

工作流只生成 debug APK。正式发布需要用户自己的签名密钥与 release secrets，不能把 keystore 提交到仓库。debug 签名在每次 runner 上可能不同，升级时可能需要卸载并造成应用数据丢失；安装/升级前请先导出工作台 JSON 和需要保留的文件。最低系统目标仍按工程配置，以 Android 9（API 28）为预览下限；真实机兼容性需另行验收。

## 本机编译

```powershell
.\tools\bootstrap.ps1 -ReviewSdkLicenses -InstallSdk
.\build.ps1
```

脚本不会替用户接受 Android SDK 许可。没有本机 SDK 时可使用 GitHub Actions。

## 未移植或明确不承诺的桌面能力

完整指纹/代理伪装、Windows 代理认证与链式代理、CDP 深度嗅探与 `Fetch.fulfillRequest`、批量自动生成/发送/后台任务、遮罩/人脸/参考音频、创作中心/百宝箱、桌面本地 API、Windows DLL/注册表/机器码、Chrome 扩展，以及桌面版的完整 Cookie 属性与本地存储迁移，均未等价移植。30S 适配层只提供本地状态和提示词转换，不等价于平台能力解锁。详见 [`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md)。
