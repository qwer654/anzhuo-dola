# 豆啦啦 · Android 移植版

这是 Windows WPF/WebView2 版本的 Android 原生重写预览工程，不是把 EXE 重打包成 APK，也尚未完成桌面版所有功能的移植。

## 用 GitHub Actions 编译

当前仓库为 `qwer654/anzhuo-dola`。在 **Actions → Android APK → Run workflow** 手动运行，或推送 `app/`、Gradle 文件、测试或工作流后自动运行。构建完成后从 workflow run 的 **Artifacts** 下载 `doulala-android-debug`，解压后安装 `app-debug.apk`。另含 SHA-256 文件，可用于校验完整性。

Actions 使用 GitHub 托管的 Android SDK、JDK 17 和 Gradle 8.9，不需要把 Android SDK 放进仓库。当前 workflow 只生成 debug APK；要发布给别人安装，需要用户自己的签名密钥与单独的 release secrets，不能把 keystore 提交到仓库。

预览 APK 的最低系统为 Android 9（API 28）。安装前需在手机允许当前文件管理器安装未知来源应用。不同构建使用临时 debug 签名，可能不能直接覆盖安装；卸载会清除账号空间和本地草稿，请先导出工作台备份。后续正式版需要固定签名密钥。

## 验证范围

CI 执行 11 项 JavaScript 测试、3 项 URL 安全单元测试、Android 编译与 APK 签名验证，并保存单元测试报告。构建成功不等于真机功能验证；目前没有连接手机，网页登录、空间隔离、文件上传和下载仍需实际验收。

## 本机编译

```powershell
.\tools\bootstrap.ps1 -ReviewSdkLicenses -InstallSdk
.\build.ps1
```

脚本不会替用户接受 Android SDK 许可。没有本机 SDK 时直接使用 GitHub Actions。

## 当前边界

已编写账号空间、WebView 单页浏览/上传、白名单页面媒体观察、系统下载和本地创作工作台。多标签、书签、Cookie 导入导出，以及桌面版依赖 CDP 的深度嗅探、Windows 代理认证/链式代理、完整指纹、批量自动生成、遮罩/人脸处理和自动化 API 尚未等价移植；见 `docs/FEATURE_MATRIX.md`。
