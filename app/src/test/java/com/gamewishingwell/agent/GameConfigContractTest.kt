package com.gamewishingwell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameConfigContractTest {

    private val contractHtml = """
        <!DOCTYPE html><html><head><script>
        window.GAME_CONFIG = {
          "playerSpeed": 130,
          "maxHp": 100,
          "labels": ["开始", "重开"],
          "hardMode": false
        };
        Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});
        </script></head><body></body></html>
    """.trimIndent()

    @Test
    fun `探测 GAME_CONFIG 赋值`() {
        assertTrue(GameConfigContract.hasConfigObject(contractHtml))
        assertTrue(GameConfigContract.hasConfigObject("var GAME_CONFIG = {};"))
        assertTrue(GameConfigContract.hasConfigObject("const GAME_CONFIG = {\n};"))
        // Object.assign 调用形与注释/字符串外的普通文本不算赋值
        assertFalse(GameConfigContract.hasConfigObject("Object.assign(window.GAME_CONFIG, {});"))
        assertFalse(GameConfigContract.hasConfigObject("<html>普通游戏</html>"))
        assertFalse(GameConfigContract.hasConfigObject(null))
    }

    @Test
    fun `提取对象字面量-嵌套与字符串内的花括号不干扰配对`() {
        val html = """
            <html><script>
            window.GAME_CONFIG = { "speed": 10, "ui": { "btn": "{不是结束}" }, "note": "} 也不是" };
            var after = { a: 1 };
            </script></html>
        """.trimIndent()
        val literal = GameConfigContract.extractConfigLiteral(html)
        assertNotNull(literal)
        assertTrue(literal!!.contains("\"ui\""))
        assertFalse(literal.contains("var after"))
        // 括号不闭合返回 null（防截断文件拖垮面板）
        assertNull(GameConfigContract.extractConfigLiteral("window.GAME_CONFIG = { \"a\": 1,"))
        assertNull(GameConfigContract.extractConfigLiteral("<html>无赋值</html>"))
    }

    @Test
    fun `规范化-注释尾逗号未引号键与单引号`() {
        val messy = """
            {
              // 行注释
              speed: 260, /* 块
              注释 */ 'name': '疾风',
              items: [1, 2, 3,],
              enabled: true,
            }
        """.trimIndent()
        val normalized = GameConfigContract.normalizeToJson(messy)!!
        val obj = GameConfigContract.extractDefaults(
            "window.GAME_CONFIG = $normalized;"
        )!!
        assertEquals(260.0, (obj["speed"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 0.0)
        assertEquals("疾风", (obj["name"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(3, (obj["items"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("true", (obj["enabled"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `从合并视图提取默认参数表`() {
        val defaults = GameConfigContract.extractDefaults(contractHtml)
        assertNotNull(defaults)
        assertEquals(4, defaults!!.size)
        assertEquals("130", (defaults["playerSpeed"] as kotlinx.serialization.json.JsonPrimitive).content)
        // 展示形态：pretty JSON 含全部键
        val pretty = GameConfigContract.prettyDefaults(contractHtml)!!
        assertTrue(pretty.contains("\"playerSpeed\""))
    }

    @Test
    fun `提取失败返回 null 不误产`() {
        // 值是函数/表达式：规范化后仍非合法 JSON（对象里出现 function 关键字）
        val html = "window.GAME_CONFIG = { speed: function(){ return 1; } };"
        assertNull(GameConfigContract.extractDefaults(html))
    }

    @Test
    fun `覆盖文件合法性只收 JSON 对象`() {
        assertTrue(GameConfigContract.isValidOverrideJson("""{"playerSpeed": 260}"""))
        assertTrue(GameConfigContract.isValidOverrideJson("{}"))
        assertFalse(GameConfigContract.isValidOverrideJson("[1,2]"))
        assertFalse(GameConfigContract.isValidOverrideJson("\"文本\""))
        assertFalse(GameConfigContract.isValidOverrideJson("{oops}"))
        assertFalse(GameConfigContract.isValidOverrideJson(""))
    }

    @Test
    fun `注释提取与默认值渲染`() {
        // 带注释样例验证键→行尾注释提取
        val cfg = """
            window.GAME_CONFIG = {
              "speed": 260,            // 移动速度
              "name": "疾风", // 名字
              "items": [1, 2],         // 道具表
              "noComment": true
            };
        """.trimIndent()
        val d = GameConfigContract.extractParamDocs(cfg)
        assertEquals("移动速度", d["speed"])
        assertEquals("名字", d["name"])
        assertEquals("道具表", d["items"])
        assertNull(d["noComment"])
        // 渲染：默认值 + 行尾注释（无注释键省略注释段）
        val defaults = GameConfigContract.extractDefaults(cfg)!!
        val rendered = GameConfigContract.renderDefaults(defaults, d)
        assertTrue(rendered.contains("\"speed\": 260    // 移动速度"))
        assertTrue(rendered.contains("\"noComment\": true"))
    }

    @Test
    fun `宽松值解析-数字布尔数组按JSON-其余按字符串`() {
        assertEquals("260", (GameConfigContract.parseValueOrString("260") as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("true", (GameConfigContract.parseValueOrString("true") as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("false", (GameConfigContract.parseValueOrString("false") as kotlinx.serialization.json.JsonPrimitive).content)
        // 数组/对象按 JSON 原样
        assertEquals(3, (GameConfigContract.parseValueOrString("[1, 2, 3]") as kotlinx.serialization.json.JsonArray).size)
        // 非法 JSON（颜色/含空格文本/带引号残缺）按字符串字面量
        assertEquals("#ffb347", (GameConfigContract.parseValueOrString("#ffb347") as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("疾 风", (GameConfigContract.parseValueOrString("疾 风") as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("\"", (GameConfigContract.parseValueOrString("\"") as kotlinx.serialization.json.JsonPrimitive).content)
        // 首尾空白剔除后再判定
        assertEquals("260", (GameConfigContract.parseValueOrString(" 260 ") as kotlinx.serialization.json.JsonPrimitive).content)
    }

    // ---------- 写入护栏支持（修复轮 config 维护） ----------

    @Test
    fun `提取单文件键集与值类型判定`() {
        val cfg = "window.GAME_CONFIG = { \"speed\": 1, \"note\": \"x\", \"arr\": [1,2] };\n" +
            "Object.assign(window.GAME_CONFIG, window.__wwConfigOverride || {});"
        assertEquals(setOf("speed", "note", "arr"), GameConfigContract.extractConfigKeys(cfg))
        // 无赋值 → null（不参与判定）
        assertNull(GameConfigContract.extractConfigKeys("var a = 1;"))
        // 函数值：字面量存在但非纯 JSON
        val fnCfg = "window.GAME_CONFIG = { speed: function(){ return 1; } };"
        assertEquals(false, GameConfigContract.configLiteralIsJsonCompatible(fnCfg))
        assertNull(GameConfigContract.extractConfigKeys(fnCfg))
        // 纯 JSON（含注释/尾逗号等可规范化写法）→ 兼容
        assertEquals(true, GameConfigContract.configLiteralIsJsonCompatible(cfg))
        assertNull(GameConfigContract.configLiteralIsJsonCompatible("var a = 1;"))
    }

    @Test
    fun `注释与字符串剥离-点访问不误报`() {
        val src = "var a = 1; // 经 CFG.speed 引用\n" +
            "/* 块注释 GAME_CONFIG.hp */ var s = \"文本 GAME_CONFIG.mp 不算\";\n" +
            "var real = GAME_CONFIG.speed;"
        val stripped = GameConfigContract.stripCommentsAndStrings(src)
        assertTrue(stripped.contains("real = GAME_CONFIG.speed"))
        assertFalse(stripped.contains("CFG.speed"))
        assertFalse(stripped.contains("文本"))
    }

    @Test
    fun `引用扫描-直引与别名且注释免疫`() {
        val files = mapOf(
            "js/config.js" to "window.GAME_CONFIG = { \"speed\": 1, \"hp\": 2 };\n" +
                "// 逻辑一律经 CFG.xxx 引用（不算引用）",
            "js/main.js" to "var CFG = window.GAME_CONFIG;\n" +
                "var v = CFG.speed + window.GAME_CONFIG.hp;\n" +
                "// CFG.ghost 注释不算\nvar s = \"CFG.stray 字符串不算\";"
        )
        val refs = GameConfigContract.collectConfigRefs(files)
        assertEquals(setOf("js/main.js"), refs["speed"])
        assertEquals(setOf("js/main.js"), refs["hp"])
        assertNull(refs["xxx"])   // 注释里的 CFG.xxx 不计
        assertNull(refs["ghost"]) // 注释里的不计
        assertNull(refs["stray"]) // 字符串里的不计
    }

    @Test
    fun `覆盖清单渲染-合法注入非法忽略`() {
        val note = GameConfigContract.renderOverrideNote("""{"playerSpeed": 260, "goblinHp": 50}""")!!
        assertTrue(note.contains("【玩家参数覆盖"))
        assertTrue(note.contains("playerSpeed = 260"))
        assertTrue(note.contains("goblinHp = 50"))
        assertTrue(note.contains("覆盖仍优先"))
        // 空/非法/无覆盖：null
        assertNull(GameConfigContract.renderOverrideNote(null))
        assertNull(GameConfigContract.renderOverrideNote("{}"))
        assertNull(GameConfigContract.renderOverrideNote("{oops"))
    }
}
