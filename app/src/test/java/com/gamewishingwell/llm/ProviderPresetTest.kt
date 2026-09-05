package com.gamewishingwell.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        // MiniMax 稀宇：OpenAI 兼容端点（国内），模型 ID 遵循平台 MiniMax-M* 命名
        val minimax = ProviderPresets.byId("minimax")!!
        assertEquals("MiniMax-M3", minimax.defaultModel)
        assertEquals("https://api.minimaxi.com/v1", minimax.baseUrl)
        assertEquals(Protocol.OPENAI_COMPATIBLE, minimax.protocol)
        assertEquals(16384, minimax.maxTokens)
        assertFalse(minimax.disableThinking)

        // OpenRouter 聚合网关：OpenAI 兼容端点，默认模型为免费端点 z-ai/glm-5.2:free（限流，可改换）
        val openrouter = ProviderPresets.byId("openrouter")!!
        assertEquals("z-ai/glm-5.2:free", openrouter.defaultModel)
        assertEquals("https://openrouter.ai/api/v1", openrouter.baseUrl)
        assertEquals(Protocol.OPENAI_COMPATIBLE, openrouter.protocol)
        assertFalse(openrouter.disableThinking)

        assertEquals(8192, ProviderPresets.byId("custom")!!.maxTokens)
    }

    @Test
    fun `模型下拉清单首位即默认模型`() {
        // 清单约定：models 首位 = defaultModel（设置页下拉的推荐默认），
        // custom 例外（纯手输，无清单）。
        ProviderPresets.all.filter { it.id != "custom" }.forEach { p ->
            if (p.models.isNotEmpty()) {
                assertEquals("厂商 ${p.id} 的 models 首位应为默认模型", p.defaultModel, p.models.first())
                assertEquals("厂商 ${p.id} 的清单不得有重复项", p.models.size, p.models.distinct().size)
            }
        }
    }

    @Test
    fun `deepseek 可选清单为 v4 系列双型号`() {
        val deepseek = ProviderPresets.byId("deepseek")!!
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), deepseek.models)
    }

    @Test
    fun `openrouter 内置全部免费对话模型`() {
        // 快照同步自 openrouter.ai/models?variant=free（2026-09-05）：
        // 已排除音频输出的 lyria 系与无工具调用的内容安全分类器——
        // 仅保留 text-out 且支持 tools 的对话模型（Agent 工具模式依赖 function calling）
        val openrouter = ProviderPresets.byId("openrouter")!!
        assertEquals(19, openrouter.models.size)
        assertTrue(openrouter.models.contains("minimax/minimax-m3:free"))
        assertTrue(openrouter.models.contains("google/gemma-4-31b-it:free"))
        assertTrue(openrouter.models.contains("openrouter/free"))
        // 非对话/分类器模型不得混入下拉
        assertFalse(openrouter.models.any { it.contains("lyria") })
        assertFalse(openrouter.models.any { it.contains("content-safety") })
    }
}
