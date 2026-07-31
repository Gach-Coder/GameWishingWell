package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Anthropic Messages API 的流式客户端。
 * 接口：POST {baseUrl}/v1/messages，SSE 输出 event:/data:，正文取 content_block_delta 的 text。
 */
class AnthropicClient(
    private val okHttp: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val maxTokens: Int = 8192
) : LlmClient {

    override val protocol: Protocol = Protocol.ANTHROPIC

    override suspend fun streamChat(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit,
        onDone: () -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            val chat = mergeConsecutive(messages.filterNot { it.role == "system" })
            // Anthropic 要求首条消息为 user 且角色交替
            val normalized = normalizeRoles(chat)

            val requestBody = Json.encodeToString(
                AnthropicChatRequest(
                    model = model,
                    max_tokens = maxTokens,
                    stream = true,
                    system = system,
                    messages = normalized
                )
            )
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Accept", "text/event-stream")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(300) ?: ""
                    throw LlmError("API 错误 ${response.code}：$err")
                }
                val source = response.body!!.source()
                // 与 OpenAI 客户端一致的内容停滞保护：绝对截止时间仅在收到实际内容时重新武装，
                // 服务端静默挂起连接时 readUtf8Line 会阻塞，必须用 source 层超时兜底
                source.timeout().deadline(STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                while (true) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: InterruptedIOException) {
                        break
                    } ?: break
                    if (!line.startsWith("data:")) continue
                    val delta = parseDelta(line.removePrefix("data:").trim())
                    if (delta != null) {
                        source.timeout().deadline(STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        onDelta(delta)
                    }
                }
                onDone()
            }
        }
    }

    private fun normalizeRoles(messages: List<ChatMessage>): List<ChatMessage> {
        var list = messages
        if (list.isNotEmpty() && list.first().role != "user") {
            list = listOf(ChatMessage("user", "请继续。")) + list
        }
        val result = mutableListOf<ChatMessage>()
        for (m in list) {
            val role = if (m.role == "assistant") "assistant" else "user"
            result.add(m.copy(role = role))
        }
        return result
    }

    private fun parseDelta(payload: String): String? = try {
        val obj = Json.parseToJsonElement(payload).jsonObject
        if (obj["type"]?.jsonPrimitive?.content == "content_block_delta") {
            obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    @Serializable
    private data class AnthropicChatRequest(
        val model: String,
        val max_tokens: Int,
        val stream: Boolean,
        val system: String = "",
        val messages: List<ChatMessage>
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 流内容停滞判定阈值：超过该时长没有任何新内容即强制结束流。 */
        const val STALL_TIMEOUT_MS = 60_000L
    }
}
