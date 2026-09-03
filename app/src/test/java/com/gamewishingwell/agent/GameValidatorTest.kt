package com.gamewishingwell.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameValidatorTest {

    private val selfContained = """
        <!DOCTYPE html><html><head></head><body>
        <canvas id="g"></canvas>
        <script>
        class Enemy { constructor(x){ this.x = x; } }
        var e = new Enemy(1);
        const v = obj?.b ?? 5;
        function step(...args){ return args.length; }
        for (const x of [1,2]) { v + x; }
        localStorage.setItem('k', '1');
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `自包含的现代 JS 不产生任何契约错误`() {
        // class/可选链/空值合并/spread/for-of/localStorage 均为 WebView 可运行的
        // 合法写法——轻量契约检查不做语法/引用分析，不得误判。
        val report = GameValidator.validate(selfContained)
        assertFalse(report.hasErrors)
    }

    @Test
    fun `外部脚本与外部图片为契约错误`() {
        val html = "<html><body>" +
            "<script src=\"https://cdn.example.com/lib.js\"></script>" +
            "<img src=\"http://example.com/a.png\">" +
            "</body></html>"
        val report = GameValidator.validate(html)
        assertTrue(report.errors.any { it.message.contains("外部JS") })
        assertTrue(report.errors.any { it.message.contains("外部图片") })
    }

    @Test
    fun `本地资源引用为契约错误`() {
        val html = "<html><body><img src=\"assets/hero.png\"><audio src=\"bgm.mp3\"></body></html>"
        val report = GameValidator.validate(html)
        assertTrue(report.errors.any { it.message.contains("本地图片资源缺失") })
        assertTrue(report.errors.any { it.message.contains("本地音频资源缺失") })
    }

    @Test
    fun `eval 与 new Function 违反安全契约`() {
        val html = "<html><body><script>var r = eval('1+1'); var f = new Function('return 1');</script></body></html>"
        val report = GameValidator.validate(html)
        assertTrue(report.errors.any { it.message.contains("eval") })
        assertTrue(report.errors.any { it.message.contains("new Function") })
    }

    @Test
    fun `空内容为错误`() {
        val report = GameValidator.validate("   ")
        assertTrue(report.errors.any { it.message.contains("为空") })
    }
}
