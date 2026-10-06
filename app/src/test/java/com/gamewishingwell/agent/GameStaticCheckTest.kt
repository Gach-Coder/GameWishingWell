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

    // ---------- 参数外置契约维护（写入时刻，修复/迭代轮） ----------

    private val CFG_JS = """
window.GAME_CONFIG = { "speed": 3, "hp": 100 };
Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});
""".trimIndent()

    private val MAIN_JS = """
var CFG = window.GAME_CONFIG;
var v = CFG.speed + CFG.hp;
""".trimIndent()

    private fun startView() = mapOf("js/config.js" to CFG_JS, "js/main.js" to MAIN_JS)

    @Test
    fun `参数维护-悬空引用被拦`() {
        // main.js 新引用了 config 里没有的键：致命
        val files = startView() + ("js/main.js" to MAIN_JS + "\nvar d = CFG.critRate;")
        val v = GameStaticCheck.configContractViolations(files, startView()) { false }
        assertEquals(listOf(1, v.size).max(), v.size) // 1 条
        assertTrue(v[0].contains("悬空引用") && v[0].contains("critRate"))
    }

    @Test
    fun `参数维护-删除键未授权被拦且点名授权放行`() {
        // config.js 删掉 hp 键（未授权）：致命并点名
        val lost = startView() + (
            "js/config.js" to """window.GAME_CONFIG = { "speed": 3 };""" +
                "\nObject.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});"
            )
        val blocked = GameStaticCheck.configContractViolations(lost, startView()) { false }
        assertTrue(blocked.any { it.contains("只增不减") && it.contains("hp") })
        // 同一删除，但指令点名了 hp 键名：放行（只剩悬空引用检查——main.js 仍引用 hp）
        val named = GameStaticCheck.configContractViolations(lost, startView()) { it == "hp" }
        // main.js 引用 hp 而键已删：悬空引用照拦（授权删键≠允许留悬空引用）
        assertTrue(named.any { it.contains("悬空引用") && it.contains("hp") })
        assertTrue(named.none { it.contains("只增不减") })
        // 键删了且引用同步清理：完全放行
        val clean = lost + ("js/main.js" to "var CFG = window.GAME_CONFIG;\nvar v = CFG.speed;")
        assertTrue(GameStaticCheck.configContractViolations(clean, startView()) { it == "hp" }.isEmpty())
    }

    @Test
    fun `参数维护-新增键与调值不受限`() {
        val grown = startView() + (
            "js/config.js" to """window.GAME_CONFIG = { "speed": 9, "hp": 50, "critRate": 0.2 };""" +
                "\nObject.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});"
            ) + ("js/main.js" to MAIN_JS + "\nvar c = CFG.critRate;")
        assertTrue(GameStaticCheck.configContractViolations(grown, startView()) { false }.isEmpty())
    }

    @Test
    fun `参数维护-函数值被打回`() {
        val fn = startView() + (
            "js/config.js" to "window.GAME_CONFIG = { speed: function(){ return 3; } };\n" +
                "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});"
            )
        // hp 键同时被删（函数字面量不可解析）——只断言值类型那条在场
        val v = GameStaticCheck.configContractViolations(fn, startView()) { true }
        assertTrue(v.any { it.contains("非 JSON 值") })
    }

    @Test
    fun `参数维护-首代生成与旧游戏不参与`() {
        // 起点无 GAME_CONFIG（首次生成）：不拦（并行写入正常时序）
        val fresh = mapOf(
            "js/main.js" to "var CFG = window.GAME_CONFIG;\nvar v = CFG.speed;",
            "index.html" to "<html><script src=\"js/main.js\"></script></html>"
        )
        assertTrue(GameStaticCheck.configContractViolations(fresh, emptyMap()) { false }.isEmpty())
        // 旧游戏（起点与当前都无 GAME_CONFIG）：全部跳过
        val legacy = mapOf("index.html" to "<html><script>var a=1;</script></html>")
        assertTrue(GameStaticCheck.configContractViolations(legacy, legacy) { false }.isEmpty())
    }

    @Test
    fun `参数维护-注释里的引用不算悬空`() {
        val commented = startView() + ("js/main.js" to MAIN_JS + "\n// 修 CFG.critRate 的注释")
        assertTrue(GameStaticCheck.configContractViolations(commented, startView()) { false }.isEmpty())
    }

    // ---------- 删除残留检查（声明消失 → 全工作区裸引用扫描） ----------

    @Test
    fun `声明收集-多声明列表-函数形参-window模块`() {
        val src = """
'use strict';
var a = 1, b, c = f(x);
let d = { m: 1 }, e;
function g(p, q = 2){ var inner; }
try {} catch (err) {}
for (k of [1,2]) {}
window.M = (function(){ return {}; })();
""".trimIndent()
        val names = GameStaticCheck.declaredNames(src)
        assertTrue("实际收集：$names", names.containsAll(setOf("a", "b", "c", "d", "e", "g", "p", "q", "inner", "err", "k", "M")))
    }

    @Test
    fun `删除残留-bashQ事故形态被点名`() {
        // 事故最小复现：起点声明了 var bashQ，写入删掉声明、重置函数里仍留裸赋值
        val start = mapOf(
            "js/main.js" to "'use strict';\nvar bashQ = null;\nfunction newGame(){ bashQ = null; }\nfunction boot(){ newGame(); }"
        )
        val after = mapOf(
            "js/main.js" to "'use strict';\nfunction newGame(){ bashQ = null; }\nfunction boot(){ newGame(); }"
        )
        val v = GameStaticCheck.danglingReferenceViolations(after, start)
        assertEquals(1, v.size)
        assertTrue(v.first().contains("bashQ"))
        assertTrue("应点名引用位置 js/main.js:2", v.first().contains("js/main.js:2"))
    }

    @Test
    fun `删除残留-引用一并清除则放行`() {
        val start = mapOf(
            "js/main.js" to "'use strict';\nvar bashQ = null;\nfunction newGame(){ bashQ = null; }\nfunction boot(){ newGame(); }"
        )
        val after = mapOf(
            "js/main.js" to "'use strict';\nfunction newGame(){ }\nfunction boot(){ newGame(); }"
        )
        assertTrue(GameStaticCheck.danglingReferenceViolations(after, start).isEmpty())
    }

    @Test
    fun `删除残留-注释与字符串里的同名不算引用`() {
        val start = mapOf(
            "js/main.js" to "'use strict';\nvar bashQ = null;\nfunction f(){ bashQ = null; }"
        )
        val after = mapOf(
            "js/main.js" to "'use strict';\nfunction f(){ }\n// bashQ 已删除\nvar s = 'bashQ 残留？';"
        )
        assertTrue(GameStaticCheck.danglingReferenceViolations(after, start).isEmpty())
    }

    @Test
    fun `删除残留-属性访问与对象键不算引用`() {
        val start = mapOf(
            "js/main.js" to "'use strict';\nvar bashQ = null;\nfunction f(){ bashQ = null; }"
        )
        // obj.bashQ 属性访问与 { bashQ: 1 } 对象键均合法，不构成 ReferenceError
        val after = mapOf(
            "js/main.js" to "'use strict';\nvar o = { bashQ: 1 };\nfunction f(){ var x = o.bashQ; }"
        )
        assertTrue(GameStaticCheck.danglingReferenceViolations(after, start).isEmpty())
    }

    @Test
    fun `删除残留-跨文件声明仍在则放行`() {
        // plain script 共享全局：声明搬去另一文件不算删除
        val start = mapOf(
            "js/a.js" to "var bashQ = null;\nfunction f(){ bashQ = null; }"
        )
        val after = mapOf(
            "js/a.js" to "function f(){ bashQ = null; }",
            "js/b.js" to "var bashQ = null;"
        )
        assertTrue(GameStaticCheck.danglingReferenceViolations(after, start).isEmpty())
    }

    @Test
    fun `删除残留-起点为空不参与`() {
        // 首次生成轮：起点快照为空，自然无"声明消失"
        val after = mapOf("js/main.js" to "'use strict';\nfunction f(){ bashQ = null; }")
        assertTrue(GameStaticCheck.danglingReferenceViolations(after, emptyMap()).isEmpty())
    }
}
