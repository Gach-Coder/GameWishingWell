package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlExtractorTest {

    private val simpleHtml = "<!DOCTYPE html><html><head></head><body><canvas id=\"g\"></canvas><script>var x=1;</script></body></html>"

    @Test
    fun `提取 markdown 围栏内的 HTML`() {
        val raw = "好的，这是游戏：\n\n```html\n$simpleHtml\n```\n\n希望你喜欢！"
        val result = HtmlExtractor.extract(raw)
        assertEquals(simpleHtml, result.html)
        assertNull(result.error)
    }

    @Test
    fun `提取裸 HTML 片段`() {
        val raw = "游戏代码：\n\n$simpleHtml"
        val result = HtmlExtractor.extract(raw)
        assertEquals(simpleHtml, result.html)
        assertNull(result.error)
    }

    @Test
    fun `无 HTML 时返回错误`() {
        val result = HtmlExtractor.extract("抱歉，我不能生成游戏。")
        assertNull(result.html)
        assertNotNull(result.error)
    }

    @Test
    fun `围栏标签大小写不敏感`() {
        val raw = "```HTML\n$simpleHtml\n```"
        assertEquals(simpleHtml, HtmlExtractor.extract(raw).html)
    }

    @Test
    fun `外部链接给出警告`() {
        val raw = "<html><head></head><body><script src=\"https://cdn.example.com/lib.js\"></script></body></html>"
        val result = HtmlExtractor.extract(raw)
        assertNotNull(result.html)
        assertTrue(result.warnings.any { it.contains("外部链接") })
    }

    @Test
    fun `围栏内无完整 html 时退回裸匹配`() {
        val raw = "```\n不是 html\n```\n下面是真代码：\n\n$simpleHtml"
        assertEquals(simpleHtml, HtmlExtractor.extract(raw).html)
    }

    @Test
    fun `剥离代码后保留对话文本`() {
        val raw = "游戏做好了！\n\n```html\n$simpleHtml\n```\n\n祝你玩得开心。"
        assertEquals("游戏做好了！\n\n\n\n祝你玩得开心。", HtmlExtractor.nonCodeText(raw))
    }

    @Test
    fun `JS 字符串中的闭合标签不会提前截断`() {
        val raw = "<!DOCTYPE html><html><body><script>var s = \"</html>\"; console.log(s);</script></body></html>"
        val result = HtmlExtractor.extract(raw)
        assertNotNull(result.html)
        assertTrue(result.html!!.contains("var s = \"</html>\""))
    }

    @Test
    fun `JS 字符串中的代码围栏不会提前截断`() {
        val raw = "```html\n<!DOCTYPE html><html><body><script>var t = \"```\";</script></body></html>\n```"
        val result = HtmlExtractor.extract(raw)
        assertNotNull(result.html)
        assertTrue(result.html!!.contains("var t = \"```\""))
    }

    @Test
    fun `中断时从未完成流中抢救可尝试游玩的 HTML`() {
        val partial = HtmlExtractor.extractPartial("好的，下面是游戏代码：\n```html\n<html><body><canvas id=\"game\"></canvas><script>")
        assertNotNull(partial)
        assertTrue(partial!!.startsWith("<html", ignoreCase = true))
        assertTrue(partial.endsWith("</html>", ignoreCase = true))
        assertTrue(partial.contains("canvas"))
    }

    @Test
    fun `多围栏回复取第一个完整 HTML 不吞并后续块`() {
        val jsonBlock = "```json\n{\"scenarios\":[]}\n```"
        val raw = "游戏做好了：\n\n```html\n$simpleHtml\n```\n\n断言数据：\n$jsonBlock\n\n完成。"
        val result = HtmlExtractor.extract(raw)
        assertNotNull(result.html)
        assertEquals(simpleHtml, result.html)
        assertFalse(result.html!!.contains("scenarios"))
    }

    @Test
    fun `多围栏回复 nonCodeText 保留围栏之间的正文`() {
        val raw = "游戏做好了！\n\n```html\n$simpleHtml\n```\n\n断言如下：\n\n```json\n[1,2]\n```\n\n完成。"
        val text = HtmlExtractor.nonCodeText(raw)
        assertTrue(text.contains("断言如下"))
        assertTrue(text.contains("完成"))
        assertFalse(text.contains("<html"))
    }
}
