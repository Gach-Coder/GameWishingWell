package com.gamewishingwell.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 游戏库存储：
 * - 已保存游戏：filesDir/games/<id>/index.html + session.json，索引 filesDir/games.json
 * - 未保存草稿：filesDir/drafts/latest.html + session.json
 */
class GameRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val gamesDir: File by lazy { File(context.filesDir, "games").apply { mkdirs() } }
    private val draftsDir: File by lazy { File(context.filesDir, "drafts").apply { mkdirs() } }
    private val indexFile: File by lazy { File(context.filesDir, "games.json") }

    // ---------- 游戏库 ----------

    suspend fun listGames(): List<GameMeta> = withContext(Dispatchers.IO) {
        readIndex().games.sortedByDescending { it.updatedAt }
    }

    suspend fun saveGame(
        title: String,
        description: String,
        html: String,
        session: List<ChatMessage>
    ): GameMeta = withContext(Dispatchers.IO) {
        val id = System.currentTimeMillis()
        val dir = File(gamesDir, id.toString()).apply { mkdirs() }
        File(dir, "index.html").writeText(html, Charsets.UTF_8)
        File(dir, "session.json").writeText(json.encodeToString(session), Charsets.UTF_8)
        val meta = GameMeta(
            id = id,
            title = title,
            description = description,
            createdAt = id,
            updatedAt = id
        )
        val idx = readIndex()
        writeIndex(GameIndex(idx.games + meta))
        meta
    }

    suspend fun updateGameHtml(
        gameId: Long,
        html: String,
        session: List<ChatMessage>,
        title: String? = null
    ) {
        withContext(Dispatchers.IO) {
            val dir = File(gamesDir, gameId.toString())
            if (!dir.exists()) return@withContext
            File(dir, "index.html").writeText(html, Charsets.UTF_8)
            File(dir, "session.json").writeText(json.encodeToString(session), Charsets.UTF_8)
            val idx = readIndex()
            writeIndex(
                GameIndex(
                    idx.games.map {
                        if (it.id == gameId) {
                            it.copy(updatedAt = System.currentTimeMillis(), title = title ?: it.title)
                        } else {
                            it
                        }
                    }
                )
            )
        }
    }

    suspend fun loadGameHtml(gameId: Long): String? = withContext(Dispatchers.IO) {
        val f = File(File(gamesDir, gameId.toString()), "index.html")
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    suspend fun loadGameSession(gameId: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
        val f = File(File(gamesDir, gameId.toString()), "session.json")
        decodeOrEmpty(f)
    }

    suspend fun deleteGame(gameId: Long) {
        withContext(Dispatchers.IO) {
            File(gamesDir, gameId.toString()).deleteRecursively()
            val idx = readIndex()
            writeIndex(GameIndex(idx.games.filterNot { it.id == gameId }))
        }
    }

    suspend fun touchPlay(gameId: Long) {
        withContext(Dispatchers.IO) {
            val idx = readIndex()
            writeIndex(
                GameIndex(
                    idx.games.map {
                        if (it.id == gameId) it.copy(playCount = it.playCount + 1) else it
                    }
                )
            )
        }
    }

    // ---------- 草稿 ----------

    suspend fun saveDraft(html: String, session: List<ChatMessage>) {
        withContext(Dispatchers.IO) {
            File(draftsDir, "latest.html").writeText(html, Charsets.UTF_8)
            File(draftsDir, "session.json").writeText(json.encodeToString(session), Charsets.UTF_8)
        }
    }

    suspend fun loadDraftHtml(): String? = withContext(Dispatchers.IO) {
        val f = File(draftsDir, "latest.html")
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    suspend fun loadDraftSession(): List<ChatMessage> = withContext(Dispatchers.IO) {
        decodeOrEmpty(File(draftsDir, "session.json"))
    }

    suspend fun hasDraft(): Boolean = withContext(Dispatchers.IO) {
        File(draftsDir, "latest.html").exists()
    }

    suspend fun clearDraft() {
        withContext(Dispatchers.IO) {
            File(draftsDir, "latest.html").delete()
            File(draftsDir, "session.json").delete()
        }
    }

    // ---------- 内部 ----------

    private fun decodeOrEmpty(f: File): List<ChatMessage> {
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ChatMessage>>(f.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun readIndex(): GameIndex {
        if (!indexFile.exists()) return GameIndex()
        return runCatching {
            json.decodeFromString<GameIndex>(indexFile.readText(Charsets.UTF_8))
        }.getOrDefault(GameIndex())
    }

    private fun writeIndex(idx: GameIndex) {
        indexFile.writeText(json.encodeToString(idx), Charsets.UTF_8)
    }
}
