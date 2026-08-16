package com.gamewishingwell.agent

import android.content.Context
import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.data.GameMeta
import com.gamewishingwell.data.GameRepository
import com.gamewishingwell.data.LlmSettings
import com.gamewishingwell.data.SettingsRepository
import com.gamewishingwell.llm.AnthropicClient
import com.gamewishingwell.llm.LlmClient
import com.gamewishingwell.llm.LlmError
import com.gamewishingwell.llm.OpenAiCompatibleClient
import com.gamewishingwell.llm.Protocol
import com.gamewishingwell.llm.ProviderPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class GameSession(
    val messages: List<ChatMessage> = emptyList(),
    val currentHtml: String? = null,
    val streamingText: String? = null,
    val lastWarning: String? = null,
    val isGenerating: Boolean = false,
    val error: String? = null
)

/**
 * 游戏创作 Agent：维护会话状态，负责生成 / 迭代修改 / 失败自动重试 / 运行时错误修复 / 保存。
 * 状态通过 [session] 对外暴露，UI 直接订阅。
 */
class GameAgent(
    private val appContext: Context,
    private val repository: GameRepository,
    private val settingsRepository: SettingsRepository
) {

    private val _session = MutableStateFlow(GameSession())
    val session: StateFlow<GameSession> = _session.asStateFlow()

    /** 当前会话是否绑定到某个已保存的游戏（编辑模式），null 表示草稿模式。 */
    private var editingGameId: Long? = null

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ---------- 会话管理 ----------

    suspend fun loadDraftSession() {
        if (_session.value.isGenerating) return
        withContext(Dispatchers.IO) {
            val html = repository.loadDraftHtml()
            val messages = repository.loadDraftSession()
            editingGameId = null
            _session.value = GameSession(messages = messages, currentHtml = html)
        }
    }

    suspend fun loadGameSession(gameId: Long) {
        if (_session.value.isGenerating) return
        withContext(Dispatchers.IO) {
            val html = repository.loadGameHtml(gameId)
            val messages = repository.loadGameSession(gameId)
            editingGameId = gameId
            _session.value = GameSession(messages = messages, currentHtml = html)
        }
    }

    suspend fun newSession() {
        repository.clearDraft()
        editingGameId = null
        _session.value = GameSession()
    }

    // ---------- 核心：发送用户指令 ----------

    suspend fun sendUserMessage(text: String) {
        val current = _session.value
        if (current.isGenerating) return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        val llm = createClient()
        if (llm == null) {
            _session.value = current.copy(
                messages = current.messages + ChatMessage("user", trimmed),
                error = "请先在「设置」中配置 API Key、地址和模型"
            )
            return
        }

        // 同一条指令重复发起（错误重试/重做）时不再追加重复的 user 消息
        val last = current.messages.lastOrNull()
        val messages = if (last != null && last.isUser && last.content == trimmed) {
            current.messages
        } else {
            current.messages + ChatMessage("user", trimmed)
        }

        _session.value = current.copy(
            messages = messages,
            isGenerating = true,
            error = null,
            streamingText = ""
        )

        var html: String? = null
        var warnings: List<String> = emptyList()
        var apiError: String? = null
        var replyText = ""

        try {
            replyText = streamReply(llm, buildApiMessages(trimmed, null))
            var result = HtmlExtractor.extract(replyText)
            if (result.html == null) {
                // 自动重试一次，把失败原因回传给模型
                replyText = streamReply(
                    llm,
                    buildApiMessages(
                        trimmed,
                        "你的上一条回复中没有可运行的 HTML 代码。请只输出完整的 HTML 文件（用 ```html 代码围栏包裹），不要任何其他文字。"
                    )
                )
                result = HtmlExtractor.extract(replyText)
            }
            html = result.html
            warnings = result.warnings
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            apiError = e.message ?: "请求失败"
        }

        android.util.Log.d(
            "GameAgent",
            "生成结束: html=${html != null}, 回复长度=${replyText.length}, apiError=$apiError, 回复开头=${replyText.take(120)}"
        )

        val base = _session.value
        when {
            html != null -> {
                val spoken = HtmlExtractor.nonCodeText(replyText)
                val assistantText = spoken.ifBlank {
                    if (base.currentHtml.isNullOrBlank()) "游戏已生成，点击下方按钮立即游玩。" else "游戏已更新。"
                }
                val newSession = base.copy(
                    messages = base.messages + ChatMessage("assistant", assistantText),
                    currentHtml = html,
                    streamingText = null,
                    isGenerating = false,
                    error = null,
                    lastWarning = warnings.firstOrNull()
                )
                _session.value = newSession
                persistSession(newSession)
            }
            apiError != null -> {
                _session.value = base.copy(
                    messages = base.messages + ChatMessage("assistant", "（生成失败）"),
                    streamingText = null,
                    isGenerating = false,
                    error = "生成失败：$apiError"
                )
            }
            else -> {
                val spoken = HtmlExtractor.nonCodeText(replyText)
                // 空回复通常是推理模型把输出预算全花在思考上（finish_reason=length、content 为空）
                val errMsg = if (replyText.isBlank()) {
                    "模型没有输出任何内容（思考过程可能耗尽了输出额度）。请重试，或在「设置」中换用非推理模型（如 deepseek-chat）。"
                } else {
                    "未能从回复中提取到可运行的 HTML。请再试一次，或换个说法描述游戏。"
                }
                _session.value = base.copy(
                    messages = base.messages + ChatMessage("assistant", spoken.ifBlank { "（未生成游戏）" }),
                    streamingText = null,
                    isGenerating = false,
                    error = errMsg
                )
            }
        }
    }

    /** 把游戏运行时的 JS 错误交给 AI 修复。 */
    suspend fun fixWithError(jsError: String) {
        sendUserMessage("游戏运行时报错，请修复并输出完整新版代码（保持原有玩法）：\n$jsError")
    }

    // ---------- 保存 ----------

    suspend fun saveCurrentGame(title: String): GameMeta? {
        val s = _session.value
        val html = s.currentHtml ?: return null
        val editingId = editingGameId
        if (editingId != null) {
            // 编辑模式：更新已有游戏，而不是新建一条
            repository.updateGameHtml(editingId, html, s.messages, title)
            return repository.listGames().firstOrNull { it.id == editingId }
        }
        val description = s.messages.firstOrNull {
            it.isUser && !it.content.contains("推荐的游戏框架")
        }?.content?.trim()?.replace(Regex("\\s+"), " ")?.take(60) ?: ""
        val meta = repository.saveGame(title.trim(), description, html, s.messages)
        editingGameId = meta.id
        repository.clearDraft()
        return meta
    }

    suspend fun testConnection(): String {
        val llm = createClient() ?: throw LlmError("配置不完整")
        val sb = StringBuilder()
        llm.streamChat(
            listOf(ChatMessage("user", "请回复：连接成功")),
            onDelta = { sb.append(it) },
            onDone = {}
        )
        return sb.toString().trim()
    }

    // ---------- 内部 ----------

    private fun createClient(): LlmClient? {
        val s: LlmSettings = settingsRepository.settings.value
        if (!settingsRepository.isConfigured()) return null
        val preset = ProviderPresets.byId(s.providerId) ?: ProviderPresets.DEFAULT
        return if (preset.protocol == Protocol.ANTHROPIC) {
            AnthropicClient(okHttp, s.apiKey, s.baseUrl, s.model, preset.maxTokens)
        } else {
            OpenAiCompatibleClient(okHttp, s.apiKey, s.baseUrl, s.model, preset.maxTokens, preset.disableThinking)
        }
    }

    private fun buildApiMessages(instruction: String, fixInstruction: String?): List<ChatMessage> {
        val base = _session.value
        val msgs = mutableListOf(ChatMessage("system", GamePrompt.systemPrompt()))
        val html = base.currentHtml
        if (html.isNullOrBlank()) {
            msgs += ChatMessage(
                "user",
                "这是推荐的游戏框架示例，你可以完全自由地改写它，只需要参考它的结构和风格：\n\n${GamePrompt.readTemplate(appContext)}"
            )
        } else {
            msgs += ChatMessage(
                "user",
                "以下是当前游戏完整代码，请在其基础上按新要求修改（未要求的部分保持不变）：\n\n<game_code>\n$html\n</game_code>"
            )
        }
        msgs += ChatMessage("user", instruction)
        fixInstruction?.let { msgs += ChatMessage("user", it) }
        return msgs
    }

    private suspend fun streamReply(llm: LlmClient, messages: List<ChatMessage>): String {
        val sb = StringBuilder()        // 最终内容，用于抽码
        val thinking = StringBuilder()  // 思考过程，仅用于展示生成进度
        var lastShown = 0
        llm.streamChat(
            messages,
            onDelta = { d ->
                sb.append(d)
                _session.value = _session.value.copy(streamingText = combinedText(thinking, sb))
            },
            onThinking = { t ->
                thinking.append(t)
                // 思考过程按增量节流更新，避免每 token 全量拷贝会话状态
                if (thinking.length + sb.length - lastShown >= 150) {
                    _session.value = _session.value.copy(streamingText = combinedText(thinking, sb))
                    lastShown = thinking.length + sb.length
                }
            },
            onDone = {}
        )
        return sb.toString()
    }

    /** 生成中的展示文本：思考过程 + 已输出的代码。 */
    private fun combinedText(thinking: StringBuilder, content: StringBuilder): String {
        val t = thinking.toString().trim()
        if (t.isEmpty()) return content.toString()
        return if (content.isNotEmpty()) "思考中…\n\n$t\n\n$content" else "思考中…\n\n$t"
    }

    private suspend fun persistSession(s: GameSession) {
        withContext(Dispatchers.IO) {
            val html = s.currentHtml ?: return@withContext
            val editingId = editingGameId
            if (editingId != null) {
                repository.updateGameHtml(editingId, html, s.messages)
            } else {
                repository.saveDraft(html, s.messages)
            }
        }
    }
}
