package com.gamewishingwell.agent

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecognitionTest {

    // ---------- 纯文本特征抽取（契约核心：系统只从纯文本提取） ----------

    @Test
    fun `纯文本显性系统抽取`() {
        // 提到具体机制/系统 → 显性（走确认门）
        val td = GameSystemCatalog.extract("做一个塔防游戏，塔台包含4种，可以升级和回收")
        assertTrue(td.contains("塔防"))
        assertTrue(td.contains("属性等级"))
        // 只有对标游戏名 → 无显性系统（直接制作；对标理解归 Agent Loop）
        assertTrue(GameSystemCatalog.extract("制作一个我的世界游戏").isEmpty())
        // "接水果"本身是玩法机制描述（反应躲避类），属显性系统
        assertTrue(GameSystemCatalog.extract("做一个接水果").contains("反应躲避"))
    }

    @Test
    fun `系统准入归一化同义并放行合法自造清洗非法`() {
        assertEquals("自由建造", GameSystemCatalog.admit("建造"))
        assertEquals("自由建造", GameSystemCatalog.admit("沙盒建造"))
        assertEquals("战斗", GameSystemCatalog.admit("战斗"))
        assertEquals("开放探索", GameSystemCatalog.admit("开放探索"))
        assertEquals("潜行暗杀", GameSystemCatalog.admit("潜行暗杀"))
        assertNull(GameSystemCatalog.admit("  "))
        assertNull(GameSystemCatalog.admit("这是一个很长的不合法系统名字"))
        assertNull(GameSystemCatalog.admit("打怪,升级"))
    }

    // ---------- 识别层 ----------

    @Test
    fun `首次识别创建会话级 Game Schema 并与下游共享`() {
        val userText = "做一个2.5D横板跑酷游戏，要有关卡、收集金币和AI敌人"
        val result = RecognitionEngine.recognize(userText, existingSchema = null)

        assertTrue(result.isNewGame)
        assertFalse(result.emptyEntities)
        val schema = result.gameSchema
        assertEquals("2.5D", schema.visualDimension)
        assertEquals("横板", schema.screenOrientation)
        assertTrue(schema.gameSystems.containsAll(listOf("关卡场景", "收集", "AI策略")))
        assertEquals(userText, schema.firstUserRequest)
        assertEquals(schema, GameSchemaValidator.validate(schema))

        val plan = PlanningEngine.build(schema)
        assertEquals(schema.gameSystems, plan.gameSystems)
        assertTrue(plan.implementations.isNotEmpty())
    }

    @Test
    fun `识别层抽取不到实体时回退为普通聊天`() {
        val result = RecognitionEngine.recognize("今天天气怎么样", null)
        assertTrue(result.emptyEntities)
    }

    @Test
    fun `无实体但明确要求做游戏时仍创建默认 Game Schema`() {
        val result = RecognitionEngine.recognize("做一个完全没听过的游戏", null)
        assertFalse(result.emptyEntities)
    }

    @Test
    fun `非空 Game Schema 默认锚定同一个游戏并继续修改`() {
        val first = RecognitionEngine.recognize("做一个塔防游戏，敌人按波次进攻，有商店经济", null).gameSchema
        val modified = RecognitionEngine.recognize("不要商店经济", first)

        assertFalse(modified.isNewGame)
        assertFalse(modified.emptyEntities)
        assertTrue("商店经济" !in modified.gameSchema.gameSystems)
        assertTrue("商店经济" in modified.gameSchema.excludedSystems)
        assertTrue("塔防" in modified.gameSchema.gameSystems)
    }

    @Test
    fun `Lite LLM 自创系统名贯穿识别管线不被截断`() {
        val lite = GameSchemaValidator.parseLiteLlmReply(
            """{"visualDimension":"3D","screenOrientation":"横板","gameSystems":["开放探索","战斗","解谜"],"confidence":0.8}"""
        )
        assertNotNull(lite)
        val schema = RecognitionEngine.recognize("做一个开放世界冒险游戏", null, lite).gameSchema
        assertTrue("开放探索" in schema.gameSystems)
        assertEquals(schema, GameSchemaValidator.validate(schema))
        val plan = PlanningEngine.build(schema)
        assertTrue(plan.implementations.any { it.system == "开放探索" })
    }

    @Test
    fun `知名游戏按 LLM 常识回答形态保持不泛化`() {
        // 对标机制已删除：形态/系统来自 Lite LLM 的常识回答（提示词关键规则 1）。
        val lite = GameSchemaValidator.parseLiteLlmReply(
            """{"visualDimension":"3D","screenOrientation":"横板","gameSystems":["自由建造","收集","合成","人物实体"],"confidence":0.9}"""
        )
        val schema = RecognitionEngine.recognize("制作一个我的世界游戏", null, lite).gameSchema
        assertEquals("3D", schema.visualDimension)
        assertEquals("横板", schema.screenOrientation)
        assertTrue("自由建造" in schema.gameSystems)
    }

    @Test
    fun `纯文本显式形态优先于 Lite LLM 缺省值`() {
        // 用户显式说了 3D 横板，LLM 错回 2D/竖版时以正则显式值为准（防缺省翻转）。
        val lite = GameSchemaValidator.parseLiteLlmReply(
            """{"visualDimension":"2D","screenOrientation":"竖版","gameSystems":["自由建造"],"confidence":0.9}"""
        )
        val schema = RecognitionEngine.recognize("做一个3D横板的我的世界游戏", null, lite).gameSchema
        assertEquals("3D", schema.visualDimension)
        assertEquals("横板", schema.screenOrientation)
    }

    @Test
    fun `方向与维度的同义写法归一化为枚举规范值`() {
        val lite = GameSchemaValidator.parseLiteLlmReply(
            """{"visualDimension":"三维","screenOrientation":"横屏","gameSystems":[],"confidence":0.9}"""
        )
        assertNotNull(lite)
        assertEquals("3D", lite!!.visualDimension)
        assertEquals("横板", lite.screenOrientation)
    }

    // ---------- 确认门 ----------

    @Test
    fun `确认门回显系统玩法与验收边界且不泄露技术词`() {
        val schema = RecognitionEngine.recognize("做一个塔防游戏，敌人按波次进攻", null).gameSchema
        val confirmation = RecognitionEngine.buildConfirmation("做一个塔防游戏，敌人按波次进攻", schema)
        assertTrue(confirmation.modules.isNotEmpty())
        assertTrue(confirmation.summary.contains("塔防"))
        // 玩家视角说明不得包含技术实现词
        confirmation.modules.forEach {
            assertFalse(it.implementation.contains("Canvas"))
            assertFalse(it.implementation.contains("requestAnimationFrame"))
        }
    }

    @Test
    fun `确认卡形态选择器覆盖方向维度且同义归一`() {
        val base = RecognitionEngine.recognize(
            "做一个塔防游戏", null,
            GameSchemaValidator.parseLiteLlmReply("""{"visualDimension":"2D","screenOrientation":"竖版","gameSystems":["塔防"],"confidence":0.9}""")
        ).gameSchema
        assertEquals("竖版", base.screenOrientation)

        // 同值 no-op
        assertTrue(RecognitionEngine.applyOrientationDimension(base, "竖版", "2D") === base)
        // 玩家改方向（"横屏"归一为横板）
        val flipped = RecognitionEngine.applyOrientationDimension(base, "横屏", null)
        assertEquals("横板", flipped.screenOrientation)
        assertEquals("2D", flipped.visualDimension)
        // 非法/空值忽略
        val kept = RecognitionEngine.applyOrientationDimension(base, "歪的", "乱写")
        assertEquals(base.screenOrientation, kept.screenOrientation)
        assertEquals(base.visualDimension, kept.visualDimension)
    }

    @Test
    fun `确认卡携带形态字段并可持久化往返`() {
        val schema = RecognitionEngine.recognize(
            "做一个塔防游戏", null,
            GameSchemaValidator.parseLiteLlmReply("""{"visualDimension":"2D","screenOrientation":"竖版","gameSystems":["塔防"],"confidence":0.9}""")
        ).gameSchema
        val confirmation = RecognitionEngine.buildConfirmation("做一个塔防游戏", schema)
        assertEquals("2D", confirmation.visualDimension)
        assertEquals("竖版", confirmation.screenOrientation)

        val restored = IntentConfirmation.fromCardContent(IntentConfirmation.cardContent(confirmation))
        assertNotNull(restored)
        assertEquals("2D", restored!!.visualDimension)
        assertEquals("竖版", restored.screenOrientation)
    }

    @Test
    fun `识别结果携带本轮补丁供策划层判断重组范围`() {
        val result = RecognitionEngine.recognize("做一个塔防游戏，敌人按波次进攻", null)
        assertNotNull(result.appliedPatch)
        assertTrue(result.appliedPatch!!.gameSystems.contains("塔防"))
    }

    // ---------- 裸需求与生成上下文 ----------

    @Test
    fun `裸需求模式保留指令导向且不再反向否定对标游戏`() {
        val schema = RecognitionEngine.recognize("做一个塔防游戏，敌人按波次进攻", null).gameSchema
        val bare = PlanningEngine.finalize(schema, uncheckedModules = schema.gameSystems.toSet())
        assertTrue(bare.gameSystems.isEmpty())

        val ctx = GamePrompt.planContext(bare)
        assertFalse(ctx.contains("不要套用任何游戏模板"))
        assertTrue(ctx.contains("以用户指令与对标游戏锚定为准"))

        // 生成路径的知名游戏忠实度规则（纯指令驱动，不依赖对标字段）
        assertTrue(GamePrompt.KNOWN_GAME_FIDELITY_RULE.contains("我的世界"))
        assertTrue(GamePrompt.KNOWN_GAME_FIDELITY_RULE.contains("禁止替换成另一种玩法的通用小游戏"))

        // 可玩性清单按类型泛化
        val req = GamePrompt.playabilityRequirements()
        assertTrue(req.contains("不以"))
        assertTrue(req.contains("沙盒建造类"))
    }

    // ---------- 序列化 ----------

    @Test
    fun `Game Schema JSON 可持久化往返`() {
        val schema = RecognitionEngine.recognize("做一个塔防游戏，敌人按波次进攻", null).gameSchema
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(schema)
        // 旧会话的对标字段被忽略而不是报错
        val legacy = encoded.replace("\"confidence\"", "\"referenceGame\":\"塔防LIKE\",\"templateId\":\"td\",\"confidence\"")
        assertEquals(schema, json.decodeFromString<GameSchema>(legacy))
    }
}
