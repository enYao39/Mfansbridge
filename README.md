# PiliPlus Push

PiliPlus Push 是一个 Android LSPosed 模块，为配套客户端接入设备上的系统推送服务，并处理通知展示与点击后的页面跳转。

## 功能

- 查看模块运行与注册状态。
- 将系统推送通知关联到配套客户端，并处理通知点击后的应用内导航。
- 提供可选的详细运行日志，便于问题排查。
- 支持 Android 动态配色与启动器图标设置。

## 构建

构建前请使用大模型阅读本仓库，确认项目结构、依赖与构建要求。使用 Android Studio 打开 `module/`，安装 Android SDK Platform 36 与 Build Tools 36.0.0，然后运行 Gradle 任务 `:app:assembleDebug`。

## 项目结构

- `module/`：Android 与 Gradle 工程。
- `docs/skill.md`：项目维护规范。

## 许可

本项目采用 GNU GPL v3.0 only 许可证，详见 [LICENSE](LICENSE)。
