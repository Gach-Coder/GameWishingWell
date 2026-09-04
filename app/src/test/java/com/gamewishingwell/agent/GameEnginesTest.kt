package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameEnginesTest {

    @Test
    fun `ww-engine 声明解析容忍属性顺序引号风格与逗号列表`() {
        assertEquals(listOf("three"), GameEngines.declaredEngines(
            "<head><meta name=\"ww-engine\" content=\"three\"></head>"
        ))
        // content 在 name 之前 + 单引号
        assertEquals(listOf("three"), GameEngines.declaredEngines(
            "<meta content='three' name='ww-engine'>"
        ))
        // 大写标签名与多余空白 + 逗号分隔多引擎
        assertEquals(listOf("three", "matter"), GameEngines.declaredEngines(
            "<META  name = \"ww-engine\"  content=\"three, matter\">"
        ))
        // 无声明 / 其它 meta 不受影响
        assertTrue(GameEngines.declaredEngines("<meta name=\"viewport\" content=\"width=device-width\">").isEmpty())
        // 重复声明去重
        assertEquals(listOf("three"), GameEngines.declaredEngines(
            "<meta name=\"ww-engine\" content=\"three\"><meta name=\"ww-engine\" content=\"three\">"
        ))
    }

    @Test
    fun `条件引入矩阵按维度系统与档位裁决`() {
        // 3D：three 必须；3D+物理：追加 matter 与可选 cannon
        assertEquals(setOf(GameEngines.THREE), GameEngines.allowedFor("3D", listOf("人物实体")))
        assertEquals(
            setOf(GameEngines.THREE, GameEngines.MATTER, GameEngines.CANNON),
            GameEngines.allowedFor("3D", listOf("物理", "关卡场景"))
        )
        // 2D+物理：matter；弹幕：可选 pixi；2D 无物理：无引擎
        assertEquals(setOf(GameEngines.MATTER), GameEngines.allowedFor("2D", listOf("物理")))
        assertEquals(setOf(GameEngines.PIXI), GameEngines.allowedFor("2D", listOf("弹幕射击")))
        assertTrue(GameEngines.allowedFor("2D", listOf("合成", "收集")).isEmpty())
        // 精品档：任何游戏追加可选 tween
        assertEquals(setOf(GameEngines.TWEEN), GameEngines.allowedFor("2D", listOf("合成"), "premium"))
        assertEquals(
            setOf(GameEngines.THREE, GameEngines.TWEEN),
            GameEngines.allowedFor("3D", emptyList(), "premium")
        )
        // 档位归一化：非法值回落均衡（无 tween）
        assertEquals(setOf(GameEngines.MATTER), GameEngines.allowedFor("2D", listOf("物理"), "外星档"))
    }

    @Test
    fun `未内置引擎声明被基础校验打回`() {
        val bad = "<html><head><meta name=\"ww-engine\" content=\"babylon\"></head><body></body></html>"
        val report = GameValidator.validate(bad)
        assertTrue(report.hasErrors)
        assertTrue(report.errors.any { it.message.contains("不支持的引擎声明") && it.message.contains("babylon") })
    }

    @Test
    fun `条件不符的引擎声明被门禁打回`() {
        val snakeWithPhysicsEngine =
            "<html><head><meta name=\"ww-engine\" content=\"matter\"></head><body><script>window.__wwDebugState=function(){};</script></body></html>"
        // 贪吃蛇（无物理系统）不允许 matter：门禁报错并给出可用集合
        val report = GameValidator.validate(snakeWithPhysicsEngine, allowedEngines = emptySet())
        assertTrue(report.hasErrors)
        assertTrue(report.errors.any { it.message.contains("matter 不适用于本游戏") })
        // 含物理系统时同一声明放行（仅剩观测性等其它检查）
        val ok = GameValidator.validate(snakeWithPhysicsEngine, allowedEngines = setOf(GameEngines.MATTER))
        assertFalse(ok.errors.any { it.message.contains("不适用于本游戏") })
    }

    @Test
    fun `内置引擎资产完整可内联`() {
        // Gradle 单测工作目录为模块目录（app/）；IDE 运行时从工程根也能解析
        fun asset(name: String): File? = sequenceOf(
            File("src/main/assets/engines/$name"),
            File("app/src/main/assets/engines/$name")
        ).firstOrNull { it.isFile }

        // 全部已内置引擎都有资产文件
        GameEngines.BUNDLED.values.forEach { spec ->
            val f = asset(spec.file)
            assertTrue("assets/engines/${spec.file} 缺失（引擎 ${spec.name} 未打包）", f != null)
            val src = f!!.readText(Charsets.UTF_8)
            // UMD 全局构建（暴露注册的全局对象）且体积合理
            assertTrue("${spec.name} 资产缺少全局对象 ${spec.globalName}", src.contains(spec.globalName))
            assertTrue("${spec.name} 资产体积异常（${src.length} 字符）", src.length > 15_000)
            // 内联安全：引擎源码中不得出现 </script> 字面量（会截断注入块）
            assertFalse("${spec.name} 资产包含 </script 字面量，不可内联", src.contains("</script"))
        }
    }

    @Test
    fun `引擎契约按条件组装教学`() {
        // 无引擎游戏：禁用段，不出现 three/matter 范式
        val plain = GamePrompt.engineContract("2D", listOf("合成", "收集"), "balanced")
        assertTrue(plain.contains("不使用任何引擎"))
        assertFalse(plain.contains("three.js（本游戏必须使用）"))
        // 物理游戏：matter 必选段 + 声明协议
        val physics = GamePrompt.engineContract("2D", listOf("物理", "收集"), "balanced")
        assertTrue(physics.contains("matter.js（本游戏含物理系统，必须使用）"))
        assertTrue(physics.contains("ww-engine"))
        assertFalse(physics.contains("three.js（本游戏必须使用）"))
        // 3D+物理+精品：全量范式（three/matter/cannon/tween）
        val full = GamePrompt.engineContract("3D", listOf("物理"), "premium")
        assertTrue(full.contains("three.js（本游戏必须使用）"))
        assertTrue(full.contains("matter.js"))
        assertTrue(full.contains("cannon"))
        assertTrue(full.contains("tween"))
        // 弹幕：pixi 可选段（措辞为可选而非必须）
        val bullet = GamePrompt.engineContract("2D", listOf("弹幕射击"), "balanced")
        assertTrue(bullet.contains("pixi.js（可选"))
    }
}
