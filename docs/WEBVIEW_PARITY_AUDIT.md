# WebView 版代码审核与 JS 移植对照

审核范围：Android `0.2.1` 源码、`assets/dola30-original/` 中的原始 Dola30 包、当前自动化测试。原始包由 `SOURCE-MANIFEST.json` 的长度与 SHA-256 校验；适配工作不得覆盖或改写这批文件。

## 结论

Android WebView 可承载页面脚本、同源/白名单页面 UI、网页 DOM 工具和被动可见资源观察，但不是 Chrome MV3 扩展宿主。当前不能宣称原 JS 已等价运行：`chrome.*` 事件/API、Service Worker、`chrome.debugger`/CDP、响应体替换和扩展下载 API均没有 WebView 的直接等价实现。继续移植时应保留原件，并把每个新增实现放在 Android 适配层；“等价”必须逐项通过页面行为与设备测试证明。

## 原包能力到 Android 的状态

| 原文件/能力 | WebView 状态 | 审核判断与下一步 |
|---|---|---|
| `duration-main.js` 页面时长 UI、状态和提示词时间轴 | **可选原版运行；条件有限** | 默认用 `dola30-android-adapter.js` 及人工工具；用户显式同意后原样 document-start 注入保留的 `duration-main.js` 到 Dola/Doubao HTTPS 页面。默认适配器兼容原版公开的 `setDola30s` / `getDola30sStatus` 和旧宿主别名 `setDoubao30s` / `getDoubao30sStatus`，但只保存本地状态且回报 `applied:0`（不控制平台）；opt-in 下不覆盖原脚本方法。Android 原生菜单可读写原脚本已有的提示段/时间轴开关 localStorage 键；脚本自身判断页面状态、提示词和匹配请求中的用户文本处理。应用不自动提交；实际网页控件识别与真实平台行为仍需真机逐项验证。 |
| `content-panel.js` 扩展浮层 | **未运行原件** | Android 原生菜单提供媒体、下载和 30S 工具入口；WebView 的 30S 悬浮控件新增触屏拖动，使用原面板 `intl_doubao_media_panel_pos_v3` 位置键并保存左/底坐标。原完整媒体面板、最小化/解析 UI 未复刻；原面板调用扩展消息 API，WebView 中无 `chrome.runtime`。 |
| `service-worker.js` 标签、下载、Debugger、资源提取、响应替换 | **安全子集** | 书签/标签/下载由原生 UI 提供；`media-probe.js` 被动观察 DOM、Performance、fetch/XHR 中已暴露的媒体 URL，用户可手动触发重扫；Debugger/CDP、fallback API 获取、解码/请求链和响应替换未移植。 |
| `skill-pack-main.js` 能力响应钩子 | **保留、未注入** | 原文件哈希保持不变，但不向站点运行；不伪造/扩大平台返回的模型、会员或生成能力。 |
| `host-bridge.js` 与 Chrome 消息通道 | **未移植** | 未创建 `chrome.*` 兼容假层，避免出现“看似成功、实际消息丢失”的伪兼容。若实现原生桥，必须限制白名单源、命令集合、消息大小和调用权限，并单测来源校验。 |
| MV3 `manifest.json` | **仅作为原始资料保留** | Android APK 不会把它当作 Chrome 扩展安装。 |
| MV3 fingerprint-privacy 扩展 | **Android API 降噪子集已接入，MV3 运行时未接入** | WebView 菜单洁净模式保留实际 WebView UA（仅移除应用自标识后缀）、拒收第三方 Cookie、禁用网页 Geolocation，并在受信 provider 顶层页及匹配的 provider iframe 阻断 Canvas 像素读取、隐藏 WebGL 调试渲染器、禁用离线音频指纹 API、WebRTC 和设备枚举；不伪造设备身份或高熵 Client Hints。此模式可能让绘图导出、音频处理、通话和设备选择不可用，也不保证无广告。Worker 与不匹配/opaque frame、页面诊断和 DNR 请求头规则仍未接入；iframe API 屏蔽的实际站点兼容性需 Android Provider 测试与真机验证。 |

## 审核发现与改进

