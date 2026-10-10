# WebView 版代码审核与 JS 移植对照

审核范围：Android `0.2.1` 源码、`assets/dola30-original/` 中的原始 Dola30 包、当前自动化测试。原始包由 `SOURCE-MANIFEST.json` 的长度与 SHA-256 校验；适配工作不得覆盖或改写这批文件。

## 结论

Android WebView 可承载页面脚本、同源/白名单页面 UI、网页 DOM 工具和被动可见资源观察，但不是 Chrome MV3 扩展宿主。当前不能宣称原 JS 已等价运行：`chrome.*` 事件/API、Service Worker、`chrome.debugger`/CDP、响应体替换和扩展下载 API均没有 WebView 的直接等价实现。继续移植时应保留原件，并把每个新增实现放在 Android 适配层；“等价”必须逐项通过页面行为与设备测试证明。

## 原包能力到 Android 的状态

| 原文件/能力 | WebView 状态 | 审核判断与下一步 |
|---|---|---|
| `duration-main.js` 页面时长 UI、状态和提示词时间轴 | **部分适配** | `dola30-android-adapter.js` 有 15/30 本地状态；`prompt-tools.js` 有用户确认的时间轴转换和复制。平台能力菜单/请求参数改写、自动提交不等价，且当前适配明确不改生成请求。 |
| `content-panel.js` 扩展浮层 | **未运行原件** | Android 原生菜单提供媒体、下载和 30S 工具入口；原面板调用扩展消息 API，WebView 中无 `chrome.runtime`。 |
| `service-worker.js` 标签、下载、Debugger、资源提取、响应替换 | **安全子集** | 书签/标签/下载由原生 UI 提供；`media-probe.js` 被动观察 DOM、Performance、fetch/XHR 中已暴露的媒体 URL。Debugger/CDP、fallback API 获取、解码/请求链和响应替换未移植。 |
| `skill-pack-main.js` 能力响应钩子 | **保留、未注入** | 原文件哈希保持不变，但不向站点运行；不伪造/扩大平台返回的模型、会员或生成能力。 |
| `host-bridge.js` 与 Chrome 消息通道 | **未移植** | 未创建 `chrome.*` 兼容假层，避免出现“看似成功、实际消息丢失”的伪兼容。若实现原生桥，必须限制白名单源、命令集合、消息大小和调用权限，并单测来源校验。 |
| MV3 `manifest.json` | **仅作为原始资料保留** | Android APK 不会把它当作 Chrome 扩展安装。 |
| MV3 fingerprint-privacy 扩展 | **尚未接入 WebView** | 其扩展 worker/DNR 路径不是 WebView API；不得标记为手机上的隐私防护已生效。 |

## 审核发现与改进

1. **版本标签不一致**：应用首页显示 0.2.0、Gradle/功能矩阵为 0.2.1；本次已统一首页标签为 0.2.1。
2. **设备端适配缺少执行级证明**：既有测试验证 JS 纯函数、源码哈希和节点 VM 行为，但没有证明 Android WebView Provider 能在目标 origin 的 document-start 执行注入脚本。本次增加 `WebViewAdapterInstrumentationTest`，检查 15/30 状态切换、时间轴输出，并确保返回状态明确为不改网络、不自动提交。
3. **原始 JS 完整性**：保留检查继续验证全部列出的原文件路径、字节数、SHA-256；APK 工作流还会检查打包后资产。不允许将原件“改成 WebView 版”；新适配放在独立文件并单独测试。
4. **站点运行风险**：注入只允许受信 HTTPS 域；文件访问关闭、混合内容禁止、外部 scheme 不会直接交给 WebView。资源下载为用户确认的系统下载任务，不自动附加登录 Cookie。Cookie 快照是敏感凭证，只经用户主动 SAF 选择且绑定精确 origin。
5. **运行时仍未验收**：源码检查或模拟器构建不能证明目标平台登录/上传/媒体发现/下载正确；还需 Xiaomi Android 16 真机验证，并记录 WebView Provider 版本、页面 URL、步骤和结果，勿采集用户 Cookie 或会话令牌。

## 下一阶段验收门槛

- 自动化：Node 测试、Java 单测、lint、APK 构建、打包后原始资源 SHA-256 全通过。
- 模拟器：`connectedDebugAndroidTest` 的 UI 隔离/标签检查与新增 document-start 适配测试通过。
- Xiaomi 真机：确认目标 WebView Provider 支持 `DOCUMENT_START_SCRIPT`；检查 Dola 豆包页浮动控件、输入选择器与用户确认时间轴转换；验证页面本身无请求体/响应体改写；确认可见媒体链接仅在用户点下载后进入系统下载器。
- 等价声明：没有覆盖的 MV3 API 必须继续标“未移植/非等价”，尤其 debugger、下载认证头、跨 Worker/iframe 注入、响应替换和扩展弹窗/Service Worker 生命周期。
