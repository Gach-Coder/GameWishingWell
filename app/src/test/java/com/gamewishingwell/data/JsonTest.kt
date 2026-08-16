package com.gamewishingwell.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `游戏索引序列化往返`() {
        val index = GameIndex(
            games = listOf(
                GameMeta(id = 1001L, title = "打地鼠", description = "一个打地鼠小游戏", createdAt = 1001L, updatedAt = 1002L, playCount = 3),
                GameMeta(id = 1002L, title = "接水果", createdAt = 1003L, updatedAt = 1003L)
            )
        )
        val encoded = json.encodeToString(index)
        val decoded = json.decodeFromString<GameIndex>(encoded)
        assertEquals(index, decoded)
    }

    @Test
    fun `聊天消息序列化往返`() {
        val messages = listOf(
            ChatMessage("user", "做一个打地鼠游戏"),
            ChatMessage("assistant", "游戏已生成")
        )
        val encoded = json.encodeToString(messages)
        val decoded = json.decodeFromString<List<ChatMessage>>(encoded)
        assertEquals(messages, decoded)
    }

    @Test
    fun `未知字段被忽略`() {
        val withUnknown = """{"id": 1, "title": "x", "createdAt": 1, "updatedAt": 1, "future": "field"}"""
        val meta = json.decodeFromString<GameMeta>(withUnknown)
        assertEquals("x", meta.title)
    }
}
