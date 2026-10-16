# LocalConnect

实验性项目 —— 手机上挑图，走局域网传到电脑，直接进 Windows 剪贴板,这样就可随时随地从手机发送大狗粪给你的朋友了

代码是人和 AI 混着写的一坨屎

## 润

```powershell
# 电脑端。首次真机连接之前，先「以管理员身份运行」tools/allow-firewall.bat
# 放行 8000 端口 —— Windows 防火墙默认拦掉所有入站连接
cd server
.\.venv\Scripts\python.exe app.py
```

启动后，**服务端窗口的标题栏会显示手机该填的地址**。

手机端用 Android Studio 打开 `android/` 目录，直接运行到手机上。App 里填上面那个 IP，
点「保存并重连」，再点「发送相册缩略图」。

然后在电脑上点中一张图，按 `Ctrl+C` —— 图片已经在剪贴板里了。

## 目录

```
server/    电脑端   Python + PyQt6 + websockets
android/   手机端   Kotlin + Ktor
tools/     辅助脚本
```