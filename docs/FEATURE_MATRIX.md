# 功能矩阵与验收边界（Android 0.2.1）

来源：当前 `android-port/app/src/main/java/com/doulala/android` 实现与 Android 工作流。下表的“已实现”表示源码已提供对应路径，不代表真实设备、第三方网站或云端工作流已经验收通过。

桌面能力对照来源：桌面发布包的 `DolaMultiBrowser.deps.json`（版本 2.3.1）、现有旧版 1.4.8 源码、当前 `Extensions/dola30`（MV3 1.6.11）以及仅含部分现有类的新版 `v231` 源码。桌面来源用于识别移植范围，不将未知或缺失组件推定为 Android 已完成。

| 能力 | 0.2.1 状态 | 实现与限制 |
|---|---|---|
| 豆包/Dola/即梦/千问/云雀入口 | 已实现 | 可编辑 HTTPS 首页；第三方网站可能拒绝 WebView 登录，需真机验证。 |
| 8 个隔离账号空间 | 已实现 | 8 个独立 Android 进程/Chromium 数据目录；空间内共享登录，空间之间不共享 Cookie/网页存储。 |
| 浏览、前进/后退、刷新、上传 | 已实现 | HTTPS WebView；上传使用系统 SAF 文档选择器；不允许 `file://`。 |
| 多标签 | 已实现（有限） | 每空间最多 6 个按需标签；只保存/恢复标签 URL 与当前选中标签，不恢复表单、历史记录或页面内状态。 |
| 书签 | 已实现（有限） | 每空间本地最多 200 条；不可导出、同步或跨空间共享。 |
| 白名单页面媒体观察 | 已实现（安全子集） | 观察页面已有 JSON/fetch/XHR/DOM/Performance 的图片/视频 URL；可复制已观察到的链接、在新 HTTPS 标签打开或确认下载；不请求 fallback API、不改响应，不保证原图/无水印。 |
| 资源下载 | 已实现（有限） | Android DownloadManager + 下载中心状态、打开、分享、SAF 另存、删除；最多保留 100 条记录，应用私有外部目录卸载时删除。 |
| 认证下载 | 未实现 | 不自动附加 Cookie 或认证头；需登录或已过期链接可能失败/过期。 |
| 30 秒/时间码 | 已实现（默认安全适配；原版脚本可选） | 默认 WebView 模式只保存本地状态并提供人工提示词工具；用户可在浏览器菜单显式开启原版 `duration-main.js`，经警告并重载后原样注入 Dola/Doubao 白名单页。开启原版模式后，原脚本自行判断是否更改页面状态、提示词或匹配请求中的用户文本；不会由应用自动提交。原 MV3 源文件保持原样。 |
| 指纹诊断/隐私 | WebView 已实现保守洁净子集；扩展模块未接入 | 浏览器菜单“隐私洁净模式”采用实际 WebView 原生 UA（移除应用专属后缀）、阻止第三方 Cookie，并禁用网页 Geolocation API；不伪造机型/系统身份或高熵请求头，可能影响跨站登录/定位功能，不保证无定向广告。MV3 模块的 Canvas/Audio/WebRTC 限制、页面诊断与 DNR 请求头规则仍未接入 WebView。
| 快捷文本 | 已实现 | 本地 CRUD、复制或填入当前网页；不会自动发送。 |
| 分镜工作台 | 已实现（手动） | UTF-8 文本按空行导入、编辑、三态手动状态、JSON 备份；不代表平台生成状态。 |
| Cookie 导入/导出 | 已实现（有限快照） | 用户主动 SAF 操作；只接受完全相同 origin，快照仅名称和值。导入写根路径、Secure/HttpOnly/SameSite=Lax 会话 Cookie；Domain/Path/过期时间/其它元数据及网页本地存储不迁移，不是完整登录备份。明文快照需用户自行保管和删除。 |
| CDP 深度嗅探/Fetch.fulfillRequest | 未实现 | Android WebView 没有桌面 WebView2 等价能力。 |
| HTTP/SOCKS5 认证、链式代理 | 未实现 | 需要重新设计 Android 网络层。 |
| UA/时区/Canvas/Audio/WebRTC 完整指纹 | 未完成（仅部分保护） | 隐私洁净模式保留真实 WebView UA 并移除 app 后缀，阻止第三方 Cookie；未改时区、系统身份或 Client Hints，也未接入 iframe/Worker/Canvas/Audio/WebRTC 完整保护。
| 批量自动生成、发送、后台任务 | 未实现 | 目前仅提供用户确认后的网页填入。 |
| 遮罩、人脸、参考音频、创作中心、百宝箱 | 未实现 | 桌面发布包只有入口线索或组件源码不完整。 |
| Windows 机器码、注册表、WPF API、DLL/Chrome 扩展 | 不适用/未实现 | Android 无对应运行时。 |
| 本地自动化 API 17823 | 未实现 | 原桌面接口仅回环绑定，未暴露到手机网络。 |

## 验证状态

- 工作流会运行媒体、原始 JS 完整性和 Android 适配层 Node 测试（`tests/media-probe.test.cjs`、`tests/dola30-source.test.cjs`、`tests/dola30-android-adapter.test.cjs`）。
- 在 2026-10-10 的 PR 检查中，4 个 Node 测试文件共 26 项、11 项 Java 单元测试、`lintDebug`、APK 构建/签名及原始资源打包哈希验证均通过；APK 构件已由 GitHub Actions 上传。
- 最新代码提交 `0514cdb` 的 GitHub Actions 已通过：28 项 Node 测试、12 项 Java 单元测试、lint、debug/deviceValidation APK 构建与签名、原始资源哈希校验；API 35 模拟器 6/6 instrumentation 通过。`c6d01ca` 的 fetch/XHR 夹具验证匹配 completion 请求的 user 时间码被转换、system 文本及无关路径不变；`0514cdb` 增加隐私洁净模式关闭网页定位的策略单测。
- `android.yml` 现对匹配的 pull request 也执行构建验证；`android-smoke.yml` 对 Android 代码/测试 pull request 运行 Android 15/API 35 模拟器的 `connectedDeviceValidationAndroidTest`。PR #1 最新已完成检查中，两项工作流均通过。
- `WebViewAdapterInstrumentationTest` 在 Android WebView 中通过 document-start、本地 30S 状态、提示词转换及 opt-in 原版控制提示段断言、已观察媒体链接复制到系统剪贴板的精确性断言；`BrowserSmokeTest` 通过空间切换、标签/书签及空间隔离烟测。
- **小米真机基础验收已完成**：`22127RK46C` / Android 16 / `com.google.android.webview 153.0.8010.36`。已校验并安装 CI `0.2.1-debug` APK，打开 `https://www.dola.com/chat/`；屏幕显示本地工具状态及资源计数（5）。本次未登录、未提交提示词或触发生成。
- 真机已验证启动、该 Dola 页面加载、document-start 适配控件及资源观察状态；未验证登录/会话、实际下载、上传、Cookie 快照、不同空间数据隔离或平台是否接受 30 秒请求，仍需逐项真机验收。

## 安装与升级边界

APK 为 debug 构建，runner 的临时签名可能导致无法覆盖旧 APK。必要时卸载再装会清除应用数据；升级前应先导出工作台 JSON、Cookie 快照（若确有需要）及下载中心中已另存的文件。Cookie 快照包含明文会话凭证，不能作为安全备份或提交到仓库。
