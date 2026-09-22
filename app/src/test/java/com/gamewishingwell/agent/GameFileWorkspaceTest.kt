package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 两区模型（2026-09-07 简化）下的编辑区工作区：平写、无历史版本归档——
 * 只保留沙箱边界、hash 并发校验、删除/重置与存量簿记清理的回归。
 */
class GameFileWorkspaceTest {

    @Test
    fun `沙箱禁止越界路径`() {
        val ws = GameFileWorkspace(File(System.getProperty("java.io.tmpdir"), "ws-root"))
        assertNull(ws.resolve("../outside.html"))
        assertNull(ws.resolve("/etc/passwd"))
    }

    @Test
    fun `平写覆盖且不产生任何版本簿记`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-plain-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            ws.writeInitial("index.html", "v1")
            ws.writeUpdated("index.html", "v2")
            assertEquals("v2", ws.read("index.html"))
            // 不再产生 .versions 归档、current.txt 指针与 sha256 文件
            assertFalse(File(dir, ".versions").exists())
            assertFalse(File(dir, "current.txt").exists())
            assertFalse(File(dir, "index.html.sha256").exists())
            assertEquals(listOf("index.html"), ws.manifest().files.map { it.path })
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
            // 正确 hash 放行（并发写入方基于最新内容的覆盖）
            val good = GameFileWorkspace.sha256("v1")
            assertEquals("v2", ws.writeUpdated("index.html", "v2", expectedHash = good)?.let { ws.read("index.html") })
            dir.deleteRecursively()
        }
    }

    @Test
    fun `不存在的文件不允许 writeUpdated`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-update-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            assertNull(ws.writeUpdated("other.html", "x"))
            dir.deleteRecursively()
        }
    }

    @Test
    fun `delete 直接删除文件`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-del-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            ws.writeInitial("index.html", "content")
            assertTrue(ws.delete("index.html"))
            assertNull(ws.read("index.html"))
            assertTrue(ws.writeInitial("index.html", "reborn") != null)
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

            ws.reset()
            assertNull(ws.read("index.html"))
            assertNull(ws.read("js/main.js"))
            assertNull(ws.read("scenarios.json"))
            assertTrue(dir.listFiles().orEmpty().isEmpty())

            assertTrue(ws.writeInitial("index.html", "new-entry") != null)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `init 清理历史版本簿记（归档目录与指针）`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-legacy-${System.nanoTime()}")
            // 模拟旧版本应用遗留：根与嵌套 .versions、current.txt、旧版 sha256
            File(dir, ".versions").mkdirs()
            File(dir, ".versions/3-index.html").writeText("legacy-archive")
            File(dir, ".versions/index.html.version").writeText("4")
            File(dir, "js/.versions").mkdirs()
            File(dir, "js/.versions/5-main.js").writeText("legacy")
            File(dir, "js/main.js").writeText("current")
            File(dir, "current.txt").writeText("index.html")
            File(dir, "index.html.sha256").writeText("deadbeef")

            val ws = GameFileWorkspace(dir)
            assertFalse(File(dir, ".versions").exists())
            assertFalse(File(dir, "js/.versions").exists())
            assertFalse(File(dir, "current.txt").exists())
            assertFalse(File(dir, "index.html.sha256").exists())
            // 游戏文件本体不受清理影响
            assertEquals("current", ws.read("js/main.js"))
            dir.deleteRecursively()
        }
    }
}
