package com.gamewishingwell.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            val meta = repo.saveGame("打地鼠", "描述", "<html>v1</html>", listOf(ChatMessage("user", "做一个游戏")))
            repo.updateGameHtml(meta.id, "<html>v2</html>", listOf(ChatMessage("user", "做一个游戏")))
            assertTrue(File(File(dir, "games/${meta.id}"), ".versions/1-index.html").exists())
            assertEquals("<html>v2</html>", repo.loadGameHtml(meta.id))

            assertTrue(repo.renameGame(meta.id, "新名字"))
            assertEquals("新名字", repo.listGames().first { it.id == meta.id }.title)

            assertTrue(repo.rollbackGame(meta.id))
            assertEquals("<html>v1</html>", repo.loadGameHtml(meta.id))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统列出游戏文件夹一级条目`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame("打地鼠", "描述", "<html>v1</html>", listOf(ChatMessage("user", "做一个游戏")))
            repo.updateGameHtml(meta.id, "<html>v2</html>", listOf(ChatMessage("user", "做一个游戏")))

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
