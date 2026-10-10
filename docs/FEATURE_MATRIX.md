# 功能矩阵与验收边界（Android 0.2.1）

来源：当前 `android-port/app/src/main/java/com/doulala/android` 实现与 Android 工作流。下表的“已实现”表示源码已提供对应路径，不代表真实设备、第三方网站或云端工作流已经验收通过。

桌面能力对照来源：桌面发布包的 `DolaMultiBrowser.deps.json`（版本 2.3.1）、现有旧版 1.4.8 源码、当前 `Extensions/dola30`（MV3 1.6.11）以及仅含部分现有类的新版 `v231` 源码。桌面来源用于识别移植范围，不将未知或缺失组件推定为 Android 已完成。

| 能力 | 0.2.1 状态 | 实现与限制 |
|---|---|---|
| 豆包/Dola/即梦/千问/云雀入口 | 已实现 | 可编辑 HTTPS 首页；第三方网站可能拒绝 WebView 登录，需真机验证。 |
| 8 个隔离账号空间 | 已实现 | 8 个独立 Android 进程/Chromium 数据目录；空间内共享登录，空间之间不共享 Cookie/网页存储。 |
| 浏览、前进/后退、刷新、上传 | 已实现 | HTTPS WebView；上传使用系统 SAF 文档选择器；不允许 `file://`。 |
| 多标签 | 已实现（有限） | 每空间最多 6 个按需标签；读取旧版 URL 数组；尽力恢复大小受限的 WebView 前进/后退历史，但不恢复表单、DOM 或 JavaScript 页面状态。 |
| 书签 | 已实现（有限） | 每空间本地最多 200 条；不可导出、同步或跨空间共享。 |
| 白名单页面媒体观察 | 已实现（安全子集） | 观察页面已有 JSON/fetch/XHR/DOM/Performance 的图片/视频 URL；对 DOM `<video>` 最多汇集两个页面直接暴露的有效 HTTPS 源，提供用户主动的渠道选择，再复制、分享、在新 HTTPS 标签打开或确认下载；可手动重扫、清空当前标签观察列表。清空不删除下载文件或网页数据；分享不附 Cookie/Referer 或资源字节；不请求 fallback API、不改响应，不保证原图/无水印。 |
| 资源下载 | 已实现（有限） | Android DownloadManager；每条已观察资源查询并显示本空间内用户显式加入的最新下载状态（等待/进行中/暂停/完成/失败/记录缺失），下载中心支持打开、分享、完成后 SAF 另存、删除；最多保留 100 条记录；不传递 WebView Cookie/认证头，应用私有外部目录卸载时删除。 |
| 认证下载 | 未实现 | 不自动附加 Cookie 或认证头；需登录或已过期链接可能失败/过期。 |
| 30 秒/时间码 | 已实现（默认安全适配；原版脚本可选） | 默认 WebView 模式只保存本地状态并提供人工提示词工具；用户可在浏览器菜单显式开启原版 `duration-main.js`，经警告并重载后原样注入 Dola/Doubao 白名单页。Android 默认适配器提供 `setDola30s` / `getDola30sStatus` 及兼容旧 C# 宿主的 `setDoubao30s` / `getDoubao30sStatus` 本地状态 API；其中 `applied: 0` 明确表示未控制页面平台。页面悬浮控件支持拖动，并复用原版面板位置键 `intl_doubao_media_panel_pos_v3`；新增最小化/展开控制并持久化至原版 `intl_doubao_media_panel_minimized_v1` 键。原完整媒体面板和解析 UI 仍未复刻。30S 对话框会说明仅可观察页面已暴露的资源 URL，MV3 Debugger 深度/自动嗅探和 fallback API 解析不可用。原版脚本选项菜单直接读写原脚本既有的 prompt-guard / timeline-rewrite localStorage 键；开启原版模式后，原脚本自行判断是否更改页面状态、提示词或匹配请求中的用户文本；不会由应用自动提交。原 MV3 源文件保持原样。 |
| 指纹诊断/隐私 | WebView 已实现用户开启的 API 降噪子集；扩展模块部分接入 | 浏览器菜单“隐私洁净模式”保留实际 WebView UA（移除应用专属后缀）、阻止第三方 Cookie、禁用网页 Geolocation，并在受信 Dola/Doubao/千问/剪映顶层页及匹配的 provider iframe 限制 Canvas 像素读取、隐藏 WebGL 调试渲染器、禁用离线音频指纹 API、WebRTC 及设备枚举。未伪造机型/系统身份或高熵请求头，不保证无定向广告；绘图导出、音频处理、通话或设备选择功能可能受影响。Worker 与不匹配/opaque frame、完整身份混淆、页面诊断和 DNR 请求头规则仍未接入 WebView。
| 快捷文本 | 已实现 | 本地 CRUD、复制或填入当前网页；不会自动发送。 |
| 分镜工作台 | 已实现（手动） | UTF-8 文本按空行导入、编辑、三态手动状态、JSON 备份；不代表平台生成状态。 |
| Cookie 导入/导出 | 已实现（有限快照） | 用户主动 SAF 操作；只接受完全相同 origin，快照仅名称和值。导入写根路径、Secure/HttpOnly/SameSite=Lax 会话 Cookie；Domain/Path/过期时间/其它元数据及网页本地存储不迁移，不是完整登录备份。明文快照需用户自行保管和删除。 |
| CDP 深度嗅探/Fetch.fulfillRequest | 未实现 | Android WebView 没有桌面 WebView2 等价能力。 |
| HTTP/SOCKS5 认证、链式代理 | 未实现 | 需要重新设计 Android 网络层。 |
| UA/时区/Canvas/Audio/WebRTC 完整指纹 | 未完成（仅部分保护） | 隐私洁净模式保留真实 WebView UA 并移除 app 后缀，阻止第三方 Cookie；Canvas/Audio/WebRTC 等 API 降噪也应用于匹配的 provider iframe。未改时区、系统身份或 Client Hints；Worker、opaque/未匹配 iframe 与完整跨页面指纹保护仍未实现。
| 批量自动生成、发送、后台任务 | 未实现 | 目前仅提供用户确认后的网页填入。 |
| 遮罩、人脸、参考音频、创作中心、百宝箱 | 未实现 | 桌面发布包只有入口线索或组件源码不完整。 |
| Windows 机器码、注册表、WPF API、DLL/Chrome 扩展 | 不适用/未实现 | Android 无对应运行时。 |
| 本地自动化 API 17823 | 未实现 | 原桌面接口仅回环绑定，未暴露到手机网络。 |

