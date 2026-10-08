# Android 页面资源观察与提示词工具

## 范围与边界

这不是 Chrome 扩展运行环境，也不是原有能力配置替换功能的复制。脚本不修改请求参数、请求体、返回响应、模型声明、会员权限；不访问 fallback_api、不下载文件、不发送生成任务。30 秒等时长是否可用只能由平台与账号实际能力决定。

实现无 `JavascriptInterface`、无 `chrome.*` shim。原生壳应在允许站点的顶层页面 document-start 注入 `media-probe.js`，每 3 秒通过 `evaluateJavascript` 拉取结果。旧 WebView 不支持 document-start 时，document-ready 注入只能作为降级，早期请求可能遗漏，不能声称完整嗅探。

## 页面 API

- `window.__dolaAndroidScan()`：扫描当前 DOM，将媒体加入队列，返回队列快照，不清空。
- `window.__dolaAndroidDrain()`：返回并清空当前待处理队列。去重记忆仍保留。
- 每项严格采用 `{url, type: 'image'|'video', source, title}`。
- 原生端建议调用 `JSON.stringify(window.__dolaAndroidDrain ? window.__dolaAndroidDrain() : [])`，注意 evaluateJavascript 本身会将字符串结果再进行 JSON 编码。
- URL 保留原始查询顺序、转义和签名；不删除临时签名参数。
- 仅 `doubao.com`、`dola.com`、`qianwen.com`、`jianying.com` 及真实子域的顶层页面启用。媒体 URL 可以是 CDN，但原生端仍需验证协议、目标地址和用户下载动作。

## 被动发现来源

1. fetch：旁路读取 response.clone() 的 JSON/text，保留页面原 promise 和 response。
2. XHR：loadend 后只读 text/JSON；不改 send 参数或返回对象。
3. DOM：img、video、video source、video poster；不抓取音频或 blob。
4. PerformanceObserver：已发生的媒体资源加载记录。
5. JSON：嵌套对象/数组/JSON 字符串，合法图片/视频 URL、base64 或 URL-safe base64 `main_url`/`play_url`。

限制：响应文本最多 1 MiB；同时最多 3 个 fetch 观察任务；队列 300 条；去重记录最多 3000 条；JSON 遍历最多 5000 节点、16 层；URL 长度最多 8192；每次 DOM/性能扫描最多 300 项。超限跳过，不能保证枚举全部资源。加密 URL、跨源不可读响应、worker 内请求、blob 媒体、尚未加载的历史消息可能发现不到。响应中的 fallback_api 被明确忽略。

## 纯函数测试 API

Node `require(media-probe.js)` 导出：`LIMITS`、`isAllowedHost`、`normalizeMediaUrl`、`mediaType`、`decodeMainUrl`、`createCollector`、`parsePayload`、`parseText`。Node 不安装页面观察器。

## 提示词工具

`prompt-tools.js` 在页面暴露 `DolaPromptTools`，Node 同样可 require：

- `rewriteTimeline(text, totalSeconds)`：显式给定全片时长，把时间码、英文/中文秒数及范围转换成百分比文本；仅返回文本，不自动编辑输入框，不提交请求。
- `parseChineseNumber(text)`：支持半、十/百/千、中文小数。
- `totalSeconds` 必须大于 0 且不超过 86400；输入最多 100000 字符。超出全片的时间点保留大于 100% 的比例，不暗中裁剪。
- 工具无法完全消除自然语言歧义，展示改写结果并由用户检查后再复制使用。

## 来源与修改记录

参考原文件 `D:\AIDM\anzhuodoulala\豆啦啦无印浏览器\Extensions\dola30\duration-main.js` 中 `timelinePercent`、`timestampSeconds`、`parseChineseSeconds`、`rewriteTimelinePrompt` 的转换思路，重新实现为无 DOM/存储/请求依赖的纯函数。增加显式总时长、英文 seconds/sec、输入限制、中文百/千解析；移除控制指令注入、模型/能力处理与所有自动提交逻辑。

媒体脚本参考原 `service-worker.js` 的媒体字段含义，但重新实现为有限只读观察器。未迁入 debugger/CDP、能力 JSON 替换、fallback 请求、加密 token 解密及自动嗅探授权。

## 本地测试

```powershell
& 'D:\Program Files\nodejs\node.exe' --test 'D:\AIDM\anzhuodoulala\android-port\tests\media-probe.test.cjs'
```

单元测试不能代替真机 WebView、站点登录态、网络响应、下载与平台生成验收。
