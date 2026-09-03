package com.gamewishingwell.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentLogTest {

    @Test
    fun `日志写入本地文件且带时间戳与scope`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "agentlog-${System.nanoTime()}")
        AgentLog.initDir(dir)
        AgentLog.log("draft", "turn start: 做一个塔防游戏")
        AgentLog.log("game-3", "round 1: textLen=120 toolCalls=[writefile]")

        val content = File(dir, "agent.log").readText(Charsets.UTF_8)
        assertTrue(content.contains("[draft] turn start"))
        assertTrue(content.contains("[game-3] round 1"))
        assertTrue(Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(content))
        dir.deleteRecursively()
    }

    @Test
    fun `未初始化时静默跳过不抛异常`() {
        // initDir 到新目录前/失败场景：log 不应崩溃（best-effort 语义）
        val dir = File(System.getProperty("java.io.tmpdir"), "agentlog-${System.nanoTime()}")
        AgentLog.initDir(dir)
        dir.deleteRecursively() // 目录被删后仍应安全
        AgentLog.log("draft", "after dir removed")
    }
}
