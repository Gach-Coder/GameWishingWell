package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** 回合耗时文案：用户可见的口语化格式。 */
class AgentDurationFormatTest {

    @Test
    fun `formats subsecond seconds minutes and hours`() {
        assertEquals("不足 1 秒", formatAgentDuration(0))
        assertEquals("不足 1 秒", formatAgentDuration(999))
        assertEquals("1 秒", formatAgentDuration(1_000))
        assertEquals("59 秒", formatAgentDuration(59_999))
        assertEquals("1 分 0 秒", formatAgentDuration(60_000))
        assertEquals("1 分 23 秒", formatAgentDuration(83_400))
        assertEquals("59 分 59 秒", formatAgentDuration(3_599_999))
        assertEquals("1 小时 0 分", formatAgentDuration(3_600_000))
        assertEquals("2 小时 5 分", formatAgentDuration(7_500_000))
    }

    @Test
    fun `negative and zero are defensive`() {
        assertEquals("不足 1 秒", formatAgentDuration(-5))
    }
}
