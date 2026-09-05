package com.gamewishingwell.agent

/**
 * 纯文本特征抽取引擎（正则部分，不依赖网络）。
 *
 * 历史说明：这里曾是旧三意图管线（IntentSchema/IntentSchemaValidator/对标模板匹配/
 * 确认门摘要）的所在地——对标机制删除后旧管线整体移除，仅保留新识别层
 * （RecognitionEngine）仍在使用的纯函数集合。游戏特性与系统只从用户纯文本
 * 提取，"对标游戏"的理解完全由后续 Agent Loop 的 LLM 按指令原话自行完成。
 */
object IntentEngine {

    /** 新意图层便捷入口：dev/chat 二分类（本地正则）。 */
    fun infer(userText: String): IntentDecision = IntentLayer.inferLocally(userText)

    /** 供策划层判断修改旧游戏时是否需要继承上一版的画面维度。 */
    fun explicitlySpecifiesDimension(text: String): Boolean = explicitDimension(text) != null

    /** 供策划层判断修改旧游戏时是否需要继承上一版的画面方向。 */
    fun explicitlySpecifiesOrientation(text: String): Boolean = explicitOrientation(text) != null

    fun explicitDimension(text: String): String? = when {
        Regex("""2\.5\s*d|伪3d|斜45|2.5D""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> GameSchema.DIMENSION_2_5D
        Regex("""(?<!2\.5)3d|三维|立体""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> GameSchema.DIMENSION_3D
        Regex("""(?<![A-Za-z0-9])2d(?![A-Za-z0-9])|二维|平面""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> GameSchema.DIMENSION_2D
        else -> null
    }

    fun explicitOrientation(text: String): String? = when {
        Regex("横板|横版|横屏|landscape", RegexOption.IGNORE_CASE).containsMatchIn(text) -> GameSchema.ORIENTATION_LANDSCAPE
        Regex("竖版|竖屏|portrait|竖", RegexOption.IGNORE_CASE).containsMatchIn(text) -> GameSchema.ORIENTATION_PORTRAIT
        else -> null
    }

    fun negatedSystems(text: String): Set<String> =
        Regex(
            "(?:不要|别要|去掉|删除|移除|取消|砍掉|去除|别加|不加|无需|不需要|不用)[^。.!！?？;；,，、\n]{0,20}",
            RegexOption.IGNORE_CASE
        )
            .findAll(text)
            .flatMap { GameSystemCatalog.extract(it.value) }
            .toSet()

    fun reAddSystems(text: String): Set<String> =
        Regex(
            "(?:还是要|要保留|保留|恢复|加回|重新加|要加上|再加上|需要加上|改成要)[^。.!！?？;；,，、\n]{0,20}",
            RegexOption.IGNORE_CASE
        )
            .findAll(text)
            .flatMap { GameSystemCatalog.extract(it.value) }
            .toSet()
}
