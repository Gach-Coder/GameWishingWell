package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicReference

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
        // runInterruptible + activeCall.cancel()：停止键取消协程时中断阻塞中的 OkHttp SSE 读取。
        // 这里不设任何超时，默认用户可无限等待；只有用户主动停止才会取消底层网络调用。
        val activeCall = AtomicReference<Call?>(null)
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            activeCall.get()?.cancel()
        }
        runInterruptible(Dispatchers.IO) {
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

            val call = okHttp.newCall(request)
            activeCall.set(call)
            try {
                call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(300) ?: ""
                    throw LlmError("API 错误 ${response.code}：$err")
                }
                val source = response.body!!.source()
                // 默认用户可无限等待：不做内容停滞超时，用户主动停止时由 runInterruptible 中断读取。
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val delta = parseDelta(line.removePrefix("data:").trim())
                    if (delta != null) {
                        onDelta(delta)
                    }
                }
                onDone()
                }
            } finally {
                activeCall.compareAndSet(call, null)
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
    }
}
