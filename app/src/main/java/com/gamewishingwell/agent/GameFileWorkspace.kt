package com.gamewishingwell.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
data class WorkspaceFile(
    val path: String,
    val version: Int,
    val sha256: String,
    val bytes: Int
)

@Serializable
data class FileManifest(
    val root: String = "",
    val pointer: String = "index.html",
    val files: List<WorkspaceFile> = emptyList()
)

/**
 * tool call 的读写文件沙箱：权限严格限制在该游戏文件夹内。
 * 写入采用版本化文件 + 指针切换；同会话由 [Mutex] 加锁，写入前做文件 hash
 * 校验，避免并发覆盖。
 *
 * 版本归档统一收在工作区根 [.versions] 下（"不得分开保存"）：子目录文件的
 * 相对路径编码进归档名（js/main.js → 1-js__main.js），任何文件都不在自己
 * 所在目录另建 .versions；归档为只写不读的历史簿记（无回读 API），统一后
 * 保存/撤销链路只需排除根 .versions 一处。init 时清理历史散落的嵌套 .versions。
 */
class GameFileWorkspace(private val rootDir: File) {

    private val mutex = Mutex()

    init {
        rootDir.mkdirs()
        cleanupScatteredVersions()
    }

    /** 版本归档名基：相对路径中的 / 编码为 __（归档与 marker 同名基，保证逐文件独立簿记）。 */
    private fun archiveBase(relativePath: String): String = relativePath.trim().replace("/", "__")

    /**
     * 清理历史散落的嵌套 .versions 目录（旧实现按文件所在目录建归档，多文件后
     * 散落在 js/.versions、css/.versions 等）：归档只写不读，删除即收敛，无需迁移。
     */
    private fun cleanupScatteredVersions() {
        runCatching {
            val root = rootDir.canonicalFile
            rootDir.walkTopDown()
                .filter { it.isDirectory && it.name == VERSIONS_DIR && it.parentFile?.canonicalFile != root }
                .forEach { it.deleteRecursively() }
        }
    }

