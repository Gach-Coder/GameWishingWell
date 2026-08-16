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
            val second = ws.patchLines("index.html", 1, 1, "v2")
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
            val badHash = ws.patchLines("index.html", 1, 1, "v2", expectedHash = "bad")
            assertNull(badHash)
            assertEquals("v1", ws.read("index.html"))
            dir.deleteRecursively()
        }
    }

    @Test
    fun `行级 patch`() {
        runBlocking {
            val dir = File(System.getProperty("java.io.tmpdir"), "ws-patch-${System.nanoTime()}")
            val ws = GameFileWorkspace(dir)
            ws.writeInitial("index.html", "a\nb\nc")
            val patched = ws.patchLines("index.html", 2, 3, "B\nC")
            assertEquals("a\nB\nC", ws.read("index.html"))
            assertEquals(2, patched?.version)
            dir.deleteRecursively()
        }
    }
}
