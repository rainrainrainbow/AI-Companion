# 星璃 (Xingli) - AI伴侣

一个完全在手机本地运行的人工智能伴侣。采用 **Qwen2.5-0.5B** 小模型通过 llama.cpp 推理，支持 **LoRA 微调**、**3D 虚拟形象**、**语音交互**。

## 特性

### 🧠 本地 LLM 推理
- 基于 **llama.cpp** 的本地推理引擎
- 使用 **Qwen2.5-0.5B** 模型（GGUF Q4量化，~300MB）
- Vulkan GPU 加速推理
- 流式输出，支持随时中断

### 🎯 手机本地 LoRA 微调
- **20条反馈触发阈值**：累积到20条用户反馈自动提示微调
- **自动夜间微调**：可在设置中开启，每天凌晨2点自动执行
- **手动微调**：随时点击设置页面的"立即微调"按钮
- 微调参数：LoRA rank=8, alpha=16, 3 epochs

### 👤 3D 虚拟形象
- 基于 **Google Filament** 的 3D 渲染引擎
- 支持 GLTF 格式模型加载
- 7种表情动画（开心/难过/生气/疲惫/焦虑/爱意/平静）
- 自动眨眼、呼吸动画
- 说话时口型同步

### 🎤 语音交互
- **语音识别**：whisper.cpp 本地 STT（16kHz PCM）
- **语音合成**：Android TTS + VITS 模型双重支持
- 情绪化 TTS：根据 AI 情绪调整语速和音调

### 💖 情感引擎
- 7种情绪状态识别与响应
- 关系亲密度系统（0-100）
- 时间感知问候（早安/晚安/午休等）
- **主动发起对话**：超过2小时/6小时/24小时未互动自动关心

### 🧠 记忆系统
- 短期记忆（最近50轮对话）
- 长期记忆（重要信息自动提取）
- 用户画像构建（姓名/生日/爱好/偏好）
- 关键词记忆检索

## 技术栈

| 模块 | 技术 |
|------|------|
| 推理引擎 | llama.cpp (C++ JNI) |
| 模型 | Qwen2.5-0.5B-GGUF (Q4_K_M) |
| 微调 | llama.cpp LoRA training |
| 3D渲染 | Google Filament + gltfio |
| 语音识别 | whisper.cpp |
| 语音合成 | Android TTS + VITS |
| UI | Jetpack Compose + Material3 |
| 架构 | MVVM + StateFlow |
| 构建 | Gradle + CMake + NDK |
| CI/CD | GitHub Actions |

## 构建指南

### 前置条件
- Android Studio Hedgehog (2024.3.1+) 或更高版本
- JDK 17+
- Android SDK 35
- Android NDK r27b+

### 快速构建

```bash
# 克隆仓库
git clone https://github.com/yourname/AI-Companion.git
cd AI-Companion

# 构建原生库（需要NDK）
chmod +x scripts/build_native.sh
./scripts/build_native.sh

# 构建APK
./gradlew assembleDebug
```

### GitHub Actions 自动构建

本项目配置了完整的 CI/CD 流水线：

1. 推送到 `main` 或 `develop` 分支自动触发构建
2. 也可以在 Actions 页面手动触发（支持选择 debug/release）
3. 构建产物自动上传为 Artifact
4. Release 构建自动发布到 Releases

**配置 Secrets（用于 Release 签名）：**
- `KEYSTORE_BASE64`：签名密钥库的 base64 编码
- `KEYSTORE_PASSWORD`：密钥库密码
- `KEY_ALIAS`：密钥别名
- `KEY_PASSWORD`：密钥密码

## 首次使用

1. 安装 APK 后启动应用
2. 授予麦克风权限（用于语音输入）
3. 应用会自动下载模型文件（约300MB，需联网）
4. 模型下载完成后即可开始对话

## 目录结构

```
AI-Companion/
├── .github/workflows/    # CI/CD 配置
├── app/
│   ├── src/main/
│   │   ├── java/com/ai/companion/
│   │   │   ├── core/          # 核心引擎
│   │   │   │   ├── llm/       # LLM推理引擎
│   │   │   │   ├── audio/     # STT/TTS引擎
│   │   │   │   ├── avatar/    # 3D虚拟形象
│   │   │   │   ├── memory/    # 记忆系统
│   │   │   │   └── emotion/   # 情感引擎
│   │   │   ├── ui/            # 用户界面
│   │   │   │   ├── screens/   # 页面
│   │   │   │   ├── components/# 组件
│   │   │   │   └── theme/     # 主题
│   │   │   ├── service/       # 后台服务
│   │   │   └── data/          # 数据层
│   │   ├── cpp/               # C++原生代码
│   │   └── res/               # 资源文件
│   └── build.gradle.kts
├── scripts/                   # 构建脚本
└── README.md
```

## 模型下载

应用首次启动时会自动下载模型。你也可以手动下载：

```bash
# Qwen2.5-0.5B GGUF (Q4量化)
wget https://huggingface.co/Qwen/Qwen2.5-0.5B-GGUF/resolve/main/qwen2.5-0.5b-q4_k_m.gguf

# Whisper base (STT)
wget https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
```

## 许可证

MIT License