package com.gamewishingwell.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderPresetTest {

    @Test
    fun `厂商默认预设与 instruct 第二章一致`() {
        val deepseek = ProviderPresets.byId("deepseek")!!
        assertEquals("deepseek-v4-flash", deepseek.defaultModel)
        assertEquals("https://api.deepseek.com/v1", deepseek.baseUrl)
        assertEquals(Protocol.OPENAI_COMPATIBLE, deepseek.protocol)
        assertEquals(16384, deepseek.maxTokens)
        assertFalse(deepseek.disableThinking)

        assertEquals("moonshot-v1-8k", ProviderPresets.byId("kimi")!!.defaultModel)
        assertEquals("gpt-4o-mini", ProviderPresets.byId("openai")!!.defaultModel)
        assertEquals("claude-sonnet-4-6", ProviderPresets.byId("anthropic")!!.defaultModel)

        val zhipu = ProviderPresets.byId("zhipu")!!
        assertEquals("glm-5.3-flash", zhipu.defaultModel)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", zhipu.baseUrl)
        assertEquals(Protocol.OPENAI_COMPATIBLE, zhipu.protocol)
        assertEquals(16384, zhipu.maxTokens)
        assertFalse(zhipu.disableThinking)

        assertEquals(8192, ProviderPresets.byId("custom")!!.maxTokens)
    }
}
