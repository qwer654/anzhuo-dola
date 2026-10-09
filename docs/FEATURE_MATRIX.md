# 功能矩阵与验收边界（Android 0.2.1）

来源：当前 `android-port/app/src/main/java/com/doulala/android` 实现与 Android 工作流。下表的“已实现”表示源码已提供对应路径，不代表真实设备、第三方网站或云端工作流已经验收通过。

桌面能力对照来源：桌面发布包的 `DolaMultiBrowser.deps.json`（版本 2.3.1）、现有旧版 1.4.8 源码、当前 `Extensions/dola30`（MV3 1.6.11）以及仅含部分现有类的新版 `v231` 源码。桌面来源用于识别移植范围，不将未知或缺失组件推定为 Android 已完成。

| 能力 | 0.2.0 状态 | 实现与限制 |
|---|---|---|
| 豆包/Dola/即梦/千问/云雀入口 | 已实现 | 可编辑 HTTPS 首页；第三方网站可能拒绝 WebView 登录，需真机验证。 |
| 8 个隔离账号空间 | 已实现 | 8 个独立 Android 进程/Chromium 数据目录；空间内共享登录，空间之间不共享 Cookie/网页存储。 |
| 浏览、前进/后退、刷新、上传 | 已实现 | HTTPS WebView；上传使用系统 SAF 文档选择器；不允许 `file://`。 |
| 多标签 | 已实现（有限） | 每空间最多 6 个按需标签；只保存/恢复标签 URL 与当前选中标签，不恢复表单、历史记录或页面内状态。 |
| 书签 | 已实现（有限） | 每空间本地最多 200 条；不可导出、同步或跨空间共享。 |
| 白名单页面媒体观察 | 已实现（安全子集） | 观察页面已有 JSON/fetch/XHR/DOM/Performance 的图片/视频 URL；不请求 fallback API、不改响应，不保证原图/无水印。 |
| 资源下载 | 已实现（有限） | Android DownloadManager + 下载中心状态、打开、分享、SAF 另存、删除；最多保留 100 条记录，应用私有外部目录卸载时删除。 |
| 认证下载 | 未实现 | 不自动附加 Cookie 或认证头；需登录或已过期链接可能失败/过期。 |
| 30 秒/时间码 | 已实现（安全适配） | 白名单 WebView 页面提供本地 15/30 秒状态、原公开 API 兼容名、提示词时间轴转换和复制；不改写 fetch/XHR、不自动提交、不伪造模型能力，不保证目标平台接受 30 秒。原 MV3 文件原样保留在 `assets/dola30-original/`。 |
| 指纹诊断/隐私 | 已加入扩展模块（待 Cobalt 验收） | `assets/fingerprint-privacy/` 提供本地信号报告和可选 Canvas、WebGL、离线音频、WebRTC、媒体设备枚举限制；默认仅隐藏 WebGL 调试信息，不上传、不伪造 User-Agent/请求头、不用于绕过风控。 |
| 快捷文本 | 已实现 | 本地 CRUD、复制或填入当前网页；不会自动发送。 |
| 分镜工作台 | 已实现（手动） | UTF-8 文本按空行导入、编辑、三态手动状态、JSON 备份；不代表平台生成状态。 |
| Cookie 导入/导出 | 已实现（有限快照） | 用户主动 SAF 操作；只接受完全相同 origin，快照仅名称和值。导入写根路径、Secure/HttpOnly/SameSite=Lax 会话 Cookie；Domain/Path/过期时间/其它元数据及网页本地存储不迁移，不是完整登录备份。明文快照需用户自行保管和删除。 |
| CDP 深度嗅探/Fetch.fulfillRequest | 未实现 | Android WebView 没有桌面 WebView2 等价能力。 |
| HTTP/SOCKS5 认证、链式代理 | 未实现 | 需要重新设计 Android 网络层。 |
| UA/时区/Canvas/Audio/WebRTC 完整指纹 | 未实现 | 不提供伪造或绕过平台安全的实现。 |
| 批量自动生成、发送、后台任务 | 未实现 | 目前仅提供用户确认后的网页填入。 |
| 遮罩、人脸、参考音频、创作中心、百宝箱 | 未实现 | 桌面发布包只有入口线索或组件源码不完整。 |
| Windows 机器码、注册表、WPF API、DLL/Chrome 扩展 | 不适用/未实现 | Android 无对应运行时。 |
| 本地自动化 API 17823 | 未实现 | 原桌面接口仅回环绑定，未暴露到手机网络。 |

## 验证状态

- 工作流会运行媒体、原始 JS 完整性和 Android 适配层 Node 测试（`tests/media-probe.test.cjs`、`tests/dola30-source.test.cjs`、`tests/dola30-android-adapter.test.cjs`）。
- 11 项 Java 单元测试已编写，但当前没有云端验证结果，不能记为已通过。
- `android.yml` 的预期检查为上述 Node 测试、`testDebugUnitTest lintDebug assembleDebug`、APK 签名验证与构件上传；当前没有 0.2.1 云端运行结果。
- `android-smoke.yml` 可在 Android 15/API 35 模拟器运行 `connectedDebugAndroidTest`；当前没有该 0.2.1 运行结果。
- 未完成 Android 真机验证；网页登录、空间隔离、标签恢复、Cookie 快照、上传、资源观察及下载中心仍需在目标设备/网站上验收。

## 安装与升级边界

APK 为 debug 构建，runner 的临时签名可能导致无法覆盖旧 APK。必要时卸载再装会清除应用数据；升级前应先导出工作台 JSON、Cookie 快照（若确有需要）及下载中心中已另存的文件。Cookie 快照包含明文会话凭证，不能作为安全备份或提交到仓库。
