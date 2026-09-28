package com.gamewishingwell.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 游戏库存储：
 * - 已保存游戏（运行区）：filesDir/games/<id>/index.html + session.json + agent_state.json，索引 filesDir/games.json
 * - 未保存草稿：filesDir/drafts/latest.html + session.json + agent_state.json
 * - 编辑区：filesDir/agent/workspace/<会话>/（GameAgent 的工具沙箱），与运行区相互独立——
 *   编辑会话期间本仓库的游戏文件不被写入，保存按钮（overwriteGameFiles/saveGame）才把
 *   编辑区文件整体覆盖到运行区。
 *
 * 两区模型（2026-09-07 简化）：整个文件系统只有编辑区与保存区，相互独立、
 * 保存/撤销即两区之间的【整体文件覆盖】（编辑区清单 → 保存区全量平写+镜像删除；
 * 保存区清单 → 编辑区全量覆盖+多余清理；Agent 日志与会话簿记豁免）——
 * 不保留任何历史版本归档（旧 .versions/标记/指针/sha256 存量随写入清理）。
 * 同会话写入由 [writeMutex] 加锁串行化。
 */
class GameRepository(private val context: Context) {

    internal companion object {
        /** 游戏入口文件名（工作区与保存区一致）。 */
        const val ENTRY_HTML = "index.html"

        /** 保存点目录名（保存时冻结的上下文快照，撤销锚点）。 */
        const val SAVEPOINT_DIR = ".savepoint"

        /** 版本化归档目录名（与 GameFileWorkspace 的 .versions 约定一致）。 */
        const val VERSIONS_DIR = ".versions"

        /** 入口指针文件名（与 GameFileWorkspace 的 current.txt 约定一致）。 */
        const val POINTER_FILE = "current.txt"

        /**
         * Agent 回合日志目录名（games/<id>/log 或 drafts/log）：只能由回合入口
         * （AgentLog.beginTurn）创建写入，只随游戏/草稿删除顺带删除；
         * 保存、撤销、运行合并等一切"游戏文件"链路一律排除该目录。
         */
        const val LOG_DIR = "log"

        /** 文件可视系统的草稿键位：gameId 传此值时浏览/读取草稿文件夹（drafts/）。 */
        const val DRAFT_ID = -1L

        /** 文件可视系统只读预览的字节上限（超出截断展示，避免超大归档拖垮渲染）。 */
        const val FILE_PREVIEW_LIMIT_BYTES = 256 * 1024

        /** logcat 标签与瞬态重试间隔（文件可视系统读取失败的现场诊断）。 */
        const val LOG_TAG = "GameRepository"
        const val READ_RETRY_MS = 250L

        /** 游戏包清单文件名（zip 根）：导出侧写入、导入侧读取标题/描述（见 [GamePackageManifest]）。 */
        const val PACKAGE_MANIFEST = "ww_game.json"

        /** 导入游戏包的解包总量上限：游戏文件夹全为文本，超限即判包异常，防超大 zip 拖垮内存。 */
        const val IMPORT_SIZE_LIMIT_BYTES = 256L * 1024 * 1024

        /** 会话簿记文件名（games/<id> 与 .savepoint 内同构；GameAgent 装载/落盘同一约定）。 */
        const val SESSION_FILE = "session.json"

        /** Agent 会话状态文件名（GameSession 的 JSON 序列化；GameAgent 落盘同一约定）。 */
        const val AGENT_STATE_FILE = "agent_state.json"

        /**
         * 导出包内【编辑区】条目的保留前缀（.ww-workspace/<相对路径>）：编辑区
         * agent/workspace/game-<id> 里可能有未保存到保存区的修改（unsavedVersion），
         * 属于该对话现场的一部分——不随包导出则导入后"会话与状态描述新版、文件是旧版"。
         * 前缀以 "." 开头，与模型生成的游戏文件路径（index.html/js/css）天然隔离。
         */
        const val WORKSPACE_PREFIX = ".ww-workspace"

        /** 编辑区根目录名（agent/workspace；game-<id> 子目录为各会话工作区）。 */
        private const val WORKSPACE_ROOT = "agent/workspace"

        /** 首轮对话环境的会话簿记初值：空消息列表（GameAgent 首次落盘前的装载兜底同义）。 */
        const val EMPTY_SESSION_JSON = "[]"

        /** 首轮对话环境的 Agent 状态初值：GameSession 全字段默认值的 JSON 形态。 */
        const val EMPTY_AGENT_STATE_JSON = "{}"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val writeMutex = Mutex()

    private val gamesDir: File by lazy { File(context.filesDir, "games").apply { mkdirs() } }
    private val draftsDir: File by lazy { File(context.filesDir, "drafts").apply { mkdirs() } }
    private val indexFile: File by lazy { File(context.filesDir, "games.json") }

    init {
        // 一次性迁移（幂等）：清理旧版本应用遗留的版本簿记——两区模型不再产生
        // .versions 归档/current.txt 指针/旧版 sha256；归档只写不读，删除即收敛。
        runCatching {
            val roots = mutableListOf(gamesDir, draftsDir)
            gamesDir.listFiles()?.filter { it.isDirectory }?.let { roots += it }
            roots.forEach { dir ->
                dir.walkTopDown().forEach { f ->
                    when {
                        f.isDirectory && f.name == VERSIONS_DIR -> f.deleteRecursively()
                        f.isFile && (f.name == POINTER_FILE || f.name.endsWith(".sha256")) -> f.delete()
                    }
                }
            }
        }
    }

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

    /**
     * 游戏文件落盘（两区模型·保存区镜像写入）：编辑区清单【整体】覆盖保存区——
     * - 清单内文件全部平写（子目录自动创建；HTML/CSS/JS 一视同仁，无任何版本化）；
     * - 镜像删除：保存区内不在清单中的游戏文件一并移除（模型删掉 js/old.js 后保存，
     *   保存区的 js/old.js 同步消失——整体覆盖的完整语义；会话簿记/保存点/日志豁免）；
     * - 存量版本簿记清理：旧版本应用的 .versions 归档、current.txt 指针、*.sha256
     *   随每次保存删除（归档只写不读，删除即收敛）。
     */
    private fun writeGameFilesLocked(dir: File, files: Map<String, String>) {
        files.forEach { (name, content) ->
            if (name == POINTER_FILE || name.contains("/$VERSIONS_DIR/") || name.startsWith("$VERSIONS_DIR/") ||
                name.startsWith("$LOG_DIR/") || name.contains("/$LOG_DIR/")
            ) {
                return@forEach
            }
            val f = File(dir, name)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }
        cleanupLegacyVersionArtifactsLocked(dir)
        mirrorDeleteLocked(dir, files)
    }

    /** 镜像删除：移除保存区内不在编辑区清单中的游戏文件（簿记/保存点/日志豁免）。 */
    private fun mirrorDeleteLocked(dir: File, files: Map<String, String>) {
        runCatching {
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { f ->
                    val rel = f.relativeTo(dir).invariantSeparatorsPath
                    val exempt = rel == "session.json" || rel == "agent_state.json" ||
                        rel.startsWith("$SAVEPOINT_DIR/") || rel.startsWith("$LOG_DIR/")
                    if (!exempt && rel !in files) f.delete()
                }
            // 清空后的孤儿目录（如 js/ 被整个删光）一并移除
            dir.walkTopDown().filter { it.isDirectory && it != dir }.forEach { d ->
                if (d.listFiles().isNullOrEmpty()) d.delete()
            }
        }
    }

    /** 清理历史版本簿记（旧版本应用遗留；不再产生新簿记）。 */
    private fun cleanupLegacyVersionArtifactsLocked(dir: File) {
        runCatching {
            dir.walkTopDown().forEach { f ->
                when {
                    f.isDirectory && f.name == VERSIONS_DIR -> f.deleteRecursively()
                    f.isFile && (f.name == POINTER_FILE || f.name.endsWith(".sha256")) -> f.delete()
                }
            }
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

    /**
     * 读取保存区的全部游戏文本文件（多文件运行副本的合并素材）：排除平台簿记
     * （.versions 归档、current.txt 指针、.sha256 校验）与会话上下文
     * （session.json / agent_state.json / .savepoint）。键为文件相对路径，
     * 仅用于按名取内容——不存在的引用取不到值（安全：不做路径拼接）。
     */
    suspend fun loadGameTextFiles(gameId: Long): Map<String, String> = withContext(Dispatchers.IO) {
        val dir = File(gamesDir, gameId.toString())
        if (!dir.isDirectory) return@withContext emptyMap()
        dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .filter { rel ->
                rel != "session.json" && rel != "agent_state.json" &&
                    rel != POINTER_FILE && !rel.endsWith(".sha256") &&
                    !rel.startsWith("$SAVEPOINT_DIR/") && !rel.startsWith("$VERSIONS_DIR/") &&
                    !rel.startsWith("$LOG_DIR/")
            }
            .associateWith { rel -> File(dir, rel).readText(Charsets.UTF_8) }
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

    suspend fun deleteGame(gameId: Long) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                File(gamesDir, gameId.toString()).deleteRecursively()
                val idx = readIndex()
                writeIndex(GameIndex(idx.games.filterNot { it.id == gameId }))
            }
        }
    }

    // ---------- 游戏包导出 / 导入（zip 互导自洽） ----------

    /**
     * 游戏卡菜单「导出zip」：把该对话的全部文件打包写入 [out]（SAF 目标 Uri 的输出流）。
     * - 保存区 games/<id> 文件夹【整体】：index.html / js/css 多文件 / session.json /
     *   agent_state.json / .savepoint / log 全量；
     * - 编辑区 agent/workspace/game-<id>（若存在）：未保存的修改以 [WORKSPACE_PREFIX]/ 前缀
     *   随包导出——保证基于导出的导入能复原完整现场（unsavedVersion 的会话其编辑区比保存区新，
     *   缺了它导入后会话描述与实际文件撕裂）；
     * - 根另附 [PACKAGE_MANIFEST] 清单（标题/描述来自索引）。
     * 写入失败或游戏不存在返回 false。
     */
    suspend fun exportGameZip(gameId: Long, out: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(gamesDir, gameId.toString())
                if (!dir.isDirectory) return@runCatching false
                val meta = readIndex().games.firstOrNull { it.id == gameId }
                ZipOutputStream(BufferedOutputStream(out)).use { zos ->
                    // 清单置于首位：导入侧首读即得元数据
                    zos.putNextEntry(ZipEntry(PACKAGE_MANIFEST))
                    zos.write(
                        json.encodeToString(
                            GamePackageManifest(title = meta?.title.orEmpty(), description = meta?.description.orEmpty())
                        ).toByteArray(Charsets.UTF_8)
                    )
                    zos.closeEntry()
                    dir.walkTopDown()
                        .filter { it.isFile }
                        .forEach { f ->
                            zos.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    // 编辑区（工作区）文件：条目名加保留前缀，与保存区条目同包不同命名空间
                    val wsDir = editingWorkspaceDir(gameId)
                    if (wsDir.isDirectory) {
                        wsDir.walkTopDown()
                            .filter { it.isFile }
                            .forEach { f ->
                                zos.putNextEntry(
                                    ZipEntry("$WORKSPACE_PREFIX/" + f.relativeTo(wsDir).invariantSeparatorsPath)
                                )
                                f.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                    }
                }
                true
            }.onFailure { Log.e(LOG_TAG, "exportGameZip 失败: gameId=$gameId", it) }
                .getOrDefault(false)
        }

    /**
     * 导入游戏包：读取 [input] 的 zip（与 [exportGameZip] 同构，容错外部打包工具的单一
     * 顶层目录前缀），解包到新 games/<新id> 并注册索引，返回新游戏元数据——新 id 即全新
     * 会话身份，"继续编辑"按现有装载链路从保存区播种工作区，天然自洽。
     * 结构非法（缺 index.html / 路径逃逸 / 超大小上限 / 空 zip）返回 null。
     *
     * 完整性检测与缺失初始化（对话现场复原契约）：
     * - 检测：session.json / agent_state.json（含 .savepoint 内同构成员）必须可解析为对应
     *   结构，损坏（导出时撕裂写入/外部手改）一律按缺失处理并 Log 记录，不静默降级；
     * - 初始化：缺失的簿记按【首轮对话环境】落盘补齐——session.json=[]（空消息列表）、
     *   agent_state.json={}（默认会话状态，GameSession 全字段有默认值）——语义与
     *   GameAgent.decodeSession(null) 的兜底一致，但直接初始化成完整文件环境；
     * - 保存点补全：.savepoint 缺失时以导入（或初始化）后的会话与状态合成——导入=入库
     *   语义，入库即冻结撤销锚点（saveCurrentGame 首次入库同款），撤销不再走
     *   "旧版本游戏无保存点"的清空对话路径；
     * - 编辑区落位：[WORKSPACE_PREFIX]/ 前缀条目解包到 agent/workspace/game-<新id>/，
     *   未保存的修改随包复原（同包校验规则：拒绝逃逸，路径已过 readZipEntries 防线）。
     */
    suspend fun importGameZip(input: InputStream): GameMeta? = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            runCatching {
                val files = readZipEntries(input) ?: return@runCatching null
                val manifest = files[PACKAGE_MANIFEST]
                    ?.let { runCatching { json.decodeFromString<GamePackageManifest>(it.toString(Charsets.UTF_8)) }.getOrNull() }
                // 清单随包只读消费，不落进游戏文件夹（否则会混入游戏文件链路——撤销清单/运行合并素材）
                // 编辑区条目分流（.ww-workspace/ 前缀）：不入游戏文件夹，稍后单独落位
                val wsPrefix = "$WORKSPACE_PREFIX/"
                val workspaceFiles = files.filterKeys { it.startsWith(wsPrefix) }
                    .mapKeys { (rel, _) -> rel.removePrefix(wsPrefix) }
                    .filterKeys { it.isNotEmpty() }
                // 完整性检测：损坏的会话簿记按缺失处理（初始化阶段补齐）
                val gameFiles = dropCorruptBookkeeping(
                    files.filterKeys { it != PACKAGE_MANIFEST && !it.startsWith(wsPrefix) && it != WORKSPACE_PREFIX }
                )
                if (gameFiles[ENTRY_HTML] == null) return@runCatching null
                var id = System.currentTimeMillis()
                while (File(gamesDir, id.toString()).exists()) id++
                val dir = File(gamesDir, id.toString()).apply { mkdirs() }
                gameFiles.forEach { (rel, bytes) ->
                    val target = File(dir, rel)
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
                // 缺失簿记按首轮对话环境初始化（空消息列表 + 默认会话状态）
                val sessionFile = File(dir, SESSION_FILE)
                if (!sessionFile.isFile) sessionFile.writeText(EMPTY_SESSION_JSON, Charsets.UTF_8)
                val stateFile = File(dir, AGENT_STATE_FILE)
                if (!stateFile.isFile) stateFile.writeText(EMPTY_AGENT_STATE_JSON, Charsets.UTF_8)
                // 保存点补全：导入=入库，入库即冻结撤销锚点（与 saveCurrentGame 首次入库同款语义）
                val spSession = File(File(dir, SAVEPOINT_DIR), SESSION_FILE)
                val spState = File(File(dir, SAVEPOINT_DIR), AGENT_STATE_FILE)
                if (!spSession.isFile || !spState.isFile) {
                    File(dir, SAVEPOINT_DIR).mkdirs()
                    if (!spSession.isFile) spSession.writeText(sessionFile.readText(Charsets.UTF_8))
                    if (!spState.isFile) spState.writeText(stateFile.readText(Charsets.UTF_8))
                }
                // 编辑区落位：未保存的修改复原到新会话的工作区
                if (workspaceFiles.isNotEmpty()) {
                    val wsDir = editingWorkspaceDir(id)
                    workspaceFiles.forEach { (rel, bytes) ->
                        val target = File(wsDir, rel)
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                    }
                }
                val meta = GameMeta(
                    id = id,
                    title = manifest?.title?.trim()?.takeIf { it.isNotEmpty() } ?: "导入的游戏",
                    description = manifest?.description.orEmpty(),
                    createdAt = id,
                    updatedAt = id
                )
                writeIndex(GameIndex(readIndex().games + meta))
                meta
            }.onFailure { Log.e(LOG_TAG, "importGameZip 失败", it) }
                .getOrNull()
        }
    }

    /**
     * 完整性检测：剔除损坏的会话簿记（session.json 必须可解析为消息列表、agent_state.json
     * 必须可解析为 JSON 对象——含 .savepoint 内同构成员）。剔除即"按缺失处理"，
     * 由导入流程按首轮对话环境初始化补齐；损坏事实 Log.w 留痕（撕裂写入/外部手改诊断）。
     */
    private fun dropCorruptBookkeeping(files: Map<String, ByteArray>): Map<String, ByteArray> {
        var result = files
        listOf(
            SESSION_FILE to { b: ByteArray -> isSessionBytes(b) },
            AGENT_STATE_FILE to { b: ByteArray -> isAgentStateBytes(b) },
            "$SAVEPOINT_DIR/$SESSION_FILE" to { b: ByteArray -> isSessionBytes(b) },
            "$SAVEPOINT_DIR/$AGENT_STATE_FILE" to { b: ByteArray -> isAgentStateBytes(b) }
        ).forEach { (name, valid) ->
            val bytes = result[name]
            if (bytes != null && !valid(bytes)) {
                Log.w(LOG_TAG, "导入检测到损坏的会话文件，按缺失初始化: $name")
                result = result - name
            }
        }
        return result
    }

    /** session.json 完整性：可解析为 ChatMessage 列表（[] 亦合法——首轮空对话即此形态）。 */
    private fun isSessionBytes(bytes: ByteArray): Boolean =
        runCatching { json.decodeFromString<List<ChatMessage>>(bytes.toString(Charsets.UTF_8)) }.isSuccess

    /** agent_state.json 完整性：可解析为 JSON 对象（GameSession 全字段默认值，{} 合法）。 */
    private fun isAgentStateBytes(bytes: ByteArray): Boolean =
        runCatching { json.decodeFromString<JsonObject>(bytes.toString(Charsets.UTF_8)) }.isSuccess

    /** 编辑区目录（agent/workspace/game-<id>；GameAgent.editingWorkspaceDir 委托此处，单一来源）。 */
    internal fun editingWorkspaceDir(gameId: Long): File =
        File(File(context.filesDir, WORKSPACE_ROOT), "game-$gameId")

    /**
     * 解包 zip 为「相对路径 → 字节」映射（导入侧私有，防御性解析）：
     * - 名称规范化为 '/' 分隔；绝对路径或含 ".." 段的条目直接判包非法（防解包逃逸）；
     * - 剥去可选的【单一顶层目录】前缀——外部工具按"打包文件夹"方式产出
     *   "123/index.html" 形态；我们自己的导出顶层即根文件（index.html 等），不受影响；
     * - 过滤旧版版本簿记（.versions 归档、current.txt 指针、sha256 校验文件，与保存链路同口径）；
     * - 解包总量超过 [IMPORT_SIZE_LIMIT_BYTES] 判非法。任一防御失败返回 null。
     */
    private fun readZipEntries(input: InputStream): Map<String, ByteArray>? {
        val raw = mutableListOf<Pair<String, ByteArray>>()
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            while (true) {
                val ze = zis.nextEntry ?: break
                if (ze.isDirectory) continue
                val name = ze.name.replace('\\', '/').removePrefix("./")
                if (name.isEmpty() || name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() }) {
                    return null
                }
                val bytes = zis.readBytes()
                total += bytes.size
                if (total > IMPORT_SIZE_LIMIT_BYTES) return null
                raw += name to bytes
            }
        }
        if (raw.isEmpty()) return null
        val prefix = raw.first().first.substringBefore('/', "")
        val hasCommonTopDir = prefix.isNotEmpty() && raw.all { (p, _) ->
            p.length > prefix.length && p[prefix.length] == '/' && p.substringBefore('/', "") == prefix
        }
        val entries = if (hasCommonTopDir) raw.map { (p, b) -> p.removePrefix("$prefix/") to b } else raw
        return entries
            .filterNot { (rel, _) -> isLegacyArtifact(rel) }
            .toMap()
    }

    /** 旧版版本簿记判定（导入过滤用）：.versions 目录、current.txt 指针、*.sha256 校验。 */
    private fun isLegacyArtifact(rel: String): Boolean =
        rel.split('/').any { it == VERSIONS_DIR || it == POINTER_FILE } || rel.endsWith(".sha256")

    /**
     * 文件可视系统：列出游戏存储文件夹（games/<id>）内 [relativePath] 子目录的一级条目
     * （空串 = 根目录）——子目录在前、其余按名称排序；路径经规范化校验不得逃出游戏目录。
     */
    /**
     * 文件可视系统：列出游戏存储文件夹（games/<id>；gameId=[DRAFT_ID] 时为草稿文件夹
     * drafts/）内 [relativePath] 子目录的一级条目（空串 = 根目录）——子目录在前、
     * 其余按名称排序；路径经规范化校验不得逃出游戏目录。
     */
    suspend fun listGameFiles(gameId: Long, relativePath: String = ""): List<GameFileEntry> =
        withContext(Dispatchers.IO) {
            val dir = resolveInGameDir(gameId, relativePath) ?: return@withContext emptyList()
            if (!dir.isDirectory) return@withContext emptyList()
            runCatching {
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
            }.getOrDefault(emptyList())
        }

    /**
     * 文件可视系统：只读打开游戏目录内一个文本文件（查看与复制用，无任何写入路径）。
     * 目标是目录、文件不存在或路径逃出游戏目录（../ 等）时抛 [FileNotFoundException]
     * （携带现场上下文：根目录、目标判定、同级条目——真机排查"全部文件无法打开"用）；
     * 内容超过 [FILE_PREVIEW_LIMIT_BYTES] 截断预览；含 NUL 字节判为二进制不做文本展示。
     *
     * 瞬态加固：IO 异常（并发写入/文件重建的瞬时窗口）延迟 250ms 重试一次；
     * 最终失败 Log.e（logcat tag=GameRepository）。
     */
    suspend fun readGameFile(gameId: Long, relativePath: String): GameFileContent =
        withContext(Dispatchers.IO) {
            runCatching { readGameFileOnce(gameId, relativePath) }
                .recoverCatching {
                    delay(READ_RETRY_MS)
                    readGameFileOnce(gameId, relativePath)
                }
                .onFailure {
                    Log.e(LOG_TAG, "readGameFile 失败: gameId=$gameId path=$relativePath", it)
                }
                .getOrThrow()
        }

    private fun readGameFileOnce(gameId: Long, relativePath: String): GameFileContent {
        val root = rootDirFor(gameId)
        val file = resolveInGameDir(gameId, relativePath)
            ?: throw FileNotFoundException(
                "路径不可读（不存在/逃逸/根目录缺失）: path=$relativePath, root=${root.absolutePath}, " +
                    "rootIsDir=${root.isDirectory}, siblings=${root.listFiles()?.size ?: -1}"
            )
        if (!file.isFile) {
            throw FileNotFoundException(
                "目标不是文件: path=$relativePath, exists=${file.exists()}, isDir=${file.isDirectory}, " +
                    "canRead=${file.canRead()}, root=${root.absolutePath}, siblings=${root.listFiles()?.size ?: -1}"
            )
        }
        val size = file.length()
        val truncated = size > FILE_PREVIEW_LIMIT_BYTES
        // 有界读取（minSdk 26 兼容）：readNBytes(Int) 是 Java 11 API，Android API 33-
        // 真机直接 NoSuchMethodError（实测：低版本手机全部文件"无法打开"的根因）。
        // 用 read(byte[], off, len) 循环复制，语义与 readNBytes(limit) 等价：至多 limit 字节。
        val bytes = file.inputStream().use { ins ->
            val out = java.io.ByteArrayOutputStream(minOf(size, FILE_PREVIEW_LIMIT_BYTES.toLong()).toInt().coerceAtLeast(0))
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (total < FILE_PREVIEW_LIMIT_BYTES) {
                val n = ins.read(buf, 0, minOf(buf.size, FILE_PREVIEW_LIMIT_BYTES - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toByteArray()
        }
        val binary = bytes.indexOf(0.toByte()) >= 0
        // 截断点可能切在多字节序列中间，解码尾部至多出现一个替换符，展示前去掉
        val text = if (binary) "" else String(bytes, Charsets.UTF_8).let { if (truncated) it.trimEnd('\uFFFD') else it }
        return GameFileContent(
            name = file.name,
            relativePath = relativePath,
            sizeBytes = size,
            lastModified = file.lastModified(),
            text = text,
            truncated = truncated,
            binary = binary
        )
    }

    /**
     * 文件可视系统「导出文件」的源数据：完整文件字节（不截断、二进制原样），
     * 供系统 SAF 另存到用户选择的位置；目标消失或路径非法返回 null。
     */
    suspend fun readGameFileBytes(gameId: Long, relativePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = resolveInGameDir(gameId, relativePath) ?: return@runCatching null
                if (!file.isFile) null else file.readBytes()
            }.onFailure {
                Log.e(LOG_TAG, "readGameFileBytes 失败: gameId=$gameId path=$relativePath", it)
            }.getOrNull()
        }

    /**
     * 保存区（games/<id>）的全部游戏文件相对路径（递归，多文件形态含 js/css 子目录）。
     * 排除平台簿记与会话文件：.versions 归档（任何层级）、current.txt 指针、*.sha256、
     * session.json/agent_state.json、.savepoint 保存点。撤销（保存版→编辑版覆盖）以此为准。
     */
    suspend fun savedGameFilePaths(gameId: Long): List<String> = withContext(Dispatchers.IO) {
        val dir = File(gamesDir, gameId.toString())
        if (!dir.isDirectory) return@withContext emptyList()
        dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .filter { rel ->
                rel != POINTER_FILE && !rel.endsWith(".sha256") &&
                    rel != "session.json" && rel != "agent_state.json" &&
                    !rel.startsWith("$VERSIONS_DIR/") && !rel.contains("/$VERSIONS_DIR/") &&
                    !rel.startsWith("$SAVEPOINT_DIR/") && !rel.contains("/$SAVEPOINT_DIR/") &&
                    !rel.startsWith("$LOG_DIR/") && !rel.contains("/$LOG_DIR/")
            }
            .sorted()
            .toList()
    }

    /** 浏览目标根目录：正 id = games/<id>，[DRAFT_ID] = 草稿文件夹 drafts/。 */
    internal fun rootDirFor(gameId: Long): File =
        if (gameId == DRAFT_ID) draftsDir else File(gamesDir, gameId.toString())

    /** 把游戏目录内相对路径解析为 File：规范化后必须仍位于游戏根目录之下，防 ../ 逃逸。 */
    private fun resolveInGameDir(gameId: Long, relativePath: String): File? = runCatching {
        val dir = rootDirFor(gameId)
        if (!dir.isDirectory) return@runCatching null
        val root = dir.canonicalPath + File.separator
        val target = File(dir, relativePath)
        if (target.canonicalPath == dir.canonicalPath || target.canonicalPath.startsWith(root)) target else null
    }.getOrNull()

    // ---------- 草稿 ----------

    suspend fun saveDraft(html: String, session: List<ChatMessage>) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                File(draftsDir, "latest.html").writeText(html, Charsets.UTF_8)
                File(draftsDir, "session.json").writeText(json.encodeToString(session), Charsets.UTF_8)
                cleanupLegacyVersionArtifactsLocked(draftsDir)
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

    suspend fun hasDraft(): Boolean = withContext(Dispatchers.IO) {
        File(draftsDir, "latest.html").exists() ||
            File(draftsDir, "session.json").exists() ||
            File(draftsDir, "agent_state.json").exists()
    }

    /**
     * 首页"未同步新版本"标记的磁盘来源：冷启动时 GameAgent 尚未实例化（首页不装载
     * 会话），持久化在 agent_state.json 的 unsavedVersion 字段只能直接扫描读取；
     * 正则容忍键值间空白（kotlinx 输出紧凑、手工/其他序列化器可能带空格）。
     */
    private val unsavedFlagRegex = Regex("\"unsavedVersion\"\\s*:\\s*true")

    suspend fun hasDraftUnsaved(): Boolean = withContext(Dispatchers.IO) {
        File(draftsDir, "agent_state.json")
            .takeIf { it.exists() }?.readText(Charsets.UTF_8)
            ?.let { unsavedFlagRegex.containsMatchIn(it) } == true
    }

    suspend fun listUnsavedGameIds(): Set<Long> = withContext(Dispatchers.IO) {
        gamesDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val id = dir.name.toLongOrNull() ?: return@mapNotNull null
                val state = File(dir, "agent_state.json")
                if (state.exists() && unsavedFlagRegex.containsMatchIn(state.readText(Charsets.UTF_8))) id else null
            }
            ?.toSet() ?: emptySet()
    }

    suspend fun clearDraft() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                File(draftsDir, "latest.html").delete()
                File(draftsDir, "latest.html.sha256").delete()
                File(draftsDir, "session.json").delete()
                File(draftsDir, "agent_state.json").delete()
                File(draftsDir, ".versions").deleteRecursively()
                File(draftsDir, "current.txt").delete()
                // 草稿日志随草稿删除顺带删除（日志无独立清理路径）
                File(draftsDir, LOG_DIR).deleteRecursively()
            }
        }
    }

    /**
     * 草稿入库时的回合日志迁移：drafts/log → games/<id>/log。
     * 入库不是删除——日志只能随游戏删除顺带删除，草稿期的历史回合日志应随会话
     * 带进游戏文件夹（否则 clearDraft 会把它们连带删掉，游戏文件夹"找不到 log"）；
     * 目标已存在同名文件（防御，正常不可能）时逐文件加序号合并。
     */
    suspend fun adoptDraftLogs(gameId: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(draftsDir, LOG_DIR)
                if (!src.isDirectory || src.listFiles().isNullOrEmpty()) return@runCatching
                val dst = File(File(gamesDir, gameId.toString()), LOG_DIR)
                if (!dst.exists()) {
                    src.renameTo(dst)
                } else {
                    src.listFiles()?.forEach { f ->
                        var target = File(dst, f.name)
                        var seq = 1
                        while (target.exists()) target = File(dst, "${f.nameWithoutExtension}_${++seq}.log")
                        f.renameTo(target)
                    }
                    src.deleteRecursively()
                }
            }
        }
    }

    // ---------- 内部 ----------

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
