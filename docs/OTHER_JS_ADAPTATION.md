# Other submitted JavaScript adaptation (v1.6.14)

`app/src/main/assets/dola30-webview-media-v1.6.14.js` is a new, read-only page observer. It is intentionally separate from the submitted extension scripts and does **not** execute `service-worker.js` or `content-panel.js`.

## What is available

* On `https://dola.com`, `https://*.dola.com`, `https://doubao.com`, and `https://*.doubao.com` (default HTTPS port only), it records already-present DOM `video`, `audio`, `img`, and `source` URLs.
* It passively inspects cloned responses only for the page's same-origin `/im/chain/single` endpoint. Requests and response objects are never replaced, cancelled, rewritten, or fetched by this observer.
* It listens for the active skill-pack's `dola-media-fallback-apis` event. Its attribute is normally a list of fallback API endpoints; it records an entry only if the page already emitted a direct, HTTPS media URL. It does not call a fallback API.
* The page bridge is `window.__dolaNewMediaState`: `{ installed, items, unsupported, limits, scan(), drain() }`. `scan()` is a native-safe refresh entry point; a bounded debounced `MutationObserver` also sees later-generated media nodes. `drain()` returns and clears the bounded current item list. Every entry is `{url, type, title}` with optional `posterUrl` for video.

URLs must be HTTPS, have no credentials, and use the default HTTPS port. Media candidates are capped at 200; inspected text is bounded to 2 MiB and JSON traversal is depth/node bounded. No raw response text, cookies, credentials, account data, or console diagnostics are retained.

## Deliberately unavailable Chrome-extension behavior

| Submitted component/function | WebView status | Reason / safe replacement |
| --- | --- | --- |
| `service-worker.js`: `chrome.debugger`, Fetch/CDP paused-response bodies | unavailable | WebView does not expose Chrome debugger/CDP. Same-origin page response cloning is the implemented read-only fallback. |
| `service-worker.js`: `chrome.tabs`, scripting injection, badge/action | unavailable | No extension context or panel is created. Native code owns any document-start injection. |
| `service-worker.js`: response fulfill/rewrite of skill pack/action bar | unavailable | This adapter never modifies network responses. |
| `service-worker.js`: downloads, reveal/download lookup, filename handling | unavailable | No download is started or managed by this adapter. |
| `service-worker.js`: fallback API resolution | deliberately unimplemented | Calling extra endpoints would create network traffic and can expose session context. |
| `content-panel.js`: floating panel, download controls, runtime messages | unavailable | No DOM panel, prompts, or extension messaging are injected. |
| `duration-main.js` | handled separately by native document-start bootstrap | It remains byte-exact and is not copied or altered by this adapter. |

There is no global extension API shim and no claim that a watermark-free URL, download, upload, or generation succeeded.

## 原生集成与授权

原始 duration-main.js 字节不变，文档空闲阶段执行。它会在用户发送动作时转换时间线提示词，并过滤部分页面回复文本；不会自动发送。保持原始 v1.6.14 行为：定义的 patchRequests 不被调用，不新增签名后改写 body。

两个原始文档开始主脚本保持不变。媒体观察器在文档开始安装以便被动读取页面已有响应；原生“下载媒体”读取该空间观察结果，提供预览、复制/分享、下载/指定位置等原有原生操作。所有下载需要用户操作，不自动执行。

新增行为由 browser_SpaceN.dola30AdditionalPageConsent1614 显式确认控制；旧版已开的 30s 开关不会静默引入这些行为。仍只有一个原生 30s 开关，可在“30S 工具”确认新增功能。取消确认不改设置或删除账号。

原 Chrome content-panel/service-worker 源码继续不注入（无全局 chrome shim）。Chrome Debugger/CDP、后台 fallback 查询/解密、扩展 tab 管理与 badge 仍未移植；同源页面可读资源不等价于后台无水印提取。新验证包 com.doulala.android.morejs 与已有包并存。
