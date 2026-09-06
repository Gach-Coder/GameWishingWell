package com.gamewishingwell.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class GameRepositoryTest {

    private fun repo(dir: File): GameRepository {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        return GameRepository(context)
    }

    @Test
    fun `游戏库保存重命名与版本化`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>", "scenarios.json" to "{\"scenarios\":[]}"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>", "scenarios.json" to "{\"scenarios\":[]}"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            assertTrue(File(File(dir, "games/${meta.id}"), ".versions/1-index.html").exists())
            assertEquals("<html>v2</html>", repo.loadGameHtml(meta.id))
            // 保存按钮整体覆盖：非入口文件也随编辑区落盘
            assertTrue(repo.loadGameFile(meta.id, "scenarios.json")?.contains("scenarios") == true)

            assertTrue(repo.renameGame(meta.id, "新名字"))
            assertEquals("新名字", repo.listGames().first { it.id == meta.id }.title)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `保存区归档滚动修剪只保留最近版本`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-prune-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v0</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val total = GameRepository.KEEP_VERSIONS + 5
            for (v in 1 until total) {
                repo.overwriteGameFiles(
                    meta.id,
                    mapOf("index.html" to "<html>v$v</html>"),
                    listOf(ChatMessage("user", "做一个游戏"))
                )
            }
            val archives = File(File(dir, "games/${meta.id}"), ".versions").listFiles().orEmpty()
                .filter { it.name.endsWith("-index.html") }
                .mapNotNull { it.name.substringBefore('-').toIntOrNull() }
            assertEquals(GameRepository.KEEP_VERSIONS, archives.size)
            assertEquals(total - 1, archives.max())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `保存点快照冻结保存时上下文供撤销恢复`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "m0"))
            )
            // 保存时冻结上下文快照
            repo.writeSavepoint(
                meta.id,
                listOf(ChatMessage("user", "m0"), ChatMessage("assistant", "a0")),
                """{"gameGenerated":true}"""
            )
            assertEquals(2, repo.loadSavepointSession(meta.id)?.size)
            assertEquals("""{"gameGenerated":true}""", repo.loadSavepointAgentState(meta.id))
            // 保存区实时会话继续前进，保存点不受影响（撤销锚点独立于实时副本）
            repo.updateGameSessionOnly(
                meta.id,
                listOf(ChatMessage("user", "m0"), ChatMessage("user", "m1"), ChatMessage("assistant", "a1"))
            )
            assertEquals(2, repo.loadSavepointSession(meta.id)?.size)
            // 不存在的游戏/尚未保存过的游戏返回 null
            assertNull(repo.loadSavepointSession(99999L))
            assertNull(repo.loadSavepointAgentState(99999L))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统列出游戏文件夹一级条目`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )

            val entries = repo.listGameFiles(meta.id)
            val names = entries.map { it.name }
            // 版本化写入产出的既有文件全部可见
            assertTrue("index.html" in names)
            assertTrue("session.json" in names)
            assertTrue(".versions" in names)
            val html = entries.first { it.name == "index.html" }
            assertTrue(!html.isDirectory && html.sizeBytes > 0 && html.lastModified > 0)
            // 目录排前，且携带子项计数
            val versions = entries.first { it.isDirectory }
            assertEquals(".versions", versions.name)
            assertTrue(versions.childCount >= 1)
            assertTrue(entries.first().isDirectory)
            // 不存在的游戏返回空
            assertTrue(repo.listGameFiles(99999L).isEmpty())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统进入子目录浏览`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-sub-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // 二次写入产生归档，.versions 内才有 N-index.html
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // .versions 子目录可进入，看到归档条目
            val sub = repo.listGameFiles(meta.id, ".versions")
            assertTrue(sub.isNotEmpty())
            assertTrue(sub.all { !it.isDirectory })
            assertTrue(sub.any { it.name.endsWith("-index.html") })
            // 不存在的子目录返回空；目标是文件也返回空
            assertTrue(repo.listGameFiles(meta.id, "no-such-dir").isEmpty())
            assertTrue(repo.listGameFiles(meta.id, "index.html").isEmpty())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统只读打开文件`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-open-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // 二次写入产生归档，.versions 内才有 N-index.html
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // 根目录文件（overwrite 后入口内容为 v2）
            val html = repo.readGameFile(meta.id, "index.html")!!
            assertEquals("index.html", html.name)
            assertEquals("index.html", html.relativePath)
            assertEquals("<html>v2</html>", html.text)
            assertTrue(!html.binary && !html.truncated)
            // 子目录文件按相对路径读取
            val archived = repo.listGameFiles(meta.id, ".versions").first { it.name.endsWith("-index.html") }
            val archivedContent = repo.readGameFile(meta.id, ".versions/${archived.name}")!!
            assertEquals(".versions/${archived.name}", archivedContent.relativePath)
            assertTrue(archivedContent.text.contains("<html>"))
            // 目录不是文件、不存在路径、不存在的游戏都返回 null
            assertNull(repo.readGameFile(meta.id, ".versions"))
            assertNull(repo.readGameFile(meta.id, "no-such.json"))
            assertNull(repo.readGameFile(99999L, "index.html"))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统拒绝路径逃逸`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-escape-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // ../ 逃出游戏目录一律拒绝（含多层；目录列表同样拒绝）
            assertNull(repo.readGameFile(meta.id, "../games.json"))
            assertNull(repo.readGameFile(meta.id, "../../games.json"))
            assertTrue(repo.listGameFiles(meta.id, "..").isEmpty())
            assertTrue(repo.listGameFiles(meta.id, "../../games").isEmpty())
            // 规范化后仍在游戏目录内的路径放行（canonical 解析兜住 ../ 往返）
            assertNotNull(repo.readGameFile(meta.id, "../../games/${meta.id}/index.html"))
            // 空相对路径 = 根目录本身，用于列表
            assertTrue(repo.listGameFiles(meta.id, "").isNotEmpty())
            assertNull(repo.readGameFile(meta.id, ""))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统超大文件截断与二进制判定`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-cap-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val gameDir = File(dir, "games/${meta.id}")
            // 超过预览上限：只给前缀，sizeBytes 记真实大小
            val big = "x".repeat(GameRepository.FILE_PREVIEW_LIMIT_BYTES + 100)
            File(gameDir, "big.txt").writeText(big)
            val capped = repo.readGameFile(meta.id, "big.txt")!!
            assertTrue(capped.truncated)
            assertEquals(big.length.toLong(), capped.sizeBytes)
            assertTrue(capped.text.length < big.length)

            // 含 NUL 字节判为二进制：不做文本预览
            File(gameDir, "blob.bin").writeBytes(byteArrayOf(1, 2, 0, 3))
            val bin = repo.readGameFile(meta.id, "blob.bin")!!
            assertTrue(bin.binary)
            assertEquals("", bin.text)

            // 空文件可打开
            File(gameDir, "empty.txt").writeText("")
            val empty = repo.readGameFile(meta.id, "empty.txt")!!
            assertEquals("", empty.text)
        }
        dir.deleteRecursively()
    }
}
