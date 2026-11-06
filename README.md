# Helios

Helios 是基于 Kotlin / Jetpack Compose 的 Android 语音辅助应用，配合胸前佩戴的 Orbbec Gemini 2 使用。

当前版本：**0.9.32 / versionCode 103**。Android 12（API 31）及以上，ARM64。

## 功能

- 本地 Hi Helios 唤醒、中文指令识别和中文语音反馈。
- RGB、对齐深度与 IMU 采集，佩戴校准和运动状态识别。
- 本地 YOLO26n 目标检测、深度测距、上/前/下方障碍提醒及左右可见空间描述。
- 云端单帧环境简述、细节与视觉问答；支持手机直连或环境代理。
- 10 类常用物品查找；当前找物要求手机直连模式。
- 疑似跌倒确认与手机本地求助提醒，不联系外部人员。
- 调试预览、参数调整和诊断日志导出。

这些功能是辅助信息，现场效果取决于设备、佩戴与环境；源码中的检测门槛不代表医学诊断或安全认证。

## 构建与运行

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 和运行模型、相机 SDK 已保留在仓库中。

1. Android Studio 打开工程，将 Gradle JDK 设置为 17。
2. 设置本机 `ANDROID_HOME`，或通过 Android Studio 生成 `local.properties`；此文件不提交。
3. 执行：

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。首次构建需要下载 Maven 和 Gradle 依赖。

Debug APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.helios.app.baseline`。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.helios.app.baseline/com.helios.app.MainActivity
```

使用时连接 Gemini 2，授予麦克风、相机及 USB 权限；佩戴好相机后长按首页启动按钮半秒，按语音完成本次佩戴校准。需要结束时长按结束按钮半秒。云端未配置不影响可用的本地功能。

## 云端配置

在 App 的“更多 → 环境理解配置”中选择直连或环境代理，填写自己的密钥/访问令牌，并明确启用单帧上传。不要把真实凭据写入源码。

代理使用 Node.js 22，无额外 npm 依赖：

```sh
cp server/.env.example server/.env
# 在本机编辑 server/.env，填写自己的服务凭据
node --env-file=server/.env server/scene-proxy.mjs
```

默认监听 `127.0.0.1:8787`，接口为 `POST /v1/scene`，健康检查为 `GET /health`。开发设备可通过 ADB reverse 连接回环代理；正式代理地址需提供 HTTPS。找物目前不支持代理模式。

```sh
node --test server/scene-proxy.test.mjs
python3 -m unittest discover -s scripts -p 'test_*.py'
```

`server/.env.example` 仅含占位内容；真实 `.env`、`.secrets`、设备配置及签名文件均被忽略。

## 项目结构

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/helios/app/` | 界面、服务、语音、传感器与业务算法 |
| `app/src/main/assets/` | Vosk 语音模型与 ONNX 视觉模型，属于运行资源 |
| `app/libs/` | 固定版本 Orbbec Android SDK |
| `app/src/test/` | Android 纯逻辑单元测试 |
| `server/` | 单帧环境代理及测试 |
| `scripts/` | 开发设备配置、代理维护和模型导出工具 |
| `config/dependencies/` | SDK、模型来源及校验摘要；含历史记录 |
| `licenses/` | 第三方许可文本 |

详细本地产品文档、阶段验证材料、Agent 计划记录、截图/交付产物、IDE 配置及缓存均不纳入 Git。忽略规则只影响 Git 跟踪，不会删除本地文件。

## 第三方组件

模型、推理运行时及 SDK 的来源和许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。第三方许可文本保留在 [licenses/](licenses/)；本项目源码未另行声明授权许可证。
