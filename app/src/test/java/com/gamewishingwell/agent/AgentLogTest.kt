package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentLogTest {

    @Test
    fun `每回合创建独立日志文件且内容带时间戳与scope`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "agentlog-${System.nanoTime()}")
        val log = AgentLog()
        log.beginTurn(File(dir, "log"))
        log.log("draft", "turn start: 做一个塔防游戏")
        log.log("game-3", "round 1: textLen=120 toolCalls=[writefile]")
        log.endTurn()

        val files = File(dir, "log").listFiles { f -> f.name.endsWith(".log") }.orEmpty()
        assertEquals(1, files.size)
        assertTrue("文件名=回合开始时间（yyyyMMdd_HHmmss.log）", Regex("""\d{8}_\d{6}\.log""").matches(files[0].name))
        val content = files[0].readText(Charsets.UTF_8)
        assertTrue(content.contains("[draft] turn start"))
        assertTrue(content.contains("[game-3] round 1"))
        assertTrue(Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(content))
        dir.deleteRecursively()
    }

    @Test
    fun `第二回合开新文件不覆盖上一回合日志`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "agentlog-${System.nanoTime()}")
        val log = AgentLog()
        log.beginTurn(File(dir, "log"))
        log.log("draft", "first turn")
        log.endTurn()
        log.beginTurn(File(dir, "log"))
        log.log("draft", "second turn")
        log.endTurn()

        val files = File(dir, "log").listFiles { f -> f.name.endsWith(".log") }.orEmpty().sortedBy { it.name }
        assertEquals(2, files.size)
        assertTrue(files[0].readText(Charsets.UTF_8).contains("first turn"))
        assertTrue(files[1].readText(Charsets.UTF_8).contains("second turn"))
        dir.deleteRecursively()
    }

    @Test
    fun `回合外与目录被删后静默跳过不抛异常`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "agentlog-${System.nanoTime()}")
        val log = AgentLog()
        // 未 beginTurn 前（回合外）不产生任何文件：日志只能由回合入口创建
        log.log("draft", "before any turn")
        assertFalse(dir.exists())

        // 回合已收尾：此后的零散写入被丢弃
        log.beginTurn(File(dir, "log"))
        log.endTurn()
        log.log("draft", "after turn end")
        val files = File(dir, "log").listFiles().orEmpty()
        assertEquals(0, files.size)

        // 目录被删后 beginTurn/log 仍应安全（best-effort 语义）
        dir.deleteRecursively()
        log.beginTurn(File(dir, "log"))
        log.log("draft", "after dir removed")
    }
}
