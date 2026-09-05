package com.gamewishingwell.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 游戏库存储：
 * - 已保存游戏（运行区）：filesDir/games/<id>/index.html + session.json + agent_state.json，索引 filesDir/games.json
 * - 未保存草稿：filesDir/drafts/latest.html + session.json + agent_state.json
 * - 编辑区：filesDir/agent/workspace/<会话>/（GameAgent 的工具沙箱），与运行区相互独立——
 *   编辑会话期间本仓库的游戏文件不被写入，保存按钮（overwriteGameFiles/saveGame）才把
 *   编辑区文件整体覆盖到运行区。
 *
 * HTML 写入采用“版本化文件 + 指针切换”：
 * 每次更新先把旧 index.html 归档到 .versions/<v>-index.html，再写新文件并更新
 * current.txt 指针与 .sha256 哈希；同会话由 [writeMutex] 加锁，写入前校验 hash，
 * 避免并发覆盖。对外仍然保留 index.html 作为最新可运行副本，兼容旧数据。
 */
class GameRepository(private val context: Context) {

    private companion object {
        /** 游戏入口文件名（工作区与保存区一致）。 */
        const val ENTRY_HTML = "index.html"

        /** 保存点目录名（保存时冻结的上下文快照，撤销锚点）。 */
        const val SAVEPOINT_DIR = ".savepoint"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val writeMutex = Mutex()

    private val gamesDir: File by lazy { File(context.filesDir, "games").apply { mkdirs() } }
    private val draftsDir: File by lazy { File(context.filesDir, "drafts").apply { mkdirs() } }
    private val indexFile: File by lazy { File(context.filesDir, "games.json") }

    // ---------- 游戏库 ----------

    suspend fun listGames(): List<GameMeta> = withContext(Dispatchers.IO) {
        readIndex().games.sortedByDescending { it.updatedAt }
    }

    /**
     * 新游戏入库。[files] 为编辑区（工作区）的全部游戏文件：
     * index.html 版本化写入，其余文件（如 scenarios.json）平写落盘。
     */
    suspend fun saveGame(
        title: String,
        description: String,
        files: Map<String, String>,
        session: List<ChatMessage>
    ): GameMeta = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val id = System.currentTimeMillis()
            val dir = File(gamesDir, id.toString()).apply { mkdirs() }
            writeGameFilesLocked(dir, files)
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
    }