1. **版本标签不一致**：应用首页显示 0.2.0、Gradle/功能矩阵为 0.2.1；本次已统一首页标签为 0.2.1。
2. **设备端适配缺少执行级证明**：既有 `WebViewAdapterInstrumentationTest` 覆盖 15/30 状态切换、时间轴输出及可选控制提示段。新增 Android instrumentation 在 API 35 WebView 中验证原版脚本 opt-in 初始化，且 Android adapter 保留原公开控制 API；d6ab775 提交对应 CI 已通过。
3. **原始 JS 完整性**：保留检查继续验证全部列出的原文件路径、字节数、SHA-256；APK 工作流还会检查打包后资产。不允许将原件“改成 WebView 版”；新适配放在独立文件并单独测试。
4. **站点运行风险**：注入只允许受信 HTTPS 域；文件访问关闭、混合内容禁止、外部 scheme 不会直接交给 WebView。资源下载为用户确认的系统下载任务，不自动附加登录 Cookie。Cookie 快照是敏感凭证，只经用户主动 SAF 选择且绑定精确 origin。
5. **真机验收边界**：已在 Xiaomi `22127RK46C` / Android 16 / `com.google.android.webview 153.0.8010.36` 安装 CI 校验的 `0.2.1-debug` APK，并打开 `https://www.dola.com/chat/`。页面显示本地工具与资源观察状态，资源计数为 5；本次未登录、未提交提示词、未触发生成，也未读取 Cookie/令牌。该结果证明目标 Provider 与这一 Dola 页面下的基础加载/注入路径可用，但不证明登录、上传、实际下载、Cookie 迁移、跨空间隔离或平台接受 30 秒请求。