## 验证状态
- 已验证代码 head `b262e11ffe2c6829cbaad68238923ccf0c9a0bcb`：46 项 Node 测试与 Android 15/API 35 的 17 项 instrumentation 均通过；Android APK workflow `38057036901`、UI smoke `38057036889` 成功。覆盖视频 source 分组和资源行本空间下载状态展示；原始扩展资源打包哈希校验成功。当前 ADB 无设备，最新下载 UI 真机交互待验证。

- 最新已验证代码 head `5cd4228c77c6c441d77f8a03e4dd6c96b8972fd5`：Node 四套共 43 项通过（媒体 17、隐私 14、适配器 11、源文件清单 1）；[Android APK workflow](https://github.com/qwer654/anzhuo-dola/actions/runs/38052851806) 构建/签名/原始资源校验通过，[Android 15/API 35 smoke](https://github.com/qwer654/anzhuo-dola/actions/runs/38052851802) 的 15/15 instrumentation 通过，实测匹配的跨源 provider iframe 中隐私 API 降噪与跨源媒体帧 nonce relay。真实小米手机仍未连接 ADB。
- 工作流会运行媒体、原始 JS 完整性和 Android 适配层 Node 测试（`tests/media-probe.test.cjs`、`tests/dola30-source.test.cjs`、`tests/dola30-android-adapter.test.cjs`）。
- 在 2026-10-10 的 PR 检查中，4 个 Node 测试文件共 26 项、11 项 Java 单元测试、`lintDebug`、APK 构建/签名及原始资源打包哈希验证均通过；APK 构件已由 GitHub Actions 上传。
- 最新提交 `f361cc0` 的 GitHub Actions APK 工作流已通过：29 项 Node 测试、12 项 Java 单元测试、lint、debug/deviceValidation APK 构建与签名、原始资源哈希校验。新增的运行时 VM 夹具验证媒体观察器只读取 fetch/XHR 可读响应副本，维持原 Response 对象、XHR `send` 返回值和 `responseText` 不变。API 35 模拟器 6/6 instrumentation 已在同一 Android 代码状态的 `05093fc` 通过；Dola30 原版匹配请求的 fetch/XHR 转换测试也通过。
- `android.yml` 现对匹配的 pull request 也执行构建验证；`android-smoke.yml` 对 Android 代码/测试 pull request 运行 Android 15/API 35 模拟器的 `connectedDeviceValidationAndroidTest`。PR #1 最新已完成检查中，两项工作流均通过。
- 2026-10-10，媒体手动重扫测试代码提交 `5c1f993` 的 Android UI smoke 与 APK workflows 均通过；模拟器运行 7 项 instrumentation，构建产出 debug/deviceValidation APK，且两份 APK 均验证了 15 个原始扩展文件。随后仅文档提交更新到 head `4c162a9`，没有改动该已验证代码。
- 后续 instrumentation 提交 `9e41d17` 的两项 Android 工作流也通过；API 35 模拟器共运行 9 项 instrumentation，新增覆盖默认关闭时原版 fetch/XHR hooks 保持原状，以及 Dola/Doubao document-start 脚本不注入非白名单 origin。APK 构建和 15 个原始文件校验通过。
- 最新代码提交 `bcf7919` 的 APK 与 Android UI smoke 工作流均通过；API 35 模拟器 10/10 instrumentation 通过，新增“隐私洁净模式”用户确认/重载/关闭回归。剪贴板测试不再依赖后台读取系统剪贴板，改为精确验证即将写入的 `ClipData` 保留完整签名 URL。
- 后续 Android 适配层增加“原版 Dola30 选项”原生菜单：只在用户已启用原版脚本、且页面为白名单时使用适配器暴露的读写接口操作原有 localStorage 键；Node VM 用例验证默认值、读写与恢复。PR #1 的 `fd7d1ead` 对应 APK 与 Android UI smoke workflows 均通过；API 35 instrumentation 验证选项 API、原版公开 API 不被覆盖，且关闭时间轴选项会阻止匹配请求改写。域名后缀修复也已通过精确 Dola/Doubao 子域名和相似域名排除测试。
- UI smoke 因前一测试遗留的隔离 BrowserActivity task 偶发回到 launcher；测试现改成 press Home 后以 `NEW_TASK|CLEAR_TASK` 启动主页面。PR #1 的 Android 15/API 35 UI smoke workflow 在该变更后通过，两个 smoke 用例均成功。
- 后续适配器补齐原版 `duration-main.js` 注释中要求保留的旧 C# 宿主别名 `setDoubao30s` / `getDoubao30sStatus`。默认适配只反映本地开关并明确返回 `applied: 0`；原版脚本 opt-in 时不覆盖两个别名。6 项适配器 Node 测试、原始 JS 清单校验及 head `9be9f10` 的 Android APK/UI smoke 工作流均通过。
- 30S 工具对话框现明确显示页面资源观察状态，以及 Chrome MV3 Debugger 深度/自动嗅探和 fallback API 解析不可用；纯 Java 单测检查提示文案及探针开/关分支。Android smoke 工作流也加入 `app/src/test/**` 触发路径，确保单测变更会重跑模拟器验收。head `37a82f1` 的 APK 与 Android 15/API 35 UI smoke 均通过。
- 最新 `deviceValidation` APK 来自通过的 APK run `38029307145`，SHA-256 `0fef409e982b777740e79b527fc8981eb8a804c9022cfda3d6e826cf1265d072`；本地 verifier 核验包内 15 个原始资源哈希均匹配。对 Xiaomi `22127RK46C` 安装并装验证包时收到 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；没有重试、绕过限制或替换现存 `com.doulala.android.debug` 包。新版本真机验证仍待设备端明确允许。
- `WebViewAdapterInstrumentationTest` 在 Android WebView 中通过 document-start、本地 30S 状态、提示词转换及 opt-in 原版控制提示段断言、已观察媒体链接复制到系统剪贴板的精确性断言；`BrowserSmokeTest` 通过空间切换、标签/书签及空间隔离烟测。
- **小米真机基础验收已完成**：`22127RK46C` / Android 16 / `com.google.android.webview 153.0.8010.36`。已校验并安装 CI `0.2.1-debug` APK，打开 `https://www.dola.com/chat/`；屏幕显示本地工具状态及资源计数（5）。本次未登录、未提交提示词或触发生成。
- **真机安装待确认**：从最新代码提交 `bcf7919` 的 CI artifact 提取 `deviceValidation` APK，并本地复核 SHA-256 `4e6111300ffb50eb10126d275b5479cd7360268041c4bb8f71808d386373a87a`，包含 15 个原始扩展文件。对前一版 APK 执行 `adb install -r` 曾返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；最新 APK 尚未安装。未卸载旧版或更改其数据；需在手机端允许通过调试安装。
- 真机已验证启动、该 Dola 页面加载、document-start 适配控件及资源观察状态；未验证登录/会话、实际下载、上传、Cookie 快照、不同空间数据隔离或平台是否接受 30 秒请求，仍需逐项真机验收。

## 安装与升级边界

APK 为 debug 构建，runner 的临时签名可能导致无法覆盖旧 APK。必要时卸载再装会清除应用数据；升级前应先导出工作台 JSON、Cookie 快照（若确有需要）及下载中心中已另存的文件。Cookie 快照包含明文会话凭证，不能作为安全备份或提交到仓库。

- WebView 标签导航历史现支持有界 best-effort 保存/恢复：兼容旧 URL 数组，历史编码 96 KiB 总预算、单标签 36 KiB Parcel 上限、512 KiB 标签 JSON 上限；损坏/超限回退 URL。Android instrumentation 在受控双页 fixture 上验证恢复的返回栈。此功能不恢复 DOM、表单或 JS 堆；PR head `241f2ff1` 的 APK CI 与 Android 15/API 35 UI smoke 已通过；16/16 instrumentation 全绿（run `38054679861`）。