    /**
     * 保存按钮：以编辑区（工作区）的游戏文件整体覆盖保存区（games/<id>，首页保存的游戏）。
     * [files] 的 index.html 走版本化写入（保留回滚历史），其余文件平写；
     * [title] 非空时顺带重命名，索引 updatedAt 只在此处（真正的入库）推进。
     */
    suspend fun overwriteGameFiles(
        gameId: Long,
        files: Map<String, String>,
        session: List<ChatMessage>,
        title: String? = null
    ) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val dir = File(gamesDir, gameId.toString())
                if (!dir.exists()) return@withLock
                writeGameFilesLocked(dir, files)
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
    }

    /** 游戏文件落盘：入口文件版本化，其余平写；子目录自动创建。 */
    private fun writeGameFilesLocked(dir: File, files: Map<String, String>) {
        files[ENTRY_HTML]?.let { versionedWrite(File(dir, ENTRY_HTML), it) }
        files.forEach { (name, content) ->
            if (name == ENTRY_HTML) return@forEach
            val f = File(dir, name)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }
    }

    /**
     * 只同步会话簿记（聊天消息），不写游戏文件、不推进 updatedAt——
     * 编辑会话期间运行区（games/<id>）保持入库版本不动，首页"更新时间"
     * 只反映玩家最后一次按保存按钮入库的版本。
     */
    suspend fun updateGameSessionOnly(gameId: Long, session: List<ChatMessage>) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val dir = File(gamesDir, gameId.toString())
                if (!dir.exists()) return@withLock
                File(dir, "session.json").writeText(json.encodeToString(session), Charsets.UTF_8)
            }
        }
    }

    suspend fun renameGame(gameId: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val clean = newTitle.trim()
            if (clean.isBlank()) return@withLock false
            val idx = readIndex()
            if (idx.games.none { it.id == gameId }) return@withLock false
            writeIndex(
                GameIndex(
                    idx.games.map {
                        if (it.id == gameId) it.copy(title = clean, updatedAt = System.currentTimeMillis()) else it
                    }
                )
            )
            true
        }
    }

    suspend fun loadGameHtml(gameId: Long): String? = withContext(Dispatchers.IO) {
        val f = File(File(gamesDir, gameId.toString()), ENTRY_HTML)
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    /** 读取保存区（games/<id>）内的单个游戏文件（如 scenarios.json）；不存在返回 null。 */
    suspend fun loadGameFile(gameId: Long, name: String): String? = withContext(Dispatchers.IO) {
        val f = File(File(gamesDir, gameId.toString()), name)
        if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    suspend fun loadGameSession(gameId: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
        val f = File(File(gamesDir, gameId.toString()), "session.json")
        decodeOrEmpty(f)
    }

    suspend fun saveGameAgentState(gameId: Long, stateJson: String) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val dir = File(gamesDir, gameId.toString())
                if (dir.exists()) {
                    File(dir, "agent_state.json").writeText(stateJson, Charsets.UTF_8)
                }
            }
        }
    }

    suspend fun loadGameAgentState(gameId: Long): String? = withContext(Dispatchers.IO) {
        val f = File(File(gamesDir, gameId.toString()), "agent_state.json")
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    // ---------- 保存点（撤销锚点） ----------

    /** 保存区内的保存点目录：保存时冻结的会话上下文快照，撤销（保存的逆操作）从此恢复。 */
    private fun savepointDir(gameId: Long): File =
        File(File(gamesDir, gameId.toString()), SAVEPOINT_DIR)

    suspend fun writeSavepoint(gameId: Long, session: List<ChatMessage>, agentStateJson: String) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val dir = savepointDir(gameId).apply { mkdirs() }
                File(dir, "session.json").writeText(json.encodeToString(session), Charsets.UTF_8)
                File(dir, "agent_state.json").writeText(agentStateJson, Charsets.UTF_8)
            }
        }
    }

    suspend fun loadSavepointSession(gameId: Long): List<ChatMessage>? = withContext(Dispatchers.IO) {
        val f = File(savepointDir(gameId), "session.json")
        if (f.isFile) decodeOrEmpty(f).takeIf { it.isNotEmpty() } else null
    }

    suspend fun loadSavepointAgentState(gameId: Long): String? = withContext(Dispatchers.IO) {
        val f = File(savepointDir(gameId), "agent_state.json")
        if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    /** 回滚到指定版本（默认回滚到上一个通过校验的归档版本）。 */
    suspend fun rollbackGame(gameId: Long, version: Int? = null): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            rollbackFile(File(File(gamesDir, gameId.toString()), "index.html"), version)
        }
    }

    suspend fun deleteGame(gameId: Long) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                File(gamesDir, gameId.toString()).deleteRecursively()
                val idx = readIndex()
                writeIndex(GameIndex(idx.games.filterNot { it.id == gameId }))
            }
        }
    }

    suspend fun touchPlay(gameId: Long) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
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
    }

    /**
     * 文件可视系统：列出游戏存储文件夹（games/<id>）的一级条目——
     * 子目录在前、其余按名称排序；只读概要信息，不提供打开能力。
     */
    suspend fun listGameFiles(gameId: Long): List<GameFileEntry> = withContext(Dispatchers.IO) {
        val dir = File(gamesDir, gameId.toString())
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles()
            ?.map { f ->
                GameFileEntry(
                    name = f.name,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else 0L,
                    childCount = if (f.isDirectory) f.listFiles()?.size ?: 0 else 0,
                    lastModified = f.lastModified()
                )
            }
            ?.sortedWith(compareByDescending<GameFileEntry> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
    }

    // ---------- 草稿 ----------

    suspend fun saveDraft(html: String, session: List<ChatMessage>) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                versionedWrite(File(draftsDir, "latest.html"), html)
                File(draftsDir, "session.json").writeText(json.encodeToString(session), Charsets.UTF_8)
            }
        }
    }

    suspend fun saveDraftSessionOnly(session: List<ChatMessage>) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                File(draftsDir, "session.json").writeText(json.encodeToString(session), Charsets.UTF_8)
            }
        }
    }

    suspend fun saveDraftAgentState(stateJson: String) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                File(draftsDir, "agent_state.json").writeText(stateJson, Charsets.UTF_8)
            }
        }
    }

    suspend fun loadDraftHtml(): String? = withContext(Dispatchers.IO) {
        val f = File(draftsDir, "latest.html")
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    suspend fun loadDraftSession(): List<ChatMessage> = withContext(Dispatchers.IO) {
        decodeOrEmpty(File(draftsDir, "session.json"))
    }

    suspend fun loadDraftAgentState(): String? = withContext(Dispatchers.IO) {
        val f = File(draftsDir, "agent_state.json")
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    /** 回滚草稿到指定版本（默认上一个归档版本）。 */
    suspend fun rollbackDraft(version: Int? = null): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            rollbackFile(File(draftsDir, "latest.html"), version)
        }
    }

    suspend fun hasDraft(): Boolean = withContext(Dispatchers.IO) {
        File(draftsDir, "latest.html").exists() ||
            File(draftsDir, "session.json").exists() ||
            File(draftsDir, "agent_state.json").exists()
    }

    suspend fun clearDraft() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                File(draftsDir, "latest.html").delete()
                File(draftsDir, "session.json").delete()
                File(draftsDir, "agent_state.json").delete()
                File(draftsDir, ".versions").deleteRecursively()
                File(draftsDir, "current.txt").delete()
            }
        }
    }

    // ---------- 内部 ----------

    /**
     * 版本化写入：旧文件归档到 .versions/<v>-index.html，更新
     * .versions/index.html.version（版本号）和 current.txt（指针），最后落盘 sha256。
     */
    private fun versionedWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val versionsDir = File(file.parentFile, ".versions").apply { mkdirs() }
        val marker = File(versionsDir, "${file.name}.version")
        val currentVersion = marker.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (file.isFile) {
            val previous = File(versionsDir, "$currentVersion-${file.name}")
            previous.writeBytes(file.readBytes())
        }
        val next = currentVersion + 1
        file.writeText(content, Charsets.UTF_8)
        marker.writeText(next.toString(), Charsets.UTF_8)
        File(file.parentFile, "current.txt").writeText(file.name, Charsets.UTF_8)
        File(file.parentFile, "${file.name}.sha256").writeText(sha256(content), Charsets.UTF_8)
    }

    private fun rollbackFile(file: File, version: Int?): Boolean {
        if (!file.isFile) return false
        val versionsDir = File(file.parentFile, ".versions")
        val marker = File(versionsDir, "${file.name}.version")
        val current = marker.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (current <= 1) return false
        val target = (version ?: (current - 1)).coerceIn(1, current - 1)
        val archived = File(versionsDir, "$target-${file.name}")
        if (!archived.isFile) return false
        versionedWrite(file, archived.readText(Charsets.UTF_8))
        return true
    }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
