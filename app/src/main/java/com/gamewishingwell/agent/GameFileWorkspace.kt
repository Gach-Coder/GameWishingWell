package com.gamewishingwell.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
data class WorkspaceFile(
    val path: String,
    val sha256: String,
    val bytes: Int
)

@Serializable
data class FileManifest(
    val root: String = "",
    val files: List<WorkspaceFile> = emptyList()
)

/**
 * tool call 的读写文件沙箱：权限严格限制在该游戏文件夹内（编辑区）。
 * 两区模型（2026-09-07 简化）：整个文件系统只有编辑区与保存区，相互独立、
 * 保存/撤销相互整体覆盖——【不保留任何历史版本归档】（旧 .versions/.version 标记/
 * current.txt 指针/旧版 sha256 均为历史簿记，init 时清理存量）。
 * 写入是"最后写入胜出"的平写：同会话由 [Mutex] 加锁 + 写入前 hash 校验
 * （内存态对比，防并发覆盖陈旧内容），仅此而已。
 */
class GameFileWorkspace(private val rootDir: File) {

    private val mutex = Mutex()

    init {
        rootDir.mkdirs()
        cleanupLegacyVersionArtifacts()
    }

    /**
     * 清理历史版本簿记（归档只写不读，删除即收敛）：任何层级的 .versions 目录
     * （旧实现的嵌套归档与根归档）、current.txt 指针、旧版 *.sha256 文件。
     */
    private fun cleanupLegacyVersionArtifacts() {
        runCatching {
            rootDir.walkTopDown().forEach { f ->
                when {
                    f.isDirectory && f.name == VERSIONS_DIR -> f.deleteRecursively()
                    f.isFile && (f.name == POINTER_FILE || f.name.endsWith(".sha256")) -> f.delete()
                }
            }
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

    /** 首次生成：全量写入；文件已存在时返回 null，强制走行级 patch。 */
    suspend fun writeInitial(relativePath: String, content: String, expectedHash: String? = null): WorkspaceFile? =
        mutex.withLock {
            val file = resolve(relativePath) ?: return@withLock null
            if (file.isFile) return@withLock null
            writeLocked(relativePath, content, expectedHash)
        }

    /**
     * 已有文件的全量替换。工具 writefile 的 overwrite 语义与"模型直接在回复里
     * 给出整份代码"的隐式写入走这里；常规迭代仍应优先字符串精确替换（editfile）。
     */
    suspend fun writeUpdated(relativePath: String, content: String, expectedHash: String? = null): WorkspaceFile? =
        mutex.withLock {
            val file = resolve(relativePath) ?: return@withLock null
            if (!file.isFile) return@withLock null
            writeLocked(relativePath, content, expectedHash)
        }

    suspend fun delete(relativePath: String): Boolean = mutex.withLock {
        resolve(relativePath)?.delete() ?: false
    }

    /**
     * 清空整个工作区（文件与簿记一并移除）：全新游戏从零开始——
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
                    WorkspaceFile(rel, sha256(bytes), bytes.size)
                }
            }
            .toList()
        return FileManifest(root = rootDir.absolutePath, files = files)
    }

    private suspend fun writeLocked(relativePath: String, content: String, expectedHash: String?): WorkspaceFile? {
        val file = resolve(relativePath) ?: return null
        file.parentFile?.mkdirs()

        // hash 校验防并发覆盖（内存态对比当前文件内容，与任何磁盘簿记无关）
        if (file.isFile) {
            val actual = sha256(file.readBytes())
            expectedHash?.let { expected ->
                if (!expected.equals(actual, ignoreCase = true)) return null
            }
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        file.writeText(content, Charsets.UTF_8)
        return WorkspaceFile(relativePath, sha256(bytes), bytes.size)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** 历史簿记文件名（不再写入；保留常量供存量清理与清单过滤）。 */
        const val POINTER_FILE = "current.txt"

        /** 历史版本归档目录名（不再写入；保留常量供存量清理与清单过滤）。 */
        const val VERSIONS_DIR = ".versions"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
