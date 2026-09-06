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
        window.__wwDebugState = function(){ return { state: 'playing', entities: [] }; };
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
    fun `本地 script src 与本地图片同为 error`() {
        // 无工作区（兼容回环等单文件场景）：本地引用一律 error——单文件直跑必然 404。
        val html = "<html><body><script src=\"game.js\"></script><img src=\"sprite.png\"></body></html>"
        val report = GameValidator.validate(html)
        assertTrue(report.errors.any { it.category == "resources" && it.message.contains("game.js") })
        assertTrue(report.errors.any { it.category == "resources" && it.message.contains("sprite.png") })
    }

    @Test
    fun `多文件模式-存在的本地 js 与 css 引用合法`() {
        val html = "<html><head>" +
            "<link rel=\"stylesheet\" href=\"css/style.css\">" +
            "<script src=\"js/main.js\"></script>" +
            "</head><body><script>window.__wwDebugState=function(){return{state:'p'}};</script></body></html>"
        val report = GameValidator.validate(html, localFileExists = { it == "css/style.css" || it == "js/main.js" })
        assertFalse(report.errors.any { it.category == "resources" })
    }

    @Test
    fun `多文件模式-缺失的本地引用报错并引导创建`() {
        val html = "<html><head><script src=\"js/missing.js\"></script></head><body></body></html>"
        val report = GameValidator.validate(html, localFileExists = { false })
        val err = report.errors.firstOrNull { it.message.contains("js/missing.js") }
        assertTrue(err != null)
        assertTrue(err!!.message.contains("writefile"))
    }

    @Test
    fun `ES module 语法在多文件模式下被禁止`() {
        // 内联管道不做模块依赖图解析：跨文件必须普通 script 顺序加载
        val htmlImport = "<html><body><script>import { Enemy } from './enemy.js';</script></body></html>"
        assertTrue(
            GameValidator.validate(htmlImport).errors.any { it.message.contains("ES module") }
        )
        val htmlExport = "<html><body><script>export default function start(){};</script></body></html>"
        assertTrue(
            GameValidator.validate(htmlExport).errors.any { it.message.contains("ES module") }
        )
        // 普通 JS 不受影响
        val plain = "<html><body><script>var important = 1; var exported = 2;</script></body></html>"
        assertFalse(
            GameValidator.validate(plain).errors.any { it.message.contains("ES module") }
        )
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

    @Test
    fun `缺少可观测性契约报错`() {
        val html = "<html><body><canvas id=\"g\"></canvas><script>var x=1;</script></body></html>"
        val report = GameValidator.validate(html)
        assertTrue(report.errors.any { it.category == "observability" && it.message.contains("__wwDebugState") })
    }

    @Test
    fun `包含可观测性契约不因此报错`() {
        val html = "<html><body><canvas id=\"g\"></canvas><script>" +
            "window.__wwDebugState=function(){return{state:'playing',entities:[]}};var x=1;" +
            "</script></body></html>"
        val report = GameValidator.validate(html)
        assertFalse(report.errors.any { it.category == "observability" })
    }

    @Test
    fun `辅助文件按类型轻量校验`() {
        // 干净 js：无任何问题（入口级伪检查如可观测性契约不适用于纯 JS）
        assertTrue(
            GameValidator.validateAuxFile("js/main.js", "var WW = window.WW || {};\nWW.go = function(){ return 1; };").checks.isEmpty()
        )
        // js 的 eval 与 ES module 语法仍是硬约束
        assertTrue(
            GameValidator.validateAuxFile("js/a.js", "var r = eval('1');").errors.any { it.message.contains("eval") }
        )
        assertTrue(
            GameValidator.validateAuxFile("js/b.js", "import { x } from './x.js';").errors.any { it.message.contains("ES module") }
        )
        // css：url() 外链 error、本地引用 warning
        assertTrue(
            GameValidator.validateAuxFile("css/s.css", "body{background:url(https://x.com/a.png)}").errors.any { it.category == "resources" }
        )
        assertTrue(
            GameValidator.validateAuxFile("css/s.css", "body{background:url(img/a.png)}").warnings.any { it.message.contains("本地资源") }
        )
        // 不出现入口级伪错误
        assertFalse(
            GameValidator.validateAuxFile("js/main.js", "var a = 1;").errors.any { it.category == "observability" }
        )
    }

    @Test
    fun `多文件模式-超大纯内联入口给出拆分建议`() {
        val bigScript = "var x = 1;\n".repeat(700)
        val html = "<html><body><script>window.__wwDebugState=function(){};\n$bigScript</script></body></html>"
        val report = GameValidator.validate(html, localFileExists = { true })
        assertTrue(report.warnings.any { it.category == "structure" && it.message.contains("拆分") })
        // 已有本地引用（已拆分状态）不再提示
        val split = "<html><head><script src=\"js/main.js\"></script></head><body></body></html>"
        val r2 = GameValidator.validate(split, localFileExists = { true })
        assertFalse(r2.warnings.any { it.category == "structure" })
        // 单文件模式（兼容回环，无工作区）不给结构建议
        val r3 = GameValidator.validate(html)
        assertFalse(r3.warnings.any { it.category == "structure" })
        // 体量不大的内联不提示
        val r4 = GameValidator.validate(selfContained, localFileExists = { true })
        assertFalse(r4.warnings.any { it.category == "structure" })
    }
}
