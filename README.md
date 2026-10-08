# 豆啦啦 · Android 移植版

这是 Windows WPF/WebView2 版本的 Android 原生重写工程，不是把 EXE 重打包成 APK。

## 用 GitHub Actions 编译

把 `android-port` 目录作为仓库根目录推送到 GitHub（建议使用私有仓库），在 **Actions → Android APK → Run workflow** 手动运行，或推送 `app/`、Gradle 文件后自动运行。构建完成后从 workflow run 的 **Artifacts** 下载 `doulala-android-debug`。

Actions 使用 GitHub 托管的 Android SDK、JDK 17 和 Gradle 8.9，不需要把 Android SDK 放进仓库。当前 workflow 只生成 debug APK；要发布给别人安装，需要用户自己的签名密钥与单独的 release secrets，不能把 keystore 提交到仓库。

## 本机编译

```powershell
.\tools\bootstrap.ps1 -ReviewSdkLicenses -InstallSdk
.\build.ps1
```

脚本不会替用户接受 Android SDK 许可。没有本机 SDK 时直接使用 GitHub Actions。

## 当前边界

已实现账号空间、WebView 浏览/上传、白名单页面媒体观察、系统下载和本地创作工作台。桌面版依赖 CDP 的深度嗅探、Windows 代理认证/链式代理、完整指纹、批量自动生成、遮罩/人脸处理和自动化 API 尚未等价移植；见 `docs/FEATURE_MATRIX.md`。
