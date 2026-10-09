# Cobalt 构建与验收门槛

## 本次核对结果（2026-10-09）

- 已读取上游源码，固定到 `tools/cobalt/baseline.json` 的提交；不是仅根据产品名猜接口。
- 上游 `tools/build/export-aar.sh` 说明：AAR 之外还需要 APK 内 assets、JNI 注册源码和资源映射。不能只在 Gradle 加一个 AAR 就宣称内核替换完成。
- 上游默认 Gradle 工程缺少 Chromium 导出物时会使用文档引擎。因此本项目的新工作流明确构建 `chrome_public_apk`，不会把那个降级产物交作浏览器。
- 当前 GitHub 仓库自托管 runner 数量为 0，本机 `adb devices` 无设备。尚未启动内核构建，也未完成安卓验收。

## 已可执行的两条流水线

1. `MV3 runtime and extension packages`：普通 GitHub runner 执行单元测试、原件哈希校验、真实 Chromium 本机回显测试，输出两份完整扩展 ZIP 和逐文件 SHA-256。原扩展的 5 个图标从用户原目录补齐，原 JS / manifest 不改字节。
2. `Cobalt engine integration (provisioned Linux only)`：手动触发，仅使用带 `cobalt-android` 标签的专用 Linux x64 runner。把两个经过签名校验的 CRX 接入 Cobalt 原有 external-extension stager，更新版本标记、GN assets 后构建真实内核 APK；不只是往 APK 塞文件。原 uBlock/pdf.js 条目保留，不添加强制启用策略。

第二条输出是 **Cobalt 内核集成验证 APK**，不是现有 Doulala Android 应用的替代版本。空间、工作台、Cookie 管理等 Doulala UI 仍保留在原工程，迁移和回归没完成前不切换默认入口。

## 专用 runner 必须预先准备

需要 Linux、足够磁盘/内存和完整 Chromium 编译环境。按固定 Cobalt 提交内的 `docs/build.md` / `docs/reproducible-build.md` 准备独立 checkout、工具链、上游补丁及其自带扩展资源；不要使用带个人未提交修改的源码树。流水线不会自动下载数百 GB、安装系统依赖或创建签名身份。

runner 服务环境变量：

| 名称 | 内容 |
| --- | --- |
| `COBALT_REPO` | 固定提交的 Cobalt 源码绝对路径 |
| `COBALT_SRC` | 已应用该版本上游补丁的 Chromium `src` 绝对路径；HEAD 标签必须为 `140.0.7339.264` |
| `COBALT_OUT` | 可选，默认 `COBALT_SRC/out/Default`，必须在该源码树内 |
| `COBALT_JOBS` | 可选，默认 4 |
| `DOLA30_CRX_KEY` | runner 上已有的 Dola30 CRX 私钥文件路径 |
| `DOLA_PRIVACY_CRX_KEY` | runner 上已有的隐私扩展 CRX 私钥文件路径 |

PATH 需要 `gn`、`autoninja`、`openssl`、`python3`、`node`。Python 需要上游 CRX 签名校验使用的 `cryptography`。私钥由持有人保存，不填入仓库，也不上传 Actions artifact；更换密钥会改变扩展 ID。APK 发布签名另行管理，本工作流不是正式发布流程。

GN 参数必须包含 Android / arm64 / `is_desktop_android = true`，且已有有效 Cobalt 配置。先应用上游 patch series，再运行本项目 stager；再次应用上游 series 会覆盖生成文件，需要重跑本项目步骤。

构建拒绝旧时间戳 APK，并验证两个 CRX 在 APK 中未压缩且字节哈希匹配。C++ 编辑前在源码 `.dola-backups/<timestamp>/` 备份；更新用原子替换。回退需同时恢复该备份的 `bundled_extensions.cc` 与 `BUILD.gn`，新 CRX 不再被 GN 引用后不会被编入；不要删除整棵 Chromium 工作目录。

## 已测与未测

本地 Windows Chromium `153.0.8010.12` 的真实 MV3 测试通过：两次启用/关闭、网络 UA 与 navigator 一致、Client Hints 删除、Cookie / Authorization 保留、关闭恢复、严格模式 Canvas 读回阻断。报告位于 `.tooling/browser-test/result.json`；这是桌面结果，不是 Cobalt 140 的兼容性证明。

最终仍必须在 Android 真机完成：

1. 安装验证 APK，确认两个扩展被扩展系统安装且 Service Worker 运行，重启后仍可用。
2. 通过自有回显页验证实际网络头和网页属性；清洁模式开关各两轮，检查 Cookie/认证不受影响。
3. 执行原扩展功能逐项对照；未验证的 debugger、下载、宿主桥路径不能算等价。
4. 接入 Doulala UI 后回归空间隔离、标签、书签、下载和工作台。不能用 Cobalt 的基础 UI 替代这些功能后称“全部移植”。
5. iframe / Worker / 原型直接调用等仍未覆盖，不保证匿名或消除广告。
