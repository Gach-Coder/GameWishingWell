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
 */
class GameFileWorkspace(private val rootDir: File) {

    private val mutex = Mutex()

    init {
        rootDir.mkdirs()
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
     * 已有文件的更新：默认行级 patch（[fromLine, toLine] 替换）。
     * 全量重写仅允许首次生成；如果调用方确需全量替换已有文件，必须先 delete 文件。
     */
    suspend fun patchLines(
        relativePath: String,
        startLine: Int,
        endLine: Int,
        replacement: String,
        expectedHash: String? = null
    ): WorkspaceFile? = mutex.withLock {
        val file = resolve(relativePath) ?: return@withLock null
        if (!file.isFile) return@withLock null
        expectedHash?.let { expected ->
            if (sha256(file.readBytes()) != expected) return@withLock null
        }
        val lines = file.readText(Charsets.UTF_8).lines().toMutableList()
        val from = startLine.coerceIn(1, lines.size + 1) - 1
        val to = endLine.coerceIn(startLine, lines.size + 1)
        val newLines = replacement.lines()
        val targetSize = from + newLines.size + (lines.size - to)
        // 小文件用 MutableList，兼容 JVM 与 Android。
        while (lines.size < from) lines.add("")
        val tail = lines.drop(to)
        val rebuilt = ArrayList<String>(targetSize.coerceAtLeast(from + newLines.size + tail.size))
        rebuilt.addAll(lines.take(from))
        rebuilt.addAll(newLines)
        rebuilt.addAll(tail)
        val updated = rebuilt.joinToString("\n")
        writeLocked(relativePath, updated, expectedHash)
    }

    suspend fun delete(relativePath: String): Boolean = mutex.withLock {
        val file = resolve(relativePath) ?: return@withLock false
        file.delete()
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
        val versionsDir = File(file.parentFile, ".versions").apply { mkdirs() }
        val currentVersion = if (file.isFile) versionOf(relativePath) else 0
        val nextVersion = currentVersion + 1
        if (file.isFile) {
            // 指针切换：旧文件先入版本库，再写新文件；任何一步失败都不破坏旧指针内容。
            File(versionsDir, "$currentVersion-${file.name}").writeBytes(file.readBytes())
            File(versionsDir, "${file.name}.version").writeText(nextVersion.toString(), Charsets.UTF_8)
        }
        file.writeText(content, Charsets.UTF_8)
        File(versionsDir, "${file.name}.version").writeText(nextVersion.toString(), Charsets.UTF_8)
        writePointer(relativePath)
        return WorkspaceFile(relativePath, nextVersion, sha256(bytes), bytes.size)
    }

    private fun currentPointer(): String = File(rootDir, POINTER_FILE).takeIf { it.isFile }?.readText()?.trim() ?: "index.html"

    private fun writePointer(path: String) {
        File(rootDir, POINTER_FILE).writeText(path, Charsets.UTF_8)
    }

    private fun versionOf(path: String): Int {
        val f = File(rootDir, path)
        val marker = File(f.parentFile, ".versions/${f.name}.version")
        if (marker.isFile) return marker.readText().trim().toIntOrNull() ?: 0
        return 0
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val POINTER_FILE = "current.txt"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
