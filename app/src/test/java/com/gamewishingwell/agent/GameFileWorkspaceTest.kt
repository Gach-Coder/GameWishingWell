package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class GameFileWorkspaceTest {

    @Test
    fun `沙箱禁止越界路径`() {
        val ws = GameFileWorkspace(File(System.getProperty("java.io.tmpdir"), "ws-root"))
        assertNull(ws.resolve("../outside.html"))
        assertNull(ws.resolve("/etc/passwd"))
    }

    @Test
    fun `版本化写入与指针切换`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-version-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            val first = ws.writeInitial("index.html", "v1")
            val second = ws.writeUpdated("index.html", "v2")
            assertEquals(1, first?.version)
            assertEquals(2, second?.version)
            assertEquals("v2", ws.read("index.html"))
            assertEquals("index.html", ws.manifest().pointer)
            assertTrue(File(dir, ".versions/1-index.html").exists())
            assertTrue(File(dir, "current.txt").exists())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `hash 校验失败时拒绝覆盖`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-hash-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            ws.writeInitial("index.html", "v1")
            val rejected = ws.writeInitial("index.html", "v2")
            assertNull(rejected)
            val badHash = ws.writeUpdated("index.html", "v2", expectedHash = "bad")
            assertNull(badHash)
            assertEquals("v1", ws.read("index.html"))
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writeUpdated 全量替换并版本递增`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-update-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            ws.writeInitial("index.html", "v1")
            val updated = ws.writeUpdated("index.html", "v2")
            assertEquals(2, updated?.version)
            assertEquals("v2", ws.read("index.html"))
            assertTrue(File(dir, ".versions/1-index.html").exists())
            // 不存在的文件不允许走 writeUpdated（首次必须 writeInitial）
            assertNull(ws.writeUpdated("other.html", "x"))
            dir.deleteRecursively()
        }
    }

    @Test
    fun `delete 清理版本簿记避免跨会话版本串台`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-del-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            ws.writeInitial("index.html", "gameA-v1")
            ws.writeUpdated("index.html", "gameA-v2")
            ws.writeUpdated("index.html", "gameA-v3")
            assertTrue(ws.delete("index.html"))

            // 版本 marker 与归档随文件一并清理：删除→重建从 v1 重新计数，
            // 上一款游戏的历史归档不得被新游戏的 v1 覆盖（版本串台）。
            assertFalse(File(dir, ".versions/index.html.version").exists())
            assertTrue(File(dir, ".versions").list().orEmpty().isEmpty())
            assertNull(ws.read("index.html"))

            val reborn = ws.writeInitial("index.html", "gameB-v1")
            assertEquals(1, reborn?.version)
            ws.writeUpdated("index.html", "gameB-v2")
            assertEquals("gameB-v2", File(dir, "index.html").readText())
            assertEquals("gameB-v1", File(dir, ".versions/1-index.html").readText())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reset 清空整个工作区-新游戏不继承旧辅助文件`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-reset-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            ws.writeInitial("index.html", "old-entry")
            ws.writeInitial("js/main.js", "old-js")
            ws.writeInitial("scenarios.json", "{\"scenarios\":[]}")
            ws.writeUpdated("js/main.js", "old-js-v2")

            ws.reset()
            assertNull(ws.read("index.html"))
            assertNull(ws.read("js/main.js"))
            assertNull(ws.read("scenarios.json"))
            // 整目录清空（含 .versions 簿记）：新游戏从零开始
            assertTrue(dir.listFiles().orEmpty().isEmpty())

            // 重建从 v1 干净起算
            val reborn = ws.writeInitial("index.html", "new-entry")
            assertEquals(1, reborn?.version)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `归档滚动修剪只保留最近 KEEP_VERSIONS 个版本`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-prune-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            val total = GameFileWorkspace.KEEP_VERSIONS + 6
            ws.writeInitial("index.html", "v1")
            for (v in 2..total) ws.writeUpdated("index.html", "v$v")

            val versionsDir = File(dir, ".versions")
            val archives = versionsDir.listFiles().orEmpty()
                .filter { it.name.endsWith("-index.html") }
                .mapNotNull { it.name.substringBefore('-').toIntOrNull() }
            assertTrue(archives.isNotEmpty())
            assertEquals(GameFileWorkspace.KEEP_VERSIONS, archives.size)
            assertEquals(total - GameFileWorkspace.KEEP_VERSIONS, archives.min())
            assertEquals(total - 1, archives.max())
            dir.deleteRecursively()
        }
    }
}
