package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameValidatorTest {

    private val valid = """
        <!DOCTYPE html><html><head></head><body>
        <canvas id="g"></canvas><div id="score"></div>
        <script>
        var score = 0;
        function restart(){ score = 0; }
        function frame(t){ document.getElementById('score').textContent = score; requestAnimationFrame(frame); }
        document.getElementById('g').addEventListener('touchstart', function(e){ e.preventDefault(); });
        requestAnimationFrame(frame);
        restart();
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `语法错误被捕获`() {
        val report = GameValidator.validate("<html><body><script>var x = ;</script></body></html>")
        assertTrue(report.checks.any { it.category == "syntax" && it.severity == "error" })
    }

    @Test
    fun `未定义变量被 no-undef 捕获`() {
        val report = GameValidator.validate("<html><body><script>missingFunction();</script></body></html>")
        assertTrue(report.checks.any { it.category == "static-runtime" && it.message.contains("missingFunction") })
    }

    @Test
    fun `DOM id 缺失产生警告`() {
        val report = GameValidator.validate("<html><body><script>document.getElementById('nope');</script></body></html>")
        assertTrue(report.checks.any { it.category == "dom-ids" && it.message.contains("nope") })
    }

    @Test
    fun `HTML 标签配对错误被捕获`() {
        val report = GameValidator.validate("<html><body><div></span></body></html>")
        assertTrue(report.checks.any { it.category == "html" && it.severity == "error" })
    }

    @Test
    fun `健康 HTML 通过静态校验`() {
        val report = GameValidator.validate(valid)
        assertEquals(report.errors.joinToString("\n") { "${it.category}@${it.line}:${it.message}" }, "")
        assertFalse(report.hasErrors)
    }

    @Test
    fun `eval 与动态 require 被代码契约拦截`() {
        val report = GameValidator.validate("<html><body><script>eval('1+1'); require('x');</script></body></html>")
        assertTrue(report.checks.count { it.severity == "error" && it.message.contains("eval") } >= 1)
        assertTrue(report.checks.any { it.message.contains("require") })
    }

    @Test
    fun `校验结果输出结构化 JSON`() {
        val report = GameValidator.validate("<html><body><script>var x = ;</script></body></html>")
        val json = report.toJsonString()
        assertTrue(json.contains("\"checks\""))
        assertTrue(json.contains("\"category\":\"syntax\""))
    }
}

class GameValidatorTemplateTest {
    @Test
    fun `内置模板可通过静态校验`() {
        val file = java.io.File("src/main/assets/game_template.html")
        if (!file.exists()) return
        val report = GameValidator.validate(file.readText(Charsets.UTF_8))
        assertEquals(report.errors.joinToString("\n") { "${it.category}@${it.line}:${it.message}" }, "")
    }
}

class GameSmokeProbeTest {
    @Test
    fun `冒烟探针注入确定性 tick 与超时兜底`() {
        val html = "<html><head></head><body><script>function frame(t){requestAnimationFrame(frame)} requestAnimationFrame(frame);</script></body></html>"
        val injected = SmokeTestProbe.inject(html)
        assertTrue(injected.contains("__wwSmokeInstalled"))
        assertTrue(injected.contains("__wwSmokeResult"))
        assertTrue(injected.contains("smoke-timeout"))
        assertTrue(injected.contains("requestAnimationFrame"))
    }
}
