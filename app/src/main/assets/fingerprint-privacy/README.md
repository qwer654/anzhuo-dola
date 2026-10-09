# Doulala Fingerprint Privacy

这是供 Chromium MV3 扩展运行时加载的独立指纹隐私模块，不修改 `dola30-original/`。

- 报告只在扩展弹窗本地显示，不上传网络。
- 默认不改变网页行为。
- 用户可选限制 Canvas 读回、隐藏 WebGL 调试渲染器信息；这些选项可能影响网页兼容性。
- 不伪造 User-Agent、设备身份或请求头，不用于绕过平台风控。

在 Cobalt 的扩展管理页加载本目录（或打包为 ZIP/CRX）即可测试。
