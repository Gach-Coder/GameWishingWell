package com.gamewishingwell.data

import kotlinx.serialization.Serializable

@Serializable
data class GameMeta(
    val id: Long,
    val title: String,
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val playCount: Int = 0
)

@Serializable
data class GameIndex(
    val games: List<GameMeta> = emptyList()
)

/** 文件可视系统条目：游戏存储文件夹内一个文件/子目录的概要信息。 */
data class GameFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    /** 目录内的直接子项数（文件恒为 0）。 */
    val childCount: Int = 0,
    val lastModified: Long
)

/** 文件可视系统：只读打开一个文本文件的读取结果（仅供查看与选中复制，无写入路径）。 */
data class GameFileContent(
    val name: String,
    /** 相对游戏根目录（games/<id>）的路径，如 ".versions/3-index.html"。 */
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** 展示的文本内容；[truncated] 为真时只是前缀。 */
    val text: String,
    val truncated: Boolean,
    /** 含 NUL 字节，视为二进制文件，不提供文本预览（text 为空）。 */
    val binary: Boolean
)

/** 一次工具调用请求：id 与参数原样字符串由协议层产出，执行层自行解析参数 JSON。 */
@Serializable
data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /** assistant 消息携带的工具调用请求；其余角色恒为空列表。 */
    val toolCalls: List<ToolCallData> = emptyList(),
    /** role="tool" 的消息对应的调用 id；其余角色为 null。 */
    val toolCallId: String? = null
) {
    val isUser: Boolean get() = role == "user"
    val isSystem: Boolean get() = role == "system"
    val isTool: Boolean get() = role == "tool"

    companion object {
        /** 确认门卡片消息：content 为 IntentConfirmation 的精简 JSON，随聊天流持久化。 */
        const val ROLE_CONFIRM_CARD = "confirm_card"
        /** 工具结果消息的 role；仅存在于 Agent Loop 内部上下文，不入聊天流。 */
        const val ROLE_TOOL = "tool"
    }

    val isConfirmCard: Boolean get() = role == ROLE_CONFIRM_CARD
}
