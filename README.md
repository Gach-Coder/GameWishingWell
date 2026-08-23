# 许愿井 GameWishingWell ⛲

<div align="center">

**安卓端 AI 游戏创作 Agent 平台 —— 一句话描述，即刻生成、即刻游玩**

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![API](https://img.shields.io/badge/API-26%2B-00B0FF?logo=android&logoColor=white)](https://developer.android.com/about/dashboards)
[![Version](https://img.shields.io/badge/version-0.1.0-blue)]()

</div>

<!-- 建议在此处补充一张 App 截图或 GIF 演示：
<p align="center"><img src="docs/screenshot.png" width="280"/></p> -->

## 💡 核心理念

> **游戏不是编译出来的，而是 AI 生成的文本 = 可运行产物（HTML 文件）。**

用户用自然语言描述游戏（如"做一个打地鼠小游戏"），LLM 以 SSE 流式输出（打字机效果）生成自包含的 HTML + CSS + JavaScript，由 Android WebView（Chromium 内核）解释执行——**无编译环节，生成即玩**。鸿蒙 ArkWeb 同为 Chromium 内核，双端可复用。

## ✨ 功能特性

- 💬 **对话式创作** —— 交互形态类似豆包/元宝的对话 Agent，左侧 AI、右侧用户；发送键可随时变为停止键中断 Agent Loop，中断后可直接尝试游玩（可能存在运行错误，属正常现象）
- 🤖 **内置 Agent Loop** —— 意图识别、Prompt 工程、代码生成与结构化校验、失败自动重试，尽量保证生成代码可直接运行
- 🔒 **文件沙箱** —— tool call 的读写权限严格限制在该游戏的文件夹内；版本化写入 + 指针切换、行级 patch 修改、hash 校验防并发覆盖
- ✅ **结构化校验** —— JS 语法（acorn 同等能力）+ HTML 配对 + `no-undef` 规则校验，另有 WebView 隔离 iframe 确定性 tick 冒烟测试
- 🐛 **运行时报错 → 一键 AI 修复** —— JS 报错弹出覆盖层并格式化回传（file:line + stack + console 片段），回到 Agent Loop 自动修复；也可继续对话迭代（如"加连击计分"）
- 🎮 **游戏库管理** —— 成品保存到本地游戏库，每个游戏一个文件夹；支持重命名、继续对话编辑、删除、草稿恢复
- 🔄 **后台持续生成** —— 切换页面/对话时，生成任务在 ViewModel 协程与全局 GameAgent 中继续运行，完成后可在 WebView 中立刻游玩
- 🖥️ **运行时增强（VIEWPORT_PRELUDE）** —— 自动修复 vh 视口问题、注入游戏内设置面板（音量控制/暂停/重新开始）、自动隐藏游戏自带的重复重开按钮
- 🔐 **API Key 加密存储** —— Android Keystore AES-GCM 加密
- ⚙️ **多厂商 LLM 配置** —— DeepSeek / Kimi / OpenAI / Anthropic Claude / 自定义，支持修改 Base URL、模型名、系统提示词、思考能力开关，并可"测试连接"

## 🧠 Agent 工作流

一次创作请求在 Agent 内部经过以下分层处理：

```mermaid
flowchart TD
    A[用户输入] --> B{意图层<br/>feature / bug / chat}
    B -- chat --> C[纯文本回复<br/>readonly 不改文件]
    B -- feature / bug --> D{识别层<br/>Game Schema JSON}
    D --> E[策划层<br/>逐系统策划实现方式]
    E --> F{确认门<br/>卡片回显勾选}
    F -- 用户输入修正 --> E
    F -- 用户确认 --> G[决策层<br/>完整策划 Schema JSON]
    G --> H[Agent Loop<br/>plan → implement → validate]
    H -- 校验失败 --> H
    H -- 校验通过 --> I[WebView 运行游戏]
    I -- 运行时报错 --> H
    I -- 用户继续迭代 --> H
    I --> J[✅ 保存游戏库<br/>快照 + 增量持久化]
```

<details>
<summary><b>📋 各层职责详解（点击展开）</b></summary>

| 层级 | 职责 |
| --- | --- |
| **意图层** | 分类用户意图：`feature`（增/改/删游戏特性）、`bug`（程序异常）、`chat`（纯聊天询问，不改动任何文件） |
| **识别层** | 正则 + Lite LLM 抽取游戏特征（画面维度、画面方向、游戏系统、对标游戏），补全或修正会话级 Game Schema JSON；先过 JSON Schema + 枚举白名单校验；对标游戏映射到内部模板库 `template_id` |
| **策划层** | 策划子 Agent 针对每个 module（人物实体、道具、战斗、技能、关卡等）阐述合理实现方式与验收边界，不涉及引擎/算法等底层技术细节 |
| **确认门** | 卡片回显策划结果，每个 module 带勾选框（默认勾选）；用户可点确认，或再次输入文本修正（增删 module / 调整实现），回到策划层重组摘要，直到确认 |
| **决策层** | 按用户最终确认的信息输出完整策划 Schema JSON；按 `template_class`（画面维度×画面方向×主类型）选取特征矩阵，只做"选系统 + 覆盖参数"，不自由发明 |
| **Agent Loop** | 先出文件计划和依赖顺序 → implement → validate，中途不换文件切分方案；上下文最小化（rolling summary + 本次要动的文件）；生成代码契约：无 eval、无动态 require、浏览器全局白名单、声明前置；不设超时与重试预算 |
| **持久化** | GameSession 持久化：文件清单、设计 Schema、known-issues、决策 log、`schema_version`，每通过校验的版本打快照（可回滚）+ 增量持久化（只存变更文件） |

</details>

## 🏗️ 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言 / 构建 | Kotlin 2.0.21 · Gradle Wrapper 8.11.1 · AGP 8.9.1 · JDK 17 |
| UI | Jetpack Compose（BOM 2024.12.01）· Material 3 · Navigation Compose 2.8.5 |
| 架构 | 单 Activity + Compose · MVVM（ViewModel + StateFlow）· 手动 DI（AppContainer） |
| 网络 / 序列化 | OkHttp 4.12.0（LLM SSE 流式）· kotlinx-serialization-json 1.7.3 |
| 异步 / WebView | kotlinx-coroutines 1.9.0 · androidx.webkit 1.12.1 |
| 安全 | Android Keystore AES-GCM（API Key 加密存储） |
| 测试 | JUnit 4.13.2 · OkHttp MockWebServer 4.12.0 |
| SDK | minSdk 26 · targetSdk / compileSdk 36 · versionName 0.1.0 |

## 📁 项目结构

```text
GameWishingWell/
├─ gradle/libs.versions.toml          # 全工程版本目录（version catalog）
└─ app/src/
   ├─ main/
   │  ├─ assets/game_template.html    # 首次生成时给 LLM 的示例游戏模板
   │  └─ java/com/gamewishingwell/
   │     ├─ MainActivity.kt           # 唯一 Activity / Compose 入口
   │     ├─ WishwellApplication.kt    # Application + AppContainer 手动 DI
   │     ├─ agent/                    # 创作Agent 核心
   │     │  ├─ GameAgent.kt           #   核心状态机 / Agent Loop
   │     │  ├─ GameIntent.kt          #   意图层（feature/bug/chat）
   │     │  ├─ GameRecognition.kt     #   识别层：Schema JSON / 白名单 / 确认门
   │     │  ├─ GamePlan.kt            #   策划层：P0/P1/P2、验收清单
   │     │  ├─ GamePrompt.kt          #   system 提示词 / 代码契约
   │     │  ├─ GameValidator.kt       #   JS/HTML 结构化校验
   │     │  ├─ GameSmokeTest.kt       #   WebView 冒烟测试
   │     │  ├─ GameFileWorkspace.kt   #   文件沙箱 / 版本化写入 / 行级 patch
   │     │  └─ ...
   │     ├─ llm/                      # LLM 客户端
   │     │  ├─ LlmClient.kt           #   流式客户端统一接口
   │     │  ├─ OpenAiCompatibleClient.kt
   │     │  ├─ AnthropicClient.kt
   │     │  └─ ProviderPreset.kt      #   厂商预设与协议枚举
   │     ├─ data/                     # 游戏库 / 设置 / 加密存储
   │     └─ ui/                        # Compose UI
   │        ├─ home/ · chat/ · game/ · settings/
   │        └─ viewmodels/
   └─ test/                            # 本地 JVM 单元测试
```

## 🚀 快速开始

### 环境要求

- JDK 17（Android Studio 自带 JBR 即可）
- Android SDK（`local.properties` 中配置 `sdk.dir`，机器相关、不入库）
- 模拟器或真机（Android 8.0 / API 26 及以上）

### 构建运行

```bash
# 克隆项目
git clone https://github.com/Gach-Coder/GameWishingWell.git
cd GameWishingWell

# 编译 Debug APK（Windows 下使用 gradlew.bat）
./gradlew assembleDebug

# 运行单元测试
./gradlew testDebugUnitTest

# 连接设备/模拟器后安装
./gradlew installDebug
# 或直接 adb 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 输出：`app/build/outputs/apk/debug/app-debug.apk`
单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`

### 首次使用

1. 进入 **设置**，选择 LLM 服务商，填写 API Key（必要时修改 Base URL / 模型名）
2. 点击 **测试连接** 确认可用
3. 进入 **创作**，用一句话描述游戏并发送，观察流式生成
4. 在确认门卡片中勾选/确认游戏系统
5. 生成完成后 **立即游玩**，或 **保存** 到 **我的游戏**

## ⚙️ LLM 厂商预设

| 厂商 | Base URL | 默认模型 | 协议 | 最大 tokens |
| --- | --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-v4-flash` | OpenAI 兼容 | 16384 |
| Kimi (Moonshot) | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` | OpenAI 兼容 | 16384 |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` | OpenAI 兼容 | 16384 |
| Claude | `https://api.anthropic.com` | `claude-sonnet-4-6` | Anthropic | 16384 |
| 自定义 | 自填 | 自填 | OpenAI 兼容 | 8192 |

> 💡 DeepSeek 默认模型为 `deepseek-v4-flash` / `deepseek-v4-pro`（`deepseek-chat` 已弃用）。
> 设置页另有 **模型思考能力** 开关（默认关闭）：OpenAI 兼容协议关闭时发送 `thinking={"type":"disabled"}`，Anthropic 开启时发送 extended thinking。

## ❓ 常见问题

<details>
<summary><b>构建同步报 SDK / JDK 错误</b></summary>

检查 `local.properties` 的 `sdk.dir` 是否指向本机 Android SDK，并确认使用 JDK 17。
</details>

<details>
<summary><b>提示"请先在设置中配置 API Key、地址和模型"</b></summary>

`isConfigured()` 要求 API Key、Base URL、模型名三项均非空，补全后重新测试连接。
</details>

<details>
<summary><b>模型输出为空或抽不到 HTML</b></summary>

检查所选模型/网关是否支持流式 `chat/completions`；确认 max_tokens（默认 16384）与模型名正确。
</details>

<details>
<summary><b>游戏白屏</b></summary>

`HtmlEnhancer` 已做视口修复，但模型仍需遵守"自包含、无外部资源"的提示词约束；可在报错覆盖层点击"让 AI 修复"。
</details>

## 📜 版本说明

- 当前版本 `0.1.0`（versionCode 1），release 暂关闭混淆
- 仓库使用 Aliyun 镜像优先（gradle-plugin / google / public），`google()` / `mavenCentral()` 兜底

---

<div align="center">

**⛲ 许愿井 Wishwell —— 向井里许个愿，AI 帮你把游戏做出来。**

</div>
