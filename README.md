# 豆啦啦 · Android 移植版

这是 Windows WPF/WebView2 版本的 Android 原生重写预览工程。当前源码版本为 **Android 0.2.1**；它不是把 EXE 重打包成 APK，也不是桌面版的完整替代品。实现状态以源码和下方边界为准，尚未宣称真实设备或平台网页登录已经验收。

## 原始 JS 保留与 Android 兼容边界

用户提供的 Dola30 MV3 JavaScript 已原样纳入 APK 资源目录 [`app/src/main/assets/dola30-original/`](app/src/main/assets/dola30-original/)；`duration-main.js`、`skill-pack-main.js`、`content-panel.js`、`host-bridge.js`、`service-worker.js`、`manifest.json`、`rules.json` 与响应样例均未改写或删除，`SOURCE-MANIFEST.json` 保存字节数和 SHA-256。

Android WebView 没有 Chrome MV3 的 `chrome.runtime`、`chrome.debugger`、`webRequest` 和桌面 WebView2 host bridge，因此不能假装原扩展全量等价运行。当前以 Android 安全子集为默认；Dola30 页面脚本可由用户显式确认后按原件注入，默认不开启。涉及平台能力改变或水印处理不作为 Android 功能承诺。

### Android WebView 适配层

APK 额外注入 [`app/src/main/assets/dola30-android-adapter.js`](app/src/main/assets/dola30-android-adapter.js) 与纯文本工具 [`app/src/main/assets/prompt-tools.js`](app/src/main/assets/prompt-tools.js)。在 `doubao.com`/`dola.com` 白名单页面上，菜单中的 **30S 工具**和页面浮动按钮可以由用户主动切换 15/30 秒本地状态，并把提示词中的时间轴转换为全片百分比；结果可复制后由用户自行检查和提交。

默认适配层保留原脚本使用的公开状态名（`setDola30s`、`getDola30sStatus`），不注入 Chrome API、不改写 `fetch`/XHR 请求，也不会自动发送。浏览器菜单另提供**默认关闭**的“原版 Dola30 请求适配”开关：用户确认后，原样 document-start 注入 `duration-main.js` 至 Dola/Doubao HTTPS 白名单页；其原逻辑可能更改页面状态、提示词及匹配请求中的用户文本。开关不代表平台能力承诺，也不承诺去水印；应用本身不自动提交。原始 MV3 文件继续原样打包，未删除任何函数。

## 已选择的后续架构：Chromium 扩展运行时

已选择方案 3：不再把 Android WebView 伪装成 MV3，而是迁移到带 Chromium Extension System 的 Android 浏览器内核。候选基线与迁移门槛记录在 [`docs/CHROMIUM_EXTENSION_ENGINE.md`](docs/CHROMIUM_EXTENSION_ENGINE.md)。在真正的扩展内核接入前，当前 WebView 版继续作为可构建、可回退版本；不会删除原始 JS 或现有功能。

首个独立扩展模块已加入 [`app/src/main/assets/fingerprint-privacy/`](app/src/main/assets/fingerprint-privacy/)：提供本地指纹诊断，以及用户可选的 Canvas、WebGL、离线音频、WebRTC 和媒体设备隐私限制。新增“洁净模式”后，扩展使用固定的低熵移动 UA/通用设备值，并删除高熵 Client Hints；不会随机轮换，也不修改 Cookie、Referer 或认证头。“严格隐私”可一次开启高影响限制。

**Android WebView 隐私开关已实现一个保守子集**：菜单中的“隐私洁净模式”会移除应用专属 UA 后缀并阻止第三方 Cookie；保留系统 WebView 实际 UA，不伪造机型、系统身份或高熵请求头。需确认后重载，可能影响跨站登录；它不保证匿名或消除广告。MV3 独立扩展中的 Canvas/Audio/WebRTC 限制与请求头规则仍未接入 WebView，Cobalt 也尚未接入。

新增真实 Chromium 回显测试和独立扩展打包流水线；Cobalt 构建入口已固定上游版本并接入原有扩展安装 stager，但需专用 Linux runner 才能执行。详见 [`docs/COBALT_BUILD_GATE.md`](docs/COBALT_BUILD_GATE.md)。内核验证 APK 与现有 WebView 应用分开，未完成全功能回归前不替换现有应用。

## 0.2.1 已实现范围