6. **隐私洁净开关已按低风险定义接入**：开启时使用 native WebView UA、阻止第三方 Cookie，并明确关闭网页 Geolocation API；单元测试验证 UA 不被伪造、Cookie 策略与定位开关随模式切换。Android 文档指出 Geolocation 在 WebSettings 中默认开启，因此模式现显式控制此信号；此设置不等于全面抗指纹，也不保证防止个性化广告。[Android WebSettings API](https://developer.android.com/reference/android/webkit/WebSettings#setGeolocationEnabled(boolean))
7. **CI 门禁与设备安装路径已验证**：PR #1 的 Android APK 工作流已对 `debug` 与独立包名 `deviceValidation` 变体运行编译、签名及原始资源完整性检查；API 35 模拟器的 `connectedDeviceValidationAndroidTest` 通过，并上传了可并装 APK 与 instrumentation APK。提交 `bcf7919` 的 Android APK 与 UI smoke 工作流均通过，API 35 模拟器运行 10 项 instrumentation。真机侧，旧版签名不兼容问题已通过并装变体绕开；Xiaomi 安装仍返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，未卸载旧版或清除数据，需要设备端允许安装后再做新版本真机验收。

8. **媒体重扫与脚本注入边界**：媒体列表新增“重扫当前页”，调用现有 `__dolaAndroidScan()` 并排空观察器队列；原生侧仍仅接收有效 HTTP(S) 图片/视频 URL，且要求当前页面处于白名单。Node VM 集成测试覆盖 DOM 发现与队列排空；API 35 instrumentation 直接加载 Dola fixture，断言重扫保留签名 URL 查询串；默认模式断言原版 fetch/XHR hooks 不变，非白名单 origin 不注入 Dola 脚本。`privacyCleanModeRequiresConsentAndCanBeReverted` 验证用户确认、重载与关闭流程；`bcf7919` 上 10 项 instrumentation 全通过。小米真机尚未安装此版本，不能据模拟器结果声称已完成真机验收。

9. **原版脚本可选项映射**：新增 Android 原生“原版 Dola30 选项”入口，仅在原版脚本明确启用且当前页为信任站点时出现可保存功能。适配器只暴露原脚本已消费的 `intl_doubao_prompt_guard_inject_v1` 与 `intl_doubao_timeline_rewrite_v1` 两个 localStorage 选项，默认语义分别为关闭与开启；不会重写 `duration-main.js`。Node VM 测试覆盖默认值、读写、恢复；PR #1 head `fd7d1ead` 的 API 35 instrumentation 检查适配 API、原公开 setter 保持不变，并验证关闭时间轴选项后匹配请求不再改写。相应 Android APK 与 Android UI smoke workflows 已通过。

10. **子域名注入回归**：修正适配器的主机名匹配，改用点边界安全的 `.endsWith()`，覆盖 `www.doubao.com`、`www.dola.com` 等子域，并拒绝 `notdoubao.com` / `evil-dola.com`。该修复已由 Node VM 测试及 PR #1 head `fd7d1ead` 的 Android API 35 instrumentation/CI 验证。

11. **烟测任务前台隔离**：Android UI smoke 测试改为 press Home 后通过 `NEW_TASK|CLEAR_TASK` 启动 `MainActivity`，避免前一次测试的隔离 BrowserActivity task 令后续断言看到 launcher。head `fd7d1ead` 的 Android 15/API 35 UI smoke workflow 已通过。

12. **旧 C# 宿主别名兼容**：原版 `duration-main.js` 声明 `setDoubao30s` / `getDoubao30sStatus` 是旧宿主的兼容 API。默认 WebView adapter 现在提供别名，将 setter 映射到同一套本地状态，getter 保持原 JSON 字段并给出 `applied: 0`，不暗示平台应用成功；opt-in 原版模式保留原别名引用。Node VM 验证开/关状态、JSON 响应和 localStorage，检查原始资产清单通过。head `9be9f105` 的 APK build 与 API 35 Android UI smoke workflows 均通过。

13. **运行中功能边界提示**：30S 原生工具对话框补充当前页面媒体探针状态，并明确指出 Android WebView 没有 Chrome MV3 Debugger 深度/自动嗅探，fallback API 解析未实现；能力报告按 probe 是否注入区分。Java 单元测试锁定正/负状态下的提示文案，避免 UI 把页面可见 URL 观察包装成原版扩展的深度嗅探。APK 与模拟器验收在 workflow-trigger commit `37a82f1b` 均通过。

14. **最新 APK 的真机并装尝试**：通过的 run `38029307145` 上传了 `deviceValidation` variant；下载后的本地 APK SHA-256 为 `0fef409e982b777740e79b527fc8981eb8a804c9022cfda3d6e826cf1265d072`，`verify_apk_assets.py` 确认 15 个原始扩展资源匹配。对连接设备 `22127RK46C` 执行未覆盖安装时，ADB 返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。未重试、未关闭安全限制、未卸载或更改已安装的 `com.doulala.android.debug`；最新 variant 尚未运行于真机。

15. **移动面板触控位置**：Android 30S 悬浮控件新增 Pointer Events 拖动，边界限制在当前 viewport 内；位置写入原版 `content-panel.js` 使用的 `intl_doubao_media_panel_pos_v3` 键，释放拖动不会误触 30S 开关。Node VM 回归测试覆盖坐标更新/持久化和误触抑制；Android WebView instrumentation 验证 Chromium Provider 中 pointer 事件、坐标变更及 localStorage 写入。完整面板、最小化状态和解析 UI 仍未移植。

16. **视频媒体分类与 poster 回归（PR head `9a0062fc`）**：API 35 instrumentation 新增 `<video>`、`<source>` 和 poster fixture。首轮发现 Performance API 可能将 poster 请求标成 `video`，修复为已知 URL 扩展名优先；随后的 WebView 测试发现 DOM poster 属性读取在真实 Provider 下缺失，改为同时回退读取 `getAttribute("poster")`。最终 API 35 smoke run `38040564234` 通过；APK run `38040564223` 完成构建、签名及原始扩展资源完整性检查。Node media probe 15/15、原始来源清单 1/1、Android adapter 10/10 通过。原始 Dola30 包仍由 manifest 校验保持不变。

16. **洁净模式 WebGL 子集**：隐私洁净开关现于 document-start 为受支持 Provider 顶层页面注入独立 fingerprint module，仅启用 `WEBGL_debug_renderer_info` 屏蔽；Android 保留真实 WebView UA，不使用该模块固定的 Chrome 140 identity，不启用 Canvas/Audio/WebRTC 限制。Node VM 覆盖初始 setting seed；Android instrumentation 比对 navigator UA 与 WebView 原生 UA，并验证 WebGL debug API 返回被屏蔽。PR head `0a51423` 所含代码由后续代码提交 `f87f8e1` 触发的 APK workflow `38041152167` 与 API 35 smoke workflow `38041152221` 验证通过，模拟器 instrumentation 12/12 全过。只证明该 WebView Provider/夹具行为，不等于真实站点或完整抗指纹验收。

## 下一阶段验收门槛

- 自动化：`f361cc0` Android APK 工作流通过 Node 29 项、Java 单测 12 项、lint、APK 构建/签名和打包原始资源完整性；`05093fc` API 35 模拟器 6/6 instrumentation 通过。新增 Node VM 测试实际执行被注入媒体观察器，确认 fetch/XHR 响应维持原行为而只收集可读媒体 URL。
- 最新提交 `0189e434`：Node 27 项、11 项 Java 单测、lint、debug/deviceValidation 构建与签名、原始 JS 打包哈希检查以及 API 35 模拟器 instrumentation 均通过。
- `c6d01ca` 提交对应工作流均通过：Node 28 项、Android 单测/build/lint/APK 签名与原始 JS 完整性检查成功；API 35 模拟器完成 6/6 instrumentation 测试（0 失败）。覆盖原版 duration 脚本 opt-in 初始化、Android adapter 不覆盖其公开 API，以及本地 fetch 与 XHR 夹具中匹配 completion 的 user 时间线变换（system 文本与非目标路径保持不变）。`0514cdb` 后续构建及 API 35 模拟器 6/6 检查均通过，洁净模式 UA/Cookie/网页定位规则有单元测试。
- 最新代码 head `9be9f105` 的 GitHub Actions Android APK 与 UI smoke 均通过（runs `38028683538`、`38028683531`）；包括旧宿主 API 别名测试、adapter host 边界、原版选项与 APK/原始资源完整性校验，以及 Android 15/API 35 UI smoke。
- 后续功能边界说明代码 head `399c1b6` 曾触发的工作流被紧随其后的文件更新取消；已在包含该代码、单测和路径触发修复的 head `37a82f1b` 重跑完整验证。Android APK run `38029307145` 与 UI smoke run `38029307232` 均成功；APK 流程完成 Node/Java 单测、lint、构建/签名和原始资源哈希校验，UI 流程完成 Android 15/API 35 仿真验收。
- 模拟器：API 35 `connectedDeviceValidationAndroidTest` 中空间隔离/标签、document-start 适配与媒体链接剪贴板测试通过；并装验收 APK 不需替换原 app 包。
- Xiaomi 真机：已在原安装包上确认 `DOCUMENT_START_SCRIPT` 路径及 Dola 页面显示；新版并装包受系统安装限制拒绝，需设备端允许安装后验证提示词转换、资源复制/下载、上传、Cookie 快照及空间隔离。不得因本次 smoke 通过而声称页面请求或平台功能已全面等价。
- 洁净模式 WebGL 子集提交 `f87f8e1`：Android APK 工作流 `38041152167` 与 UI smoke `38041152221` 均成功。CI 运行 12/12 instrumentation（0 skipped/0 failed），APK 路径验证 Node/Java 测试、lint、打包签名和原始资源完整性。工作流 commit 是 PR merge ref，代码改动以 PR head `0a51423` 为准；纯 docs 后续更新不影响运行代码。
- 等价声明：没有覆盖的 MV3 API 必须继续标“未移植/非等价”，尤其 debugger、下载认证头、Worker/不匹配或 opaque iframe 注入、响应替换和扩展弹窗/Service Worker 生命周期。匹配 provider iframe 的 API 降噪不是完整 MV3 frame/worker 覆盖。


17. **标签导航历史恢复（PR head `7704490b`）**：多标签偏好设置继续读取旧版 URL 字符串数组，并支持写入 URL + 有界 WebView `saveState` 序列化数据。全部历史编码预算 96 KiB，单标签状态原始 Parcel 上限 36 KiB，JSON 上限 512 KiB；无法保存、损坏或超限时退回 URL 加载。API 35 instrumentation 使用本地两页 fixture 验证恢复后仍有返回历史；表单、DOM、JavaScript 堆和网页内状态仍不恢复。PR head `241f2ff1` 的 Android APK 构建/资源哈希门禁通过，Android 15/API 35 UI smoke 为 16/16 instrumentation 全通过（run `38054679861`）；历史恢复项由本地双页 HTTPS 拦截 fixture 覆盖。
