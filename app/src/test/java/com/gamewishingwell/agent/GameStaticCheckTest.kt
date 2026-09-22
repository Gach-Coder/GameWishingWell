package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跨文件模块导出契约静态预检（写入时刻）的表驱动测试。 */
class GameStaticCheckTest {

    // 我的世界的真实形态：window.Input = (function(){...return {...}})();
    private val TOUCH_WITH_SETUI = """
window.Input = (function(){
  var uic = null;
  function setUI(c){ uic = c; }
  function bind(cv){ }
  return {
    setUI: setUI,
    bind: bind,
    joy: { x: 0, active: false },
    jump: function(){ return false; },
    drawUI: function(){}
  };
})();
"""

    private val TOUCH_MISSING_SETUI = """
window.Input = (function(){
  var uic = null;
  function setUI(c){ uic = c; }
  function bind(cv){ }
  return {
    bind: bind,
    jump: function(){ return false; },
    drawUI: function(){}
  };
})();
"""

    private val MAIN_CALLS = """
window.G = { state: 'menu' };
(function(){
  function init(){
    var uic = document.createElement('canvas');
    Input.setUI(uic);
    Input.bind(document.getElementById('g'));
    var j = Input.joy.active;
    if (Input.jump()) { G.state = 'playing'; }
  }
  setTimeout(init, 30);
})();
"""

    @Test
    fun `丢失导出成员被点名-调用方与成员都在回报里`() {
        val violations = GameStaticCheck.moduleExportViolations(
            mapOf("js/touch.js" to TOUCH_MISSING_SETUI, "js/main.js" to MAIN_CALLS)
        )
        assertEquals(1, violations.size)
        val v = violations.first()
        assertEquals("Input", v.module)
        assertEquals("setUI", v.member)
        assertTrue(v.callers.contains("js/main.js"))
    }

    @Test
    fun `导出齐全零违规-属性访问与方法调用都不误报`() {
        val violations = GameStaticCheck.moduleExportViolations(
            mapOf("js/touch.js" to TOUCH_WITH_SETUI, "js/main.js" to MAIN_CALLS)
        )
        assertTrue("应无违规，实际 $violations", violations.isEmpty())
    }

    @Test
    fun `单文件内联模块同样受契约约束`() {
        // 单文件形态：模块与调用同在 index.html
        val html = "<html><body><script>$TOUCH_MISSING_SETUI</script>" +
            "<script>$MAIN_CALLS</script></body></html>"
        val violations = GameStaticCheck.moduleExportViolations(mapOf("index.html" to html))
        assertEquals(1, violations.size)
        assertEquals("setUI", violations.first().member)
    }

    @Test
    fun `导出表不可解析的模块跳过-plain对象与引擎全局不参与`() {
        // plain 对象（window.G = {...}）不是模块；引擎全局 THREE 不在工作区定义
        val files = mapOf(
            "index.html" to """
                <script>
                  window.G = { cam: { yaw: 0.6, pitch: -0.3 } };
                  window.Planner = (function(){ var x = 1; return x; })();
                  (function(){
                    if (G.cam.pitch < 0) G.cam.pitch = 0.35;
                    var m = THREE.MathUtils.degToRad(90);
                    Planner.next();
                  })();
                </script>
            """.trimIndent()
        )
        // Planner 无 return {} 块（return 的是标量）→ 导出表不可解析 → 跳过
        val violations = GameStaticCheck.moduleExportViolations(files)
        assertTrue("应无违规，实际 $violations", violations.isEmpty())
    }

    @Test
    fun `嵌套对象与字符串字面量不破坏平衡扫描`() {
        val files = mapOf(
            "js/m.js" to """
                window.M = (function(){
                  return {
                    cfg: { braces: "}{ not }{ counted", label: 'a{b}c' },
                    run: function(){ return 1; }
                  };
                })();
            """.trimIndent(),
            "index.html" to "<script>M.run();</script>"
        )
        assertTrue(GameStaticCheck.moduleExportViolations(files).isEmpty())
        val broken = mapOf(
            "js/m.js" to """
                window.M = (function(){
                  return {
                    cfg: { braces: "}{ not }{ counted", label: 'a{b}c' }
                  };
                })();
            """.trimIndent(),
            "index.html" to "<script>M.run();</script>"
        )
        assertEquals(1, GameStaticCheck.moduleExportViolations(broken).size)
    }
}
