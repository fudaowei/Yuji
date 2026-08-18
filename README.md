# 语记

一个 Android App：在手机上一边听语音课程/讲座，一边把播放的系统音频实时录下来，用本地模型转成带标点的中文文字，存成 Markdown，支持全文关键词搜索。全程不联网、不上传，识别和标点恢复都在手机本地跑。

## 功能

- **边听边录**：基于 `MediaProjection` + `AudioPlaybackCaptureConfiguration` 直接捕获其他 App 播放的系统音频，不依赖麦克风环境录音，不受外部噪音干扰
- **本地语音识别 + 标点恢复**：基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)，识别模型和标点模型都打包在安装包里，装完即用，不需要联网、不需要额外下载
- **暂停/继续录制**，可随时手动停止
- **转写结果存成 Markdown**（YAML frontmatter + 正文），全文关键词搜索，点击结果跳转原文并高亮
- **待转写录音列表**：转写失败或被中断时，可以对已有录音手动重新发起转写
- **长按删除**转写记录或录音（可选择只删文字保留录音）

## 为什么要做成本地识别

语音课程/讲座类内容往往涉及知识产权，不希望音频或文字被上传到任何第三方服务器。所以从一开始就把"完全本地识别、不联网转写"定为硬性约束，也因此放弃了体积更小、但依赖云端 API 的方案。

## 安装要求

- Android 10（API 29）及以上的真机（`AudioPlaybackCaptureConfiguration` 的最低系统要求）
- arm64-v8a 或 armeabi-v7a 架构（现在绝大多数真机都是，不支持模拟器）
- 需要手动授予录音、通知、"所有文件访问权限"（用于把转写结果存到 `/sdcard/Yuji/`）

目前只有 Android 版。iOS 因为系统沙盒限制，普通 App 无法捕获其他 App 的系统音频，暂无法直接照搬这套方案；桌面版（Ubuntu/Windows/Mac）尚未实现。

## 从源码编译

```bash
export JAVA_HOME=~/dev-jdk/jdk-17.0.20+8   # 或任意 JDK 17
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug
```

仓库里不包含语音识别/标点模型文件、也不包含 release 签名密钥，需要自行准备：

1. **识别 + 标点模型**：从 sherpa-onnx 官方 Release 下载 `sherpa-onnx-paraformer-zh-2023-09-14`（中文语音识别，int8 量化）和 `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8`（标点恢复），解压后按下面结构放进 `app/src/main/assets/model/`：
   ```
   app/src/main/assets/model/
   ├── paraformer-zh/
   │   ├── model.int8.onnx
   │   └── tokens.txt
   └── punct-ct-transformer/
       └── model.int8.onnx
   ```
   这两个模型文件较大（合计约 305MB），且是二进制文件，需要以未压缩方式打进安装包，`app/build.gradle.kts` 里已经配置了对应的 `noCompress`。
2. **release 签名**（仅打包分享给别人安装时需要，`./gradlew assembleDebug` 不需要）：自行用 `keytool` 生成一个密钥库，在项目根目录建一个 `keystore.properties`：
   ```
   storeFile=keystore/release.jks
   storePassword=你的密码
   keyAlias=你的别名
   keyPassword=你的密码
   ```
   `app/build.gradle.kts` 会自动读取这个文件配置 `signingConfigs.release`。

## 技术栈

Kotlin · Android `MediaProjection` · [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（paraformer-zh 中文语音识别 + ct-transformer 标点恢复，均为 int8 量化模型，纯 CPU 推理）
