package com.gamewishingwell.llm

import com.gamewishingwell.data.ChatMessage

class LlmError(message: String) : Exception(message)

/**
 * 统一的流式 LLM 客户端接口。
 * 流式过程中通过 [onDelta] 逐个字符推送最终内容，[onThinking] 推送模型的思考过程
 * （推理模型如 deepseek-v4-flash 会先输出 reasoning_content；按 instruct.txt 约定，
 * 前端不展示源代码与思考原文，只把流用于内部进度判断），
 * 结束调用 [onDone]；网络/API 错误以异常抛出。
 */
interface LlmClient {
    val protocol: Protocol

    suspend fun streamChat(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit = {},
        onDone: () -> Unit
    )
}

/** 合并相邻的相同 role 消息（Anthropic 要求消息交替，OpenAI 也接受合并后的紧凑上下文）。 */
internal fun mergeConsecutive(messages: List<ChatMessage>): List<ChatMessage> =
    messages.fold(mutableListOf()) { acc, m ->
        if (acc.isNotEmpty() && acc.last().role == m.role) {
            val last = acc.removeAt(acc.size - 1)
            acc.add(last.copy(content = last.content + "\n\n" + m.content))
        } else {
            acc.add(m)
        }
        acc
    }
