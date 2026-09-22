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
        // 2.5D：three 同样必须（等距正交相机框架，2026-09-07 起与 3D 同等待遇）；
        // 2.5D+物理：three + matter（matter 是 2D 物理，等距世界坐标可直接用）
        assertEquals(setOf(GameEngines.THREE), GameEngines.allowedFor("2.5D", listOf("人物实体")))
        assertEquals(
            setOf(GameEngines.THREE, GameEngines.MATTER),
            GameEngines.allowedFor("2.5D", listOf("物理"))
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
        // 2.5D：等距框架范式（正交相机/固定斜角/Raycaster 拾取），不带 3D 的
        // 相机旋转硬性契约与视角旋转断言（2.5D 相机锁定固定等距角）；
        // 移动操控必须四方向（▲▼◀▶/全向摇杆），禁止只实现左右（实测反例：类DNF 仅 ◀▶）
        val iso = GamePrompt.engineContract("2.5D", listOf("人物实体", "战斗"), "balanced")
        assertTrue(iso.contains("2.5D 等距引擎 three.js（本游戏必须使用）"))
        assertTrue(iso.contains("OrthographicCamera"))
        assertTrue(iso.contains("Raycaster"))
        assertTrue(iso.contains("严禁手写 y-sort/画家算法"))
        assertTrue(iso.contains("▲▼◀▶"))
        assertTrue(iso.contains("只实现左右两向=2.5D 场景不可玩"))
        // 方向一致性契约（实测反例：摇杆轴 1:1 绑世界轴，角落相机下按上走成屏幕斜下方）
        assertTrue(iso.contains("方向语义＝屏幕视觉方向"))
        assertTrue(iso.contains("严禁把摇杆/方向键的输入轴 1:1 绑到世界或网格轴"))
        assertTrue(iso.contains("normalize(1,0,-1)"))
        // 可观测性：player 暴露屏幕坐标 sx/sy（方向一致性断言的依据）
        assertTrue(iso.contains("sx/sy"))
        assertFalse(iso.contains("scenarios.json 必含一条视角旋转断言"))
        assertFalse(iso.contains("右半屏单指滑动旋转相机"))
        // 3D 范式不受影响（视角旋转契约仍是硬性要求）；摇杆同样要求屏幕方向对齐
        val full3d = GamePrompt.engineContract("3D", listOf("人物实体"), "balanced")
        assertTrue(full3d.contains("【3D 引擎 three.js（本游戏必须使用）】"))
        assertTrue(full3d.contains("scenarios.json 必含一条视角旋转断言"))
        assertTrue(full3d.contains("摇杆方向＝屏幕视觉方向"))
        // 待机静止与 touch 绑定（实测事故：旋转只绑 mouse 沙箱拖不动；待机闲步骗断言）
        assertTrue(full3d.contains("都必须绑 touchstart/touchmove/touchend"))
        assertTrue(full3d.contains("严禁加\"待机自动转向\"让 yaw 自己漂移"))
        assertFalse(full3d.contains("OrthographicCamera"))
    }

    @Test
    fun `系统提示词的方向语义与屏幕坐标可观测性契约`() {
        val sys = GamePrompt.systemPrompt(custom = null, toolMode = true)
        // 通用方向语义：方向控件＝屏幕视觉移动方向（一切维度）；待机静止硬性禁令
        assertTrue(sys.contains("方向控件的方向＝人物在【屏幕上的视觉移动方向】"))
        assertTrue(sys.contains("待机静止"))
        assertTrue(sys.contains("无输入时画面必须完全静止"))
        // 可观测性：player 暴露 sx/sy 视口像素坐标
        assertTrue(sys.contains("sx: 0, sy: 0"))
        assertTrue(sys.contains("方向一致性断言的依据"))
    }

    @Test
    fun `用户限定只要左右时四向契约让位且纵深断言豁免`() {
        // 正常例："（2.5D）制作DNF"——四向硬性契约照常
        val normal = GamePrompt.engineContract("2.5D", listOf("人物实体", "战斗"))
        assertTrue(normal.contains("只实现左右两向=2.5D 场景不可玩，禁止"))
        assertFalse(normal.contains("玩家主权"))

        // 反例（玩家主权）："（2.5D）制作DNF，人物控制只要左右就行"——两向合法、纵深断言豁免
        val locked = GamePrompt.engineContract(
            "2.5D", listOf("人物实体", "战斗"),
            controlsLockedHorizontal = GamePrompt.movementLockedByUser("（2.5D）制作DNF，人物控制只要左右就行")
        )
        assertTrue(locked.contains("【用户操控约束（玩家主权"))
        assertTrue(locked.contains("纵深轴断言豁免"))
        assertTrue(locked.contains("用户主权优先于四向默认契约"))
        assertFalse("限定后不得再出现四向硬性禁令", locked.contains("只实现左右两向=2.5D 场景不可玩"))
        // 引擎本体契约不受影响（three 必须/正交相机照常）
        assertTrue(locked.contains("OrthographicCamera"))

        // 2D 游戏的限定不注入主权段（2D 默认本就是 ◀▶，无需豁免）
        val locked2d = GamePrompt.engineContract(
            "2D", listOf("战斗"),
            controlsLockedHorizontal = GamePrompt.movementLockedByUser("制作跑酷，只要左右")
        )
        assertFalse(locked2d.contains("玩家主权"))
    }

    @Test
    fun `移动操控限定的判定含历史延续与显式解锁`() {
        val lock = GamePrompt.movementLockedByUser("（2.5D）制作DNF，人物控制只要左右就行")
        assertTrue("用户原话命中限定", lock)
        // 无关措辞不误伤
        assertFalse(GamePrompt.movementLockedByUser("（2.5D）制作DNF"))
        assertFalse(GamePrompt.movementLockedByUser("做一个上下左右都能动的游戏"))
        assertFalse(GamePrompt.movementLockedByUser("画面不要上下留黑边"))
        // 历史延续：限定之后的"加个技能"回合仍锁定
        assertTrue(
            GamePrompt.movementLockedByUser(
                "加个技能",
                listOf("（2.5D）制作DNF，人物控制只要左右就行")
            )
        )
        // 显式解锁：用户要求加回上下移动时恢复四向契约
        assertFalse(
            GamePrompt.movementLockedByUser(
                "把上下移动也加上",
                listOf("（2.5D）制作DNF，人物控制只要左右就行")
            )
        )
    }
}