    /** 路径解析：禁止 .. 逃逸、禁止绝对路径。解析失败返回 null。 */
    fun resolve(relativePath: String): File? {
        val normalized = relativePath.trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            return null
        }
        val root = rootDir.canonicalFile
        val target = File(root, normalized).canonicalFile
        return if (target.path == root.path || target.path.startsWith(root.path + File.separator)) target else null
    }

    fun read(relativePath: String): String? {
        val file = resolve(relativePath) ?: return null
        return if (file.isFile) file.readText(Charsets.UTF_8) else null
    }

    fun hash(relativePath: String): String? {
        val file = resolve(relativePath) ?: return null
        return if (file.isFile) sha256(file.readBytes()) else null
    }

    /** 首次生成：全量写入并建立版本 v1；文件已存在时返回 null，强制走行级 patch。 */
    suspend fun writeInitial(relativePath: String, content: String, expectedHash: String? = null): WorkspaceFile? =
        mutex.withLock {
            val file = resolve(relativePath) ?: return@withLock null
            if (file.isFile) return@withLock null
            writeLocked(relativePath, content, expectedHash)
        }

    /**
     * 已有文件的全量替换（版本递增、旧版入版本库）。工具 writefile 的 overwrite
     * 语义与“模型直接在回复里给出整份代码”的隐式写入走这里；常规迭代仍应优先
     * 字符串精确替换（editfile），避免全量重写的回归与 token 开销。
     */
    suspend fun writeUpdated(relativePath: String, content: String, expectedHash: String? = null): WorkspaceFile? =
        mutex.withLock {
            val file = resolve(relativePath) ?: return@withLock null
            if (!file.isFile) return@withLock null
            writeLocked(relativePath, content, expectedHash)
        }

    suspend fun delete(relativePath: String): Boolean = mutex.withLock {
        val file = resolve(relativePath) ?: return@withLock false
        val deleted = file.delete()
        if (deleted) {
            // 版本簿记随文件一并清理：残留的旧 marker 会让"删除→重建"从旧版本号
            // 续数，新归档按 v1 覆盖上一款游戏的历史归档（跨游戏版本串台）；
            // 归档文件同步移除，避免孤儿堆积。归档统一在根 .versions 下。
            val base = archiveBase(relativePath)
            val versionsDir = File(rootDir, VERSIONS_DIR)
            File(versionsDir, "$base.version").delete()
            versionsDir.listFiles()?.forEach { archived ->
                if (archived.name.endsWith("-$base")) archived.delete()
            }
        }
        deleted
    }

    /**
     * 清空整个工作区（文件与版本簿记一并移除）：全新游戏从零开始——
     * 多文件形态下旧游戏的辅助文件（js/css/scenarios）若不清理，
     * 会被新会话的保存/交付链路误当作本游戏的文件带走（孤儿残留）。
     */
    suspend fun reset() = mutex.withLock {
        rootDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun manifest(): FileManifest {
        val files = rootDir.walkTopDown()
            .filter { it.isFile && it.name != POINTER_FILE && ".versions" !in it.path }
            .mapNotNull { file ->
                val rel = file.relativeTo(rootDir).invariantSeparatorsPath
                if (rel == POINTER_FILE || ".versions" in rel) null else {
                    val bytes = file.readBytes()
                    WorkspaceFile(rel, versionOf(rel), sha256(bytes), bytes.size)
                }
            }
            .toList()
        return FileManifest(root = rootDir.absolutePath, pointer = currentPointer(), files = files)
    }

    private suspend fun writeLocked(relativePath: String, content: String, expectedHash: String?): WorkspaceFile? {
        val file = resolve(relativePath) ?: return null
        file.parentFile?.mkdirs()

        // hash 校验防并发覆盖
        if (file.isFile) {
            val actual = sha256(file.readBytes())
            expectedHash?.let { expected ->
                if (!expected.equals(actual, ignoreCase = true)) return null
            }
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        // 版本归档统一在根 .versions（嵌套路径编码进归档名），不在文件所在目录另建
        val base = archiveBase(relativePath)
        val versionsDir = File(rootDir, VERSIONS_DIR).apply { mkdirs() }
        val currentVersion = if (file.isFile) versionOf(relativePath) else 0
        val nextVersion = currentVersion + 1
        if (file.isFile) {
            // 指针切换：旧文件先入版本库，再写新文件；任何一步失败都不破坏旧指针内容。
            File(versionsDir, "$currentVersion-$base").writeBytes(file.readBytes())
        }
        file.writeText(content, Charsets.UTF_8)
        File(versionsDir, "$base.version").writeText(nextVersion.toString(), Charsets.UTF_8)
        pruneVersions(versionsDir, base, nextVersion)
        // 入口指针只随入口文件切换：写 scenarios.json 等辅助文件不得把"当前入口"
        // 指偏（listfiles 观察与回滚语义都以入口为准）。
        if (relativePath == GameFileWorkspaceEntryPoint.DEFAULT) {
            writePointer(relativePath)
        }
        return WorkspaceFile(relativePath, nextVersion, sha256(bytes), bytes.size)
    }

    /**
     * 归档修剪：每个文件只保留最近 [KEEP_VERSIONS] 个历史版本（即 [v-KEEP, v-1]，
     * 当前文件本身不计入归档）。长修复轮上百次写入 × 每次归档旧全文（几十至几百
     * KB），不修剪会让 .versions 无限膨胀。按归档名基（编码后的相对路径）逐文件
     * 独立修剪，多文件互不误删。
     */
    private fun pruneVersions(versionsDir: File, archiveBase: String, currentVersion: Int) {
        if (currentVersion <= KEEP_VERSIONS) return
        val minKeep = currentVersion - KEEP_VERSIONS
        versionsDir.listFiles()?.forEach { archived ->
            val prefix = archived.name.substringBefore('-', "")
            val v = prefix.toIntOrNull() ?: return@forEach
            if (v < minKeep && archived.name.endsWith("-$archiveBase")) archived.delete()
        }
    }

    private fun currentPointer(): String = File(rootDir, POINTER_FILE).takeIf { it.isFile }?.readText()?.trim() ?: "index.html"

    private fun writePointer(path: String) {
        File(rootDir, POINTER_FILE).writeText(path, Charsets.UTF_8)
    }

    private fun versionOf(path: String): Int {
        val marker = File(File(rootDir, VERSIONS_DIR), "${archiveBase(path)}.version")
        if (marker.isFile) return marker.readText().trim().toIntOrNull() ?: 0
        return 0
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val POINTER_FILE = "current.txt"

        /** 版本归档目录（统一在工作区根，任何文件不再按所在目录分散保存）。 */
        const val VERSIONS_DIR = ".versions"

        /** 每个文件保留的历史版本数（超出部分在写入时修剪）。 */
        const val KEEP_VERSIONS = 20

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