- **8 个隔离账号空间**：每个空间运行在独立 Android 进程与 Chromium 数据目录；空间内网页标签共享登录态，空间之间不共享 Cookie/网页存储。默认首页为 Dola（`https://www.dola.com/`）；其它空间可从设置修改。
- **移动浏览器**：HTTPS 地址、前进/后退、刷新、系统文件选择器上传；每个空间最多 **6 个按需创建的标签**。只恢复标签 URL 与当前选中标签，不恢复表单、历史记录或页面内状态。
- **书签**：每个空间最多 **200** 条本地书签；当前没有书签导出/跨设备同步。
- **空间入口移除**：非默认空间可从列表移除并将其槽位永久停用，计入 8 个槽位上限且目前没有恢复入口，防止后续新账号复用旧登录态。注意：当前无法验证 Android WebView 的单空间 Cookie/网页存储清理，因此移除入口不会擦除本机该空间的网页数据；需彻底清除时只能使用 Android 系统的应用存储清除（会影响全部空间）。
- **白名单资源观察**：仅观察页面已有 JSON/fetch/XHR/DOM/Performance 中的图片/视频 URL；不请求 `fallback_api`、不修改响应，也不保证原图或无水印版本。图片可由用户显式点击预览；已观察到视频 poster 时也可预览封面，但不播放视频。图片/封面预览仅 HTTPS、最多 8 MiB、拒绝重定向，不携带 WebView Cookie/授权头/Referer；视频保留“打开/下载”路径。
- **Cookie 手动迁移（有限快照）**：通过 SAF 由用户主动导出/导入当前完全相同 origin 的快照，仅保存名称和值。导入写入根路径、`Secure`、`HttpOnly`、`SameSite=Lax` 会话 Cookie；原始 Domain、Path、过期时间及其它属性会丢失，因此不是完整登录备份，不能保证恢复登录。快照含明文凭证，应仅保存在用户控制的位置并在用后删除。
- **下载中心**：基于 Android `DownloadManager`，提供状态刷新、打开、分享、SAF 另存、删除；下载记录上限 100 条，应用私有外部下载目录会在卸载时删除。不会自动附加 Cookie/认证头，需登录或已过期的链接可能失败或过期。
- **本地创作工作台**：快捷文本 CRUD、复制或填入当前网页；UTF-8 文本按空行导入并编辑分镜、三态手动状态、JSON 备份。不会伪造平台生成状态或自动发送。
- **Android WebView 30S 工具**：白名单页面的本地 15/30 秒状态、提示词时间轴转换、结果复制；所有动作均由用户确认，不拦截网络请求或自动发送。

## 用 GitHub Actions 编译

仓库为 `qwer654/anzhuo-dola`。在 **Actions → Android APK → Run workflow** 手动运行，或推送 `app/`、Gradle 文件、测试或工作流后自动运行。构建完成后从 workflow run 的 **Artifacts** 下载 `doulala-android-debug`，解压后可安装 `app-debug.apk`。构件还提供独立包名的 `app-deviceValidation.apk` 和对应 instrumentation APK，可与已有 debug 安装并存，供真机回归使用而无需卸载清除原应用数据；`app-deviceValidation-androidTest.apk` 可由 Android instrumentation runner 执行；两个 app APK 均附 SHA-256 文件。

主工作流使用 GitHub 托管的 Android SDK、JDK 17、Gradle 8.9 和 Node.js 20，执行 Node 测试，随后运行 `testDebugUnitTest lintDebug assembleDebug assembleDeviceValidation`、APK 签名验证并检查 APK 内原始 JS SHA-256。`Android UI smoke` 会在 Android 相关源码或测试变更的 push / pull request 时运行，也可手动触发；它在 Android 15/API 35 模拟器执行 `connectedDeviceValidationAndroidTest`，包括 UI 隔离检查和 WebView document-start 适配测试。未运行前不视为通过。审核和逐项差距见 [`docs/WEBVIEW_PARITY_AUDIT.md`](docs/WEBVIEW_PARITY_AUDIT.md)。

工作流生成 debug APK 与可并装的 deviceValidation APK（均为临时 debug 签名）。正式发布需要用户自己的签名密钥与 release secrets，不能把 keystore 提交到仓库。debug 签名在每次 runner 上可能不同，升级时可能需要卸载并造成应用数据丢失；安装/升级前请先导出工作台 JSON 和需要保留的文件。最低系统目标仍按工程配置，以 Android 9（API 28）为预览下限；真实机兼容性需另行验收。

## 本机编译

```powershell
.\tools\bootstrap.ps1 -ReviewSdkLicenses -InstallSdk
.\build.ps1
```

脚本不会替用户接受 Android SDK 许可。没有本机 SDK 时可使用 GitHub Actions。

## 未移植或明确不承诺的桌面能力

完整指纹/代理伪装、Windows 代理认证与链式代理、CDP 深度嗅探与 `Fetch.fulfillRequest`、批量自动生成/发送/后台任务、遮罩/人脸/参考音频、创作中心/百宝箱、桌面本地 API、Windows DLL/注册表/机器码、Chrome 扩展，以及桌面版的完整 Cookie 属性与本地存储迁移，均未等价移植。30S 适配层只提供本地状态和提示词转换，不等价于平台能力解锁。详见 [`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md)。
