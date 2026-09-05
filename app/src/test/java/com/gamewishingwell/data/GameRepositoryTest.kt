package com.gamewishingwell.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
