# CK 导入安全迭代（0.3.4）

新增本机带随机密钥的 CK 摘要导入档案：完全相同 CK 跨空间导入会被拒绝，同一空间保留稳定 WebView 目录；归档保留档案，明确清除/删除仅释放本空间摘要。它不是服务端账号/设备绑定，不承诺解决 `710022002` 或恢复已过期会话，不注入/伪造网站设备标识，不自动发消息测试。详见 [会话导入安全与证据边界](docs/SESSION_IMPORT_SAFETY.md)。

当前源码版本为 0.3.4；下方旧版本功能说明为历史记录，以当前运行时策略和最新说明为准。原用户 JS 字节不变。GitHub 临时签名与已有手机安装可能不匹配，本轮不卸载或清除旧安装数据。

# 新附件其它页面 JS 适配（0.3.2）

原始 `duration-main.js` 已接入 document-idle 阶段；扩展资源面板由 Android 原生资源面板和独立只读观察器适配，未伪造 Chrome 后台/Debugger。原开启状态不会自动授权新增逻辑；需在本空间“30S 工具”确认新增页面功能。详见 [其它 JS 适配范围](docs/OTHER_JS_ADAPTATION.md)。

# 64 空间 UI 迭代（0.3.0）

当前实现、Google 登录限制和视频自动串联的未接入项见 [64 空间迭代说明](docs/SPACES64.md)。本轮真机包为 `com.doulala.android.spaces64`，与旧验证包并存，不删除其账号数据。

> 当前规则以文末“新附件 v1.6.14 替换运行时”为准；之前移植状态仅为历史记录。

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
- **账号空间归档/恢复**：非默认空间可归档以从主列表隐藏，槽位仍计入 8 个名额，不会分配给新账号；可从“已归档空间”恢复到同一槽位，并保留原 Cookie、网页存储、标签和本机状态。归档不是安全擦除；当前不提供单空间数据删除，系统清除应用数据会清除全部空间。
- **白名单资源观察**：仅观察页面已有 JSON/fetch/XHR/DOM/Performance 中的图片/视频 URL；不请求 `fallback_api`、不修改响应，也不保证原图或无水印版本。图片可由用户显式点击预览；已观察到视频 poster 时也可预览封面，但不播放视频。图片/封面预览仅 HTTPS、最多 8 MiB、拒绝重定向，不携带 WebView Cookie/授权头/Referer；视频保留“打开/下载”路径。
- **Cookie / Dola CK 手动迁移（有限快照）**：通过 SAF 由用户主动导出/导入当前完全相同 origin 的快照，仅保存名称和值；另支持 DolaFetchCk 导出的单账号 JSON 和 CK 汇总 JSON，每次只选择一个账号，且仅可导入 HTTPS `dola.com` 页面。导入仅使用 `cookie` 字段，忽略 UA、指纹、代理等其它元数据；这些 CK 文件不含来源站点，导入前需由用户确认来源确为 Dola。导入写入当前主机根路径、`Secure`、`HttpOnly`、`SameSite=Lax` 会话 Cookie；原始 Domain、Path、过期时间及其它属性会丢失，因此不是完整登录备份，不能保证恢复登录。CK 与快照都包含登录凭证，应仅处理本人账号文件，不上传仓库或 CI，并在用后删除。
- **旧 Dola CK 兼容方式（显式选择）**：CK 确认框另有“Dola兼容方式”，需二次确认。它先替换当前主机同名根路径 Cookie，再推定为 `dola.com` 域 Cookie（`__Host-` 仍为 host-only）；仅 `passport_csrf_token` 与 `passport_csrf_token_default` 可由网页脚本读取，所有其它 Cookie 保持 `HttpOnly`。使用 `Secure / SameSite=Lax` 会话 Cookie，不改 UA、设备指纹、代理，不延长服务端有效期；普通快照不提供此方式。**这是旧 CK 缺属性时的有限兼容选项，不是无损恢复，也未经真实 Dola 对话稳定性验收**。部分失败不会自动回滚，其它路径旧 Cookie 不会清除；建议先导出快照。若仍掉线，应在该空间正常登录，而不是反复导入旧 CK。

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

## WebView 文档开始页面脚本（2026-10-12）

- 每个空间菜单新增 **30s（本空间）**；默认关闭、独立持久保存，不从旧版 duration 开关自动开启。切换需确认并重建本空间标签 WebView，未提交内容可能丢失；其他空间不受影响。
- 开启后仅在 Dola HTTPS 页面以单条 document-start 链按附件顺序执行 `dola30-user-source-v1.6.9/skill-pack-main.js` → `media-upload-main.js`，两个源文件字节不变。首次页面脚本执行前种入该空间的默认 30s 状态；页面本地时长仍可单独调整。关闭后新文档不安装二者钩子，并恢复默认 15s 状态。
- 旧版 `duration-main.js` 的请求适配保留其独立开关。没有自动打开旧版请求改写，也没有注入 `content-panel.js` 或 service worker；Chrome Debugger、runtime/扩展面板后台交互明确不可用。不创建假的 chrome API。
- `skill-pack` 会更改页面收到的本地能力配置显示，不能据此判定服务器支持；`media-upload` 安装页面事件桥和地址规范化，但依赖站点私有 Webpack/上传管线。本次未复刻 Chrome 上传面板，未自动上传、提交或验证真实生成。
- “30S 工具”区分设置已保存、注入注册不可用、两个脚本实际执行标记以及服务器未验证状态。脚本加载不等于上传成功或实际 30 秒生成。

## 新附件 v1.6.14 替换运行时（2026-10-12，当前有效规则）

来源：用户本轮指定的 `D:/新建文件夹 (2)/dola30-新/`，不是此前 v1.6.9。当前文档开始注入仅使用新附件原字节 `skill-pack-main.js` → `media-upload-main.js`，限定 HTTPS Dola 页面。

每个空间独立保存 **30s（本空间）**，默认关闭。新开关键独立于历史 `originalDola30`/`dola30PageScripts`，旧设置不会自动启用新代码。切换需确认并重建该空间标签；Cookie/账号数据不删除。

**所有原使用的外部 JS 均仅备份、不再运行**：旧 duration、skill-pack、media-upload、Android 适配器、资源观察、提示词工具、隐私页面脚本。旧启用入口不再能重新启用它们；历史脚本 instrumentation 留在 `tests/legacy-backup/`，不当作新运行时验收。

因此旧页面资源观察/时间轴工具/Canvas、WebGL、音频、WebRTC 等页面 JS 保护已停用，界面明确说明。Cookie/CK、标准文件选择、原生第三方 Cookie/定位隐私开关、账号空间和原生编辑功能仍保留。

Chrome 专属 debugger、后台 worker 与扩展 content-panel 不注入、不模拟 chrome API。新附件 duration（document_idle，非本轮指定的两个脚本）也仅保留源基线，不执行。完整新附件另外留有本机私有备份，background worker 不随公开包上传。

`RUNTIME-SOURCE-POLICY.json` 记录唯一激活的两文件及全部不执行 JS 的哈希；APK verifier 校验旧备份基线与新 5 文件源基线。脚本执行标记不是实际上传成功、后端支持 30 秒或生成成功的证明。

新附件验收包为 `newSourceValidation`（包名 `com.doulala.android.new30s`、名称“豆啦啦 新JS验证”），可与旧包并存，不要求卸载或清除旧账号空间。模拟器行为测试使用 `deviceValidation` 包，二者复用同一份 main 源码；新包只改变应用标识、版本后缀和名称，其独立安装尚需真机验证。
