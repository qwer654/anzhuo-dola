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
5. **真机验收边界**：已在 Xiaomi `22127RK46C` / Android 16 / `com.google.android.webview 153.0.8010.36` 安装 CI 校验的 `0.2.1-debug` APK，并打开 `https://www.dola.com/chat/`。页面显示本地工具与资源观察状态，资源计数为 5；本次未登录、未提交提示词、未触发生成，也未读取 Cookie/令牌。该结果证明目标 Provider 与这一 Dola 页面下的基础加载/注入路径可用，但不证明登录、上传、实际下载、Cookie 迁移、跨空间隔离或平台接受 30 秒请求。

6. **CI 门禁与设备安装路径已验证**：PR #1 的 Android APK 工作流已对 `debug` 与独立包名 `deviceValidation` 变体运行编译、签名及原始资源完整性检查；API 35 模拟器的 `connectedDeviceValidationAndroidTest` 通过，包含媒体 URL 精确复制到剪贴板的测试，并上传了可并装 APK 与 instrumentation APK。真机侧 `adb install` 曾返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（runner debug 签名不一致）；随后提供并在 CI 验证了并装变体。小米手机本次尝试安装并装变体时返回 `INSTALL_FAILED_USER_RESTRICTED`，旧版数据未卸载或清除，等待设备端安装确认后再做真机 instrumentation。

## 下一阶段验收门槛

- 自动化：Node 测试 26 项、Java 单测 11 项、lint、APK 构建/签名、打包原始资源完整性均通过（2026-10-10 PR 检查）。
- 模拟器：API 35 `connectedDeviceValidationAndroidTest` 中空间隔离/标签、document-start 适配与媒体链接剪贴板测试通过；并装验收 APK 不需替换原 app 包。
- Xiaomi 真机：已在原安装包上确认 `DOCUMENT_START_SCRIPT` 路径及 Dola 页面显示；新版并装包受系统安装限制拒绝，需设备端允许安装后验证提示词转换、资源复制/下载、上传、Cookie 快照及空间隔离。不得因本次 smoke 通过而声称页面请求或平台功能已全面等价。
- 等价声明：没有覆盖的 MV3 API 必须继续标“未移植/非等价”，尤其 debugger、下载认证头、跨 Worker/iframe 注入、响应替换和扩展弹窗/Service Worker 生命周期。
