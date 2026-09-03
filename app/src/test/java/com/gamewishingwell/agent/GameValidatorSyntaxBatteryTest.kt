package com.gamewishingwell.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归锁定：轻量契约检查已彻底移除语法/no-undef/HTML 配对分析——
 * 一批"在 Android WebView 中可正常运行"的现代 JS 写法（class/可选链/空值合并/
 * spread/for-of/箭头默认参数等，此前被 Rhino 1.7.15 误判 syntax error）
 * 必须零 error。运行正确性只由沙箱（GameSmokeTest）裁决。
 */
class GameValidatorSyntaxBatteryTest {

    private val snippets = listOf(
        "class Enemy { constructor(x){ this.x = x; } move(){ this.x++; } } var e = new Enemy(1); e.move();",
        "var a = {}; var v = a.b?.c;",
        "var v = null ?? 5;",
        "function add(a,b){return a+b;} var args=[1,2]; add(...args);",
        "var arr=[1,2]; var b=[...arr,3]; b.length;",
        "var t=0; for (const x of [1,2,3]) { t+=x; }",
        "var n='w'; var s = `hi \${n}`;",
        "var f=(a=1)=>a+1; f();",
        "var o={ get v(){return 1;}, go(){ return 2; } }; o.v; o.go();",
        "var fs=[]; for(let i=0;i<3;i++){ fs.push(function(){return i;}); } fs[0]();",
        "function f(p){return p.x+p.y;} f({x:1,y:2});",
        "var k='s'; localStorage.setItem(k,'1'); var v=localStorage.getItem(k);",
        "var p={x:1,y:2}; var {x,y}=p; x+y;",
        "var r=0; try { r=1; } catch(e){ r=2; } finally { r+=1; }",
        "var t=0; function loop(){ t++; setTimeout(loop, 16); } loop();"
    )

    @Test
    fun `现代JS写法不产生任何契约错误`() {
        snippets.forEachIndexed { idx, js ->
            val report = GameValidator.validate("<html><body><script>$js</script></body></html>")
            assertTrue(
                "样本#$idx 被误判: ${report.errors.joinToString(";") { it.message }}",
                report.errors.isEmpty()
            )
        }
    }
}
