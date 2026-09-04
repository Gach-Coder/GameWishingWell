package com.gamewishingwell.agent

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecognitionTest {

    @Test
    fun `意图层输出 dev chat 两个意图`() {
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("做一个打地鼠游戏").intent)
        assertEquals(IntentDecision.INTENT_CHAT, IntentLayer.inferLocally("你好").intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("加个连击计分").intent)
        // 修复类词汇不再参与路由：文本报告的异常统一按 dev 走正常管线，
        // 快车道由会话状态（运行时错误签名）推导，不靠猜文本。
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("游戏报错了，点开始没反应，修复一下").intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("修复bug").intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("闪退了帮我修修").intent)
        // 制作新游戏的指令优先于修复词：“修汽车”是游戏内容，不是修 bug
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("做一个修汽车的装修游戏").intent)
        assertTrue(IntentLayer.inferLocally("修复bug").isDevelop)
        assertFalse(IntentLayer.inferLocally("你好").isDevelop)
        // 短问句陷阱：开发诉求不得被疑问前缀误判成 chat 并以高置信度短路
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("能不能简单一点").intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.inferLocally("再难一点").intent)

        val parsed = IntentLayer.parseLiteLlmReply("""{"intent":"chat","confidence":0.9,"reason":"问候"}""")
        assertEquals(IntentDecision.INTENT_CHAT, parsed?.intent)
        assertTrue(parsed?.isChat == true)
        // 历史标签归一：旧三分类的 fix_bug / 旧二分类的 develop 都表示“要动游戏” → dev
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.parseLiteLlmReply("""{"intent":"fix_bug","confidence":0.9}""")?.intent)
        assertEquals(IntentDecision.INTENT_DEV, IntentLayer.parseLiteLlmReply("""{"intent":"develop","confidence":0.9}""")?.intent)
        assertNull(IntentLayer.parseLiteLlmReply("""{"intent":"外星意图","confidence":0.9}"""))
        // LLM 自报低置信度视为不可信，回落本地默认（dev）
        assertNull(IntentLayer.parseLiteLlmReply("""{"intent":"chat","confidence":0.3}"""))
    }

    @Test
    fun `首次识别创建会话级 Game Schema 并与下游共享`() {
        val userText = "做一个2.5D横板跑酷游戏，要有关卡、收集金币和AI敌人"
        val result = RecognitionEngine.recognize(
            userText,
            existingSchema = null
        )

        assertTrue(result.isNewGame)
        assertFalse(result.emptyEntities)
        val schema = result.gameSchema
        assertEquals("2.5D", schema.visualDimension)
        assertEquals("横板", schema.screenOrientation)
        assertTrue(schema.gameSystems.containsAll(listOf("关卡场景", "收集", "AI策略")))
        assertEquals("endless_runner", schema.templateId)
        assertEquals(userText, schema.firstUserRequest)
        assertEquals(userText, schema.lastUserRequest)
        assertEquals(schema, GameSchemaValidator.validate(schema))

        // 下游策划层直接消费同一份 Game Schema JSON
        val plan = PlanningEngine.build(schema)
        assertEquals(schema.gameSystems, plan.gameSystems)
        assertTrue(plan.implementations.isNotEmpty())
    }

    @Test
    fun `非空 Game Schema 默认锚定同一个游戏并继续修改`() {
        val first = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val modified = RecognitionEngine.recognize("不要道具", first)

        assertEquals("不要道具", modified.gameSchema.lastUserRequest)
        assertFalse(modified.isNewGame)
        assertFalse(modified.emptyEntities)
        assertTrue("道具" !in modified.gameSchema.gameSystems)
        assertTrue("道具" in modified.gameSchema.excludedSystems)
        assertEquals("gold_miner", modified.gameSchema.templateId)
        assertTrue("物理" in modified.gameSchema.gameSystems)
    }

    @Test
    fun `修改轮换成新对标游戏时会切换模板系统`() {
        val first = RecognitionEngine.recognize("做一个塔防游戏", null).gameSchema
        val switched = RecognitionEngine.recognize("换成2048", first)

        assertFalse(switched.isNewGame)
        assertEquals("merge_2048", switched.gameSchema.templateId)
        assertTrue("合成" in switched.gameSchema.gameSystems)
        assertTrue("塔防" !in switched.gameSchema.gameSystems)
    }

    @Test
    fun `识别层抽取不到实体时回退为普通聊天`() {
        assertTrue(RecognitionEngine.recognize("你好", null).emptyEntities)

        val anchored = RecognitionEngine.recognize("做一个打地鼠游戏", null).gameSchema
        val chat = RecognitionEngine.recognize("谢谢", anchored)
        assertTrue(chat.emptyEntities)
        assertEquals(anchored, chat.gameSchema)
    }

    @Test
    fun `无实体但明确要求做游戏时仍创建默认 Game Schema`() {
        val result = RecognitionEngine.recognize("给我做一个游戏", null)
        assertFalse(result.emptyEntities)
        assertTrue(result.gameSchema.gameSystems.isNotEmpty())
    }

    @Test
    fun `局部修改词在已锚定游戏上不会丢失原有系统`() {
        val first = RecognitionEngine.recognize("做一个塔防游戏", null).gameSchema
        val modified = RecognitionEngine.recognize("加个连击计分", first)
        assertFalse(modified.emptyEntities)
        assertEquals(first.gameSystems, modified.gameSchema.gameSystems)
        assertTrue("塔防" in modified.gameSchema.gameSystems)
    }

    @Test
    fun `确认门用策划层 LLM 的玩家视角阐述且不泄露技术词`() {
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val draft = PlanningEngine.build(schema)
        val confirmation = RecognitionEngine.buildConfirmation(
            "做一个黄金矿工游戏",
            schema,
            draft,
            isNewGame = true
        )
        assertTrue(confirmation.isNewGame)
        assertEquals(schema, confirmation.gameSchema)
        assertNull(confirmation.intent)
        val item = confirmation.systemExplanations.first { it.startsWith("道具：") }
        assertTrue(item.contains("黄金"))
        assertTrue(item.contains("石头"))
        assertTrue(item.contains("炸弹"))
        assertTrue(item.contains("验收边界"))
        assertFalse(item.contains("Canvas"))
        assertFalse(item.contains("requestAnimationFrame"))
    }

    @Test
    fun `对标模板无覆盖填补画面方向空位且不覆盖用户明确值`() {
        // 赛车模板建议横板：用户未明确指定时由模板填补空位
        val racing = RecognitionEngine.recognize("做一个赛车游戏", null).gameSchema
        assertEquals("racing", racing.templateId)
        assertEquals("横板", racing.screenOrientation)

        // 用户明确“竖版”时优先于模板建议值
        val explicit = RecognitionEngine.recognize("做一个竖版的赛车游戏", null).gameSchema
        assertEquals("racing", explicit.templateId)
        assertEquals("竖版", explicit.screenOrientation)

        // 修改轮换上新模板同样只填补空位
        val first = RecognitionEngine.recognize("做一个竖版塔防游戏", null).gameSchema
        assertEquals("竖版", first.screenOrientation)
        val switched = RecognitionEngine.recognize("换成跑酷", first).gameSchema
        assertEquals("endless_runner", switched.templateId)
        assertEquals("横板", switched.screenOrientation)
    }

    @Test
    fun `识别结果携带本轮补丁供策划层判断重组范围`() {
        val first = RecognitionEngine.recognize("做一个黄金矿工游戏", null)
        assertTrue(first.appliedPatch!!.hasEntities)

        val second = RecognitionEngine.recognize("加一个技能系统，大招要炫酷", first.gameSchema)
        assertEquals(listOf("技能"), second.appliedPatch!!.gameSystems)
    }

    @Test
    fun `确认门卡片提供module勾选数据且可持久化往返`() {
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val draft = PlanningEngine.build(schema)
        val confirmation = RecognitionEngine.buildConfirmation(
            "做一个黄金矿工游戏",
            schema,
            draft,
            isNewGame = true,
            revised = false
        )

        // module_list 与策划草案一致，卡片据此渲染勾选框
        assertEquals(draft.gameSystems, confirmation.modules.map { it.module })
        assertTrue(confirmation.modules.all { it.acceptanceBoundary.isNotBlank() })
        assertTrue(confirmation.modules.all { it.implementation.isNotBlank() })
        assertTrue(confirmation.modules.none { it.implementation.contains("Canvas") })

        // 卡片消息编码剥离 draftPlan/gameSchema，回显数据可完整往返
        val decoded = IntentConfirmation.fromCardContent(IntentConfirmation.cardContent(confirmation))
        assertEquals(confirmation.modules, decoded!!.modules)
        assertEquals(confirmation.summary, decoded.summary)
        assertEquals(confirmation.excludedSystems, decoded.excludedSystems)
        assertNull(decoded.draftPlan)
        assertNull(decoded.gameSchema)
        assertEquals(confirmation.copy(intent = null, gameSchema = null, draftPlan = null), decoded)

        // 确认时勾选状态锁进卡片消息：历史卡重显保持取消勾选，不会复位成全选
        val locked = confirmation.copy(uncheckedModules = listOf("物理"))
        val decodedLocked = IntentConfirmation.fromCardContent(IntentConfirmation.cardContent(locked))
        assertEquals(listOf("物理"), decodedLocked!!.uncheckedModules)
        assertTrue(decodedLocked.modules.isNotEmpty())
    }

    @Test
    fun `Game Schema JSON 可持久化往返`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val schema = RecognitionEngine.recognize("做一个黄金矿工游戏", null).gameSchema
        val encoded = json.encodeToString(schema)
        val decoded = json.decodeFromString<GameSchema>(encoded)
        assertEquals(schema, decoded)
    }

    @Test
    fun `方向与维度的同义写法归一化为枚举规范值`() {
        // Lite LLM 常回 "横屏/竖屏/landscape/portrait" 等口语值，必须归一化，
        // 否则白名单会把 "横屏" 静默映射成默认竖版（用户要横板被翻成竖版）。
        assertEquals(GameSchema.ORIENTATION_LANDSCAPE, GameSchema.normalizeOrientation("横屏"))
        assertEquals(GameSchema.ORIENTATION_LANDSCAPE, GameSchema.normalizeOrientation("横版"))
        assertEquals(GameSchema.ORIENTATION_LANDSCAPE, GameSchema.normalizeOrientation("Landscape"))
        assertEquals(GameSchema.ORIENTATION_PORTRAIT, GameSchema.normalizeOrientation("竖屏"))
        assertEquals(GameSchema.ORIENTATION_PORTRAIT, GameSchema.normalizeOrientation("portrait"))
        assertEquals("横板", GameSchema.normalizeOrientation("横板"))
        assertNull(GameSchema.normalizeOrientation("外星方向"))
        assertNull(GameSchema.normalizeOrientation(null))
        assertNull(GameSchema.normalizeOrientation(""))

        assertEquals(GameSchema.DIMENSION_2D, GameSchema.normalizeDimension("2d"))
        assertEquals(GameSchema.DIMENSION_2D, GameSchema.normalizeDimension("二维"))
        assertEquals(GameSchema.DIMENSION_2_5D, GameSchema.normalizeDimension("伪3d"))
        assertEquals(GameSchema.DIMENSION_3D, GameSchema.normalizeDimension("三维"))
        assertNull(GameSchema.normalizeDimension("4D"))
        assertNull(GameSchema.normalizeDimension(null))
    }

    @Test
    fun `validate 把同义方向值归一化而不是翻成默认竖版`() {
        val landscape = GameSchema(screenOrientation = "横屏")
        assertEquals(GameSchema.ORIENTATION_LANDSCAPE, GameSchemaValidator.validate(landscape).screenOrientation)

        val portrait = GameSchema(screenOrientation = "竖屏")
        assertEquals(GameSchema.ORIENTATION_PORTRAIT, GameSchemaValidator.validate(portrait).screenOrientation)

        // 非法值仍回落默认（2D/竖版），保持旧行为
        assertEquals(GameSchema.DIMENSION_2D, GameSchemaValidator.validate(GameSchema(visualDimension = "4D")).visualDimension)
        assertEquals(GameSchema.ORIENTATION_PORTRAIT, GameSchemaValidator.validate(GameSchema(screenOrientation = "方形")).screenOrientation)
    }

    @Test
    fun `识别层 Lite LLM 回复中的同义方向值参与合并且不翻转用户明确值`() {
        // Lite LLM 回 "横屏"：解析出口归一化为 "横板"
        val landscapePatch = GameSchemaValidator.parseLiteLlmReply(
            """{"visualDimension":"2D","screenOrientation":"横屏","gameSystems":["平台跳跃"],"confidence":0.8}"""
        )
        assertEquals(GameSchema.ORIENTATION_LANDSCAPE, landscapePatch?.screenOrientation)

        // Lite LLM 缺省填 "竖屏"、正则明确抽出横板时：合并结果以正则为准
        val regex = RecognitionEngine.extractLocal("制作一个横板的超级玛丽")
        assertEquals(GameSchema.ORIENTATION_LANDSCAPE, regex.screenOrientation)
        val portraitPatch = GameSchemaValidator.parseLiteLlmReply(
            """{"visualDimension":"2D","screenOrientation":"竖屏","gameSystems":[],"confidence":0.8}"""
        )
        val merged = RecognitionEngine.merge(regex, portraitPatch)
        assertEquals(GameSchema.ORIENTATION_LANDSCAPE, merged.screenOrientation)

        // 完整识别链路：横板需求 + Lite LLM 缺省竖屏 → 最终 Schema 仍是横板
        val recognized = RecognitionEngine.recognize("制作一个横板的超级玛丽", null, portraitPatch)
        assertEquals(GameSchema.ORIENTATION_LANDSCAPE, recognized.gameSchema.screenOrientation)
        // 策划层派生的验收清单与提示词携带同一方向，横板游戏沙箱按横屏视口运行
        val plan = PlanningEngine.build(recognized.gameSchema)
        assertTrue(plan.templateClass.contains("横板"))
        assertTrue(plan.acceptanceChecklist.any { it.contains("横板") })
        assertTrue(plan.excludedApproaches.any { it.contains("竖版") })
    }
}
