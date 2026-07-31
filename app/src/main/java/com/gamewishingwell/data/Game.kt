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

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
) {
    val isUser: Boolean get() = role == "user"
    val isSystem: Boolean get() = role == "system"
}
