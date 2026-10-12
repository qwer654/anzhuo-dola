# 64 空间与移动 UI（0.3.0）

## 本轮实现

- 64 个固定槽位，编号 1–64 对应 `Space0`–`Space63`；每个活动声明私有 `:spaceN` 进程和 taskAffinity。
- Application 在任何 WebView/CookieManager 初始化前调用 `WebView.setDataDirectorySuffix("spaceN")`；网页 Cookie、localStorage、IndexedDB、WebView 数据及缓存按空间隔离。同空间的多标签共享登录态。
- 保留前 8 个空间的类名、suffix、配置键，扩容不迁移/清空已有目录。归档仍保留数据且占名额，可恢复同槽位。
- 64 是档案容量而非 64 个永久运行的进程。空间按需启动，Android 仍可回收后台进程。进程隔离不是 Android UID 沙箱隔离：本 APK 所有空间仍同 UID。
- 空间列表顶部固定搜索、新建、工作台；列表独立滚动。底部常驻“空间 / 当前 IP / 导入 CK / 标签 / 更多”，主要触控目标至少 48dp；适配系统栏和键盘遮挡。
- 唯一页面 JS 开关为 `dola30-新` 的 30s 开关，按空间保存。仅 v1.6.14 两个原始页面主脚本可进入 document-start 链，原字节不变。旧 JS 的开关不再显示，所有旧外部 JS 保持仅备份。
- “导入 CK”直接打开系统文件选择器，仍经过原有账号选择/域名校验/确认，不扫描手机目录，不自动导入 Google Cookie。
- “当前 IP”由用户点击后通过 HTTPS 查询出口地址，不附带 WebView Cookie、页面内容或账号头，不保存查询值。它表示应用访问 IP 查询服务的路由：不同空间不会自动有不同 IP；VPN/代理按目标分流时不保证等于 Dola 看到的出口。

## Google 登录限制（未承诺完成）

Google OAuth 政策不允许开发者控制的嵌入式 WebView 承载授权，可能出现 `disallowed_useragent`。本轮不伪装 UA 绕过，也不复制外部浏览器的 Google Cookie。
空间内网站允许的登录态可以隔离保存，但“64 个空间各自完成 Google OAuth 登录”并未实现/验收。需站点支持的登录方式，或站点配合官方 native OAuth 回调；外部浏览器登录不会自动变成该空间的网页登录态。

## 顺序视频与 ffmpeg 尾帧（未接入、不可验收）

当前 Studio 是分镜文本与手动填入工作台，并无并发视频生成提交器；两个活跃页面脚本也不提供可靠的任务提交/完成状态/成片下载接口。没有伪造已完成队列或重新启用旧 duration/background/panel 脚本。

接入需要提供现有生成入口/源码及平台调用协议：提交后稳定 job ID、完成/失败状态、带授权的成片下载、参考图上传返回格式、模型参数及素材 ID。随后应按下述状态机实现：

`segment[n].submit → waitSucceeded → download → ffmpeg last decoded frame → JPG upload → segment[n+1].submit`

- 上一段未成功、成片未下载、末帧未抽出或上传未完成，均不得提交下一段。
- 下一段参考图必须为 `[上一段末帧JPG, ...原有资产图]`，保留原始模型及其他调用参数；不能把资产图替换/清空。
- 失败停留当前段，持久化任务 ID 并支持恢复，避免重试重复扣费；取消后不得暗中续提交。
- “末帧”须为实际成片最后一帧，不能仅按预计时长截图，也不能仅靠 `-sseof` 抽到接近结尾的一帧。Android ffmpeg 二进制/ABI/许可与大小尚待选定和验证。

## 验证范围

CI 包含原始 JS 哈希、18 项停用 JS 完整性、64 manifest/class/suffix 静态契约、纯 Java 单测、Android 15 原生 UI 与 document-start 注入测试。测试使用合成 Cookie/示例站点，不读取真实账号。
本轮新验证包和旧包并存，原因是托管 Runner 临时调试签名不可作为稳定升级签名；不会通过卸载旧应用解决签名冲突。长期分发需要配置持久签名密钥，而不是提交私钥进仓库。
