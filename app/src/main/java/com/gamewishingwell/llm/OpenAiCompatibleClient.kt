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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容协议的流式客户端（适用于 DeepSeek / Kimi(Moonshot) / OpenAI 等）。
 * 接口：POST {baseUrl}/chat/completions，SSE 输出 data: {...} / data: [DONE]。
 */
class OpenAiCompatibleClient(
    private val okHttp: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val maxTokens: Int = 8192,
    /** 是否发送 thinking={"type":"disabled"} 关闭推理模型的思考过程（由设置页思考开关驱动，默认关闭）。 */
    private val disableThinking: Boolean = false
) : LlmClient {

    override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE

    override suspend fun streamChat(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit,
        onDone: () -> Unit
    ) {
        // runInterruptible + activeCall.cancel()：停止键取消协程时会中断阻塞中的 OkHttp SSE 读取。
        // 这里不设任何超时，默认用户可无限等待；只有用户主动停止才会取消底层网络调用。
        val activeCall = AtomicReference<Call?>(null)
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            activeCall.get()?.cancel()
        }
        runInterruptible(Dispatchers.IO) {
            try {
                executeStream(buildRequest(messages, includeMaxTokens = true, includeThinking = true), onDelta, onThinking, onDone, activeCall)
            } catch (e: LlmError) {
                if (Thread.currentThread().isInterrupted) throw e
                val msg = e.message ?: ""
                when {
                    // 个别老模型不支持 max_tokens 字段，收到相关 4xx 时去掉该字段重试
                    msg.contains("max_tokens", ignoreCase = true) ->
                        executeStream(buildRequest(messages, includeMaxTokens = false, includeThinking = true), onDelta, onThinking, onDone, activeCall)
                    // 个别网关不支持 thinking 字段，去掉后重试
                    msg.contains("thinking", ignoreCase = true) ->
                        executeStream(buildRequest(messages, includeMaxTokens = true, includeThinking = false), onDelta, onThinking, onDone, activeCall)
                    else -> throw e
                }
            }
        }
    }

    private fun buildRequest(messages: List<ChatMessage>, includeMaxTokens: Boolean, includeThinking: Boolean): Request {
        val body = Json.encodeToString(
            OpenAiChatRequest(
                model = model,
                stream = true,
                max_tokens = if (includeMaxTokens) maxTokens else null,
                thinking = if (includeThinking && disableThinking) ThinkingConfig("disabled") else null,
                messages = mergeConsecutive(messages)
            )
        )
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun executeStream(
        request: Request,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit,
        onDone: () -> Unit,
        activeCall: AtomicReference<Call?>
    ) {
        val call = okHttp.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(300) ?: ""
                throw LlmError("API 错误 ${response.code}：$err")
            }
            val source = response.body!!.source()
            // 默认用户可无限等待：不做内容停滞超时，服务端多久没有新内容都继续等；
            // 用户主动按停止键时由 runInterruptible 中断阻塞读取。
            var reasoningChars = 0L
            var contentChars = 0L
            var lastFinishReason: String? = null
            var lastPayloadTail: String? = null
            var endedByDone = false
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") { endedByDone = true; break }
                    val (content, reasoning) = parseDelta(payload)
                    lastPayloadTail = payload.takeLast(120)
                    try {
                        lastFinishReason = Json.parseToJsonElement(payload).jsonObject["choices"]
                            ?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")
                            ?.jsonPrimitive?.contentOrNull ?: lastFinishReason
                    } catch (e: Exception) { /* 忽略解析失败 */ }
                    if (content != null || reasoning != null) {
                        if (content != null) { contentChars += content.length; onDelta(content) }
                        if (reasoning != null) { reasoningChars += reasoning.length; onThinking(reasoning) }
                    }
                }
            }
            android.util.Log.d(
                "OpenAiClient",
                "流结束: done=$endedByDone 推理字符=$reasoningChars 内容字符=$contentChars " +
                    "finish_reason=$lastFinishReason 尾帧=$lastPayloadTail"
            )
            onDone()
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    /** 返回 (最终内容, 思考过程)，推理模型思考阶段 content 为 null、reasoning_content 有值。 */
    private fun parseDelta(payload: String): Pair<String?, String?> = try {
        val el = Json.parseToJsonElement(payload).jsonObject
        val delta = el["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject
        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
        val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
        content to reasoning
    } catch (e: Exception) {
        null to null
    }

    @Serializable
    private data class OpenAiChatRequest(
        val model: String,
        val stream: Boolean,
        val max_tokens: Int? = null,
        val thinking: ThinkingConfig? = null,
        val messages: List<ChatMessage>
    )

    @Serializable
    private data class ThinkingConfig(val type: String)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
