package com.gamewishingwell.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSettingsTest {

    @Test
    fun `模型思考能力默认关闭`() {
        assertFalse(LlmSettings().thinkingEnabled)
    }

    @Test
    fun `模型思考能力开关可保存到设置数据类`() {
        val enabled = LlmSettings(thinkingEnabled = true)
        assertTrue(enabled.thinkingEnabled)
    }
}
