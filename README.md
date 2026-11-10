<div align="center">

# OpencodeM

**An AI-powered development client for Android with the real [opencode 2.2.5](https://opencode.ai) inside.**

**[English](README.md)** | [Русский](README.ru.md)

[![Release](https://img.shields.io/badge/release-v1.0-blue.svg?style=flat-square)](https://github.com)
[![Platform](https://img.shields.io/badge/platform-Android%209%2B%20%7C%20arm64--v8a-brightgreen.svg?style=flat-square)](https://developer.android.com)
[![License](https://img.shields.io/badge/license-MIT-green.svg?style=flat-square)](LICENSE)

</div>

---

OpencodeM is a native Android client for [opencode](https://github.com/anomalyco/opencode). Rather than just being an API frontend, the app runs the real **opencode 2.2.5** engine locally on your phone (compiled with Bun for Android) and communicates with it over its local HTTP and SSE API. Everything that works in opencode on desktop works directly on your phone.

> [!NOTE]
> **Architecture: `arm64-v8a` only**  
> OpencodeM requires a 64-bit ARM processor (`arm64-v8a`). 32-bit ARM (`armeabi-v7a`) is **not supported** because Bun's JavaScriptCore runtime strictly requires 64-bit tagged pointers (NaN-boxing).

---

## ✨ Features in 1.0

- **Real Opencode 2.2.5 Engine:** Full autonomous AI coding agent with tools, file system access, terminal execution, and subagents.
- **Root & Non-Root Support:**
  - Works out of the box on non-rooted devices via PRoot.
  - Full root support with **KernelSU**, **APatch**, and **Magisk** (includes interactive root pre-authorization prompts).
  - High-performance Alpine Linux execution via root `chroot` (bypassing Android 10+ PRoot pipe limitations).
- **VCS & Git Integration:**
  - Full Git support routed through Alpine Linux.
  - Dedicated Changes screen with file diffs, commit rollback, and one-tap `apk add git` installation.
- **Context Inspector:**
  - Material 3 bottom sheet showing real-time token usage, cost in USD, and Prompt Cache Hit Rate (⚡).
  - Token breakdown (System prompt, Workspace context, History) and compaction history.
- **Queue & Steering:**
  - Floating queue dock above composer for queuing instructions while the agent is busy.
  - One-tap steer or cancel queued tasks.
- **Local Offline Models:**
  - Built-in `llama.cpp` server (with optimizations for `armv8.2-a+dotprod+fp16` and `i8mm`).
  - Run Gemma, Qwen, or any GGUF model offline without internet access.
- **Material You UI:**
  - Modern Jetpack Compose UI adhering to Material 3 design guidelines.
  - Dynamic wallpaper-based theming on Android 12+.
- **Extensions & Ecosystem:**
  - Support for Model Context Protocol (MCP) servers (local and remote).
  - Custom OpenAI-compatible providers, custom models, and agents.

---

## 🛠️ Building from Source

### Prerequisites
- **JDK 17** (e.g., Temurin 17)
- **Android SDK** (API 35, Build-tools 35.0.0)
- **NDK 28.2+** and **CMake 3.22.1**
- **Git LFS** (for checking out precompiled native binaries in `jniLibs`)

### Build Release APK
On Windows:
```powershell
cd opencodem
.\build.ps1
```

On Linux / macOS:
```bash
cd opencodem
./build.sh
```

The signed release APK will be generated at `out/OpencodeM-1.0.apk` and copied to the project root.

---

## 🙏 Credits & Acknowledgments

OpencodeM is built upon the incredible work of the open-source community:

- **[narezany](https://git.narez.xyz/narezany/MargyCode)** — Original creator of **MargyCode** (based on v0.1/v0.2). Thank you for pioneering running opencode on Android.
- **[Anomaly (opencode.ai)](https://github.com/anomalyco/opencode)** — Creators of **opencode**, the AI-powered development tool.
- **[Oven-sh](https://bun.sh)** — For the **Bun** runtime.
- **[ggml-org / PrismML](https://github.com/ggml-org/llama.cpp)** — For **llama.cpp** mobile LLM inference.
- **[Termux](https://github.com/termux/proot)** — For the Android-compatible PRoot implementation.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
Third-party components (opencode, Bun, llama.cpp, proot, musl) are licensed under their respective open-source licenses.
