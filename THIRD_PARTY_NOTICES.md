# Third-party notices

## Orbbec Android Wrapper

- Version: v2.0.6, commit b7252b7ae2d648d30f8d77c6caf7f14f6b1df816.
- AAR: official repository `app/libs/obsensor_v2.0.6_2026031801_release.aar`, copied unchanged.
- Source: https://github.com/orbbec/OrbbecSDK-Android-Wrapper/tree/v2.0.6
- Wrapper license: Apache-2.0, copied in `licenses/Orbbec-Apache-2.0.txt`; vendor native/extension binaries remain distributed as supplied by the official artifact.

## Vosk Android

- Version: 0.3.75
- Project: https://github.com/alphacep/vosk-api
- License: Apache License 2.0

## Vosk small English model

- Model: `vosk-model-small-en-us-0.15`
- Source: https://alphacephei.com/vosk/models
- License listed by the model publisher: Apache License 2.0

The exact artifact URLs and model digest are recorded in `config/dependencies/voice-engine-lock.json`.

## Vosk small Chinese model

- Model: `vosk-model-small-cn-0.22`
- Source: https://alphacephei.com/vosk/models
- License listed by the model publisher: Apache License 2.0
## 视觉推理依赖

- Ultralytics YOLOv8n：导出工具 8.3.0，AGPL-3.0，许可全文见 `licenses/Ultralytics-AGPL-3.0.txt`；模型来源、导出参数与摘要见 `config/dependencies/yolo-model-lock.json`。发行时需遵守其许可证，封闭源产品须另行确认授权方案。
- ONNX Runtime Android 1.20.0：Microsoft，MIT；许可全文见 `licenses/ONNX-Runtime-MIT.txt`。

当前运行视觉模型为 YOLO26n-416；保留的 YOLOv8n 模型用于历史对照。各模型来源与摘要见 `config/dependencies/yolo-model-lock.json`。锁文件中的历史性能与验收字段不是当前版本完整验证声明。
