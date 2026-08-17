package com.gamewishingwell.agent

/**
 * 对标游戏模板的系统级实现细节库。
 *
 * 确认门和策划层共用同一份数据：
 * - 确认门用具体玩法向玩家解释“这个游戏里该系统会怎么做”；
 * - 策划层把同一组 methods / acceptanceBoundary 注入 DesignPlan 和 LLM prompt，
 *   因此玩家确认的内容就是后续生成代码的真实功能边界。
 *
 * 这里只描述模板内已确认存在的系统；模板未覆盖的系统回退到 [GameSystemCatalog]
 * 的通用实现方法，避免自由发明。
 */
data class TemplateSystemSpec(
    val methods: List<String>,
    val acceptanceBoundary: String,
    /** 覆盖通用系统矩阵的 P0/P1/P2 分层；例如黄金矿工的“道具”属于核心玩法。 */
    val layer: Int? = null
)

object TemplateSystemCatalog {

    private val specs: Map<String, Map<String, TemplateSystemSpec>> = mapOf(
        "whack_a_mole" to mapOf(
            "反应躲避" to TemplateSystemSpec(
                methods = listOf(
                    "3×3 洞位棋盘，地鼠按 0.8~1.6 秒随机从洞中冒出并停留 0.6~1.1 秒",
                    "手指点按命中地鼠得 10 分，地鼠立即缩回并播放命中反馈",
                    "空点或漏打累计失误，失误 3 次结束本局，可 restart() 重开"
                ),
                acceptanceBoundary = "地鼠只从洞位冒出，点中立即缩回并加分，空点/漏打有清晰失误惩罚"
            ),
            "战斗" to TemplateSystemSpec(
                methods = listOf(
                    "地鼠冒头窗口即攻击窗口，点按=命中攻击，不额外生成敌人",
                    "普通鼠 +10 分；黄金鼠 +50 分且停留更短；炸弹鼠点中扣 1 次失误"
                ),
                acceptanceBoundary = "按鼠种区分命中结果：普通鼠/黄金鼠加分正确，炸弹鼠惩罚明确"
            )
        ),
        "catch_fruit" to mapOf(
            "收集" to TemplateSystemSpec(
                methods = listOf(
                    "苹果/香蕉/西瓜三类水果从顶部随机位置下落，分值分别为 10/15/20",
                    "篮子跟随手指水平移动，水果与篮子矩形碰撞即拾取成功",
                    "炸弹与水果混落，接到炸弹扣除 1 点生命并清空 combo"
                ),
                acceptanceBoundary = "三类水果可被篮子接住并正确计分，接到炸弹有明确惩罚"
            ),
            "反应躲避" to TemplateSystemSpec(
                methods = listOf(
                    "下落速度随分数提升，每 100 分提高一档",
                    "连续接中 5 个水果触发 bonus 加分反馈",
                    "漏接水果累计失误，生命归零结束并显示本局得分"
                ),
                acceptanceBoundary = "难度随分数提升，连击/漏接反馈清晰"
            )
        ),
        "snake" to mapOf(
            "人物实体" to TemplateSystemSpec(
                methods = listOf(
                    "蛇身由方块节组成，初始 3 节，滑动或点按方向键四向转向",
                    "蛇头按固定 tick 前进，禁止 180° 直接反向",
                    "吃到食物后蛇尾增长 1 节，撞墙或撞到自身判定失败"
                ),
                acceptanceBoundary = "蛇可四向移动，碰墙/自身失败，吃食物后明显增长"
            ),
            "收集" to TemplateSystemSpec(
                methods = listOf(
                    "食物随机生成在空白格，场上始终只有 1 个食物",
                    "蛇头与食物格重合即拾取，加 10 分并立即生成下一个食物"
                ),
                acceptanceBoundary = "食物生成位置合法，拾取后计分并刷新正确"
            ),
            "属性等级" to TemplateSystemSpec(
                methods = listOf(
                    "每 50 分升 1 级，蛇移动 tick 随等级逐级加快",
                    "HUD 显示当前分数、等级与蛇身长度"
                ),
                acceptanceBoundary = "等级成长可见，且等级提升会直接加快游戏节奏"
            )
        ),
        "merge_2048" to mapOf(
            "合成" to TemplateSystemSpec(
                methods = listOf(
                    "4×4 数字方块棋盘，开局随机生成 2 个方块（数值 2 或 4）",
                    "上下左右滑动整盘移动，相同数字相撞合并为两数之和",
                    "每次有效移动后在空白格随机生成 1 个新方块（2 或 4）"
                ),
                acceptanceBoundary = "相同数字可合并，无效移动不生成新块，棋盘满且无法合并时失败"
            ),
            "商店经济" to TemplateSystemSpec(
                methods = listOf(
                    "合并得分按 1:1 转化为金币",
                    "商店可购买“回退一步”和“消除一个 2/4 方块”两种道具"
                ),
                acceptanceBoundary = "金币产出与购买扣费正确，余额不足时不可购买"
            )
        ),
        "tetris" to mapOf(
            "反应躲避" to TemplateSystemSpec(
                methods = listOf(
                    "7 种四格方块随机从顶部下落，左右滑动移动、点按旋转",
                    "方块落底固定，整行填满即消除并加分",
                    "方块堆叠到顶部判定失败"
                ),
                acceptanceBoundary = "方块可移动/旋转，满行消除与堆顶失败正确"
            ),
            "属性等级" to TemplateSystemSpec(
                methods = listOf(
                    "每消除 10 行升 1 级，下落速度随等级加快",
                    "HUD 显示消除行数、等级与得分"
                ),
                acceptanceBoundary = "等级与下落速度正相关且可见"
            )
        ),
        "brick_breaker" to mapOf(
            "物理" to TemplateSystemSpec(
                methods = listOf(
                    "小球与挡板/砖块做轴对齐碰撞反弹，反弹角由碰撞点相对挡板中心偏移决定",
                    "挡板跟随手指水平移动，小球从挡板中心发射",
                    "小球落到底部损失 1 条命并从挡板重新发射"
                ),
                acceptanceBoundary = "反弹方向可预期，小球掉落判定与命数正确"
            ),
            "战斗" to TemplateSystemSpec(
                methods = listOf(
                    "砖块按颜色有 1~3 点耐久，小球每碰撞一次扣 1 点耐久",
                    "全部砖块清除即过关，命数归零失败"
                ),
                acceptanceBoundary = "砖块耐久/扣血正确，清空砖块过关、命数归零失败"
            ),
            "关卡场景" to TemplateSystemSpec(
                methods = listOf(
                    "3 关不同砖块布局，清空当前布局后自动进入下一关",
                    "每关切换后重置小球与挡板位置，保留当前得分"
                ),
                acceptanceBoundary = "关卡递进且切换时小球/挡板状态正确重置"
            )
        ),
        "plane_shooter" to mapOf(
            "弹幕射击" to TemplateSystemSpec(
                methods = listOf(
                    "玩家飞机跟随手指移动，自动连续发射子弹",
                    "敌机按波次从顶部进入，周期性发射瞄准玩家方向的弹幕",
                    "子弹与敌机做矩形碰撞判定，敌机血量归零爆炸并加分"
                ),
                acceptanceBoundary = "玩家移动/自动射击可用，子弹命中与敌弹幕威胁正确"
            ),
            "战斗" to TemplateSystemSpec(
                methods = listOf(
                    "玩家 3 点生命，中弹扣 1 点并进入 1 秒无敌闪烁",
                    "普通敌机与关底 boss 均可被击败，生命归零失败"
                ),
                acceptanceBoundary = "受击/无敌/生命归零流程正确，boss 可被击败"
            ),
            "关卡场景" to TemplateSystemSpec(
                methods = listOf(
                    "3 个递进关卡，敌机数量与弹幕密度逐步提升",
                    "清空当前关敌机/boss 后进入下一关并保留得分"
                ),
                acceptanceBoundary = "3 关递进，波次强度与切换正确"
            )
        ),
        "endless_runner" to mapOf(
            "竞速" to TemplateSystemSpec(
                methods = listOf(
                    "角色自动向前奔跑，左右滑动切换 3 条跑道，上/下滑动跳跃/滑铲",
                    "跑动距离与金币共同计入总分，速度随距离逐步提升"
                ),
                acceptanceBoundary = "变道/跳跃/滑铲均可控，距离与金币计分正确"
            ),
            "平台跳跃" to TemplateSystemSpec(
                methods = listOf(
                    "跑道随机生成障碍与平台缺口，跳跃可越过障碍/缺口",
                    "碰撞障碍或落入缺口判定失败并显示本局得分"
                ),
                acceptanceBoundary = "障碍/缺口可被跳跃越过，碰撞失败判定可靠"
            ),
            "收集" to TemplateSystemSpec(
                methods = listOf(
                    "跑道随机生成金币，角色触碰即拾取",
                    "连续拾取金币触发 combo，combo 越高单枚金币得分越高"
                ),
                acceptanceBoundary = "金币拾取、combo 与计分正确"
            )
        ),
        "tower_defense" to mapOf(
            "塔防" to TemplateSystemSpec(
                methods = listOf(
                    "地图固定 3~5 个塔位，可建造箭塔/炮塔/冰塔三类防御塔",
                    "敌人沿固定路径按波次推进，每波数量与强度递增",
                    "漏怪按怪物剩余生命扣除基地生命，基地生命归零失败"
                ),
                acceptanceBoundary = "可建塔，敌人按路径/波次进攻，漏怪扣基地生命并最终判负"
            ),
            "战斗" to TemplateSystemSpec(
                methods = listOf(
                    "防御塔自动索敌攻击射程内敌人：箭塔高攻速单体、炮塔范围伤害、冰塔减速",
                    "敌人有生命值与移动速度，血量归零被击杀并奖励金币"
                ),
                acceptanceBoundary = "三类塔攻击差异可感知，敌人可被击杀且奖励正确"
            ),
            "AI策略" to TemplateSystemSpec(
                methods = listOf(
                    "敌人按预设路径点移动，不动态绕路",
                    "塔默认优先攻击最靠近基地的敌人"
                ),
                acceptanceBoundary = "敌人路径稳定，塔索敌优先攻击最靠近基地的敌人"
            ),
            "商店经济" to TemplateSystemSpec(
                methods = listOf(
                    "击杀/波次奖励金币，建塔与升级消耗金币",
                    "金币不足时按钮禁用，出售塔返还 60% 金币"
                ),
                acceptanceBoundary = "金币产出、建塔/升级/出售扣费正确，余额不足不可购买"
            )
        ),
        "match3" to mapOf(
            "合成" to TemplateSystemSpec(
                methods = listOf(
                    "7×7 棋盘，5 种颜色宝石随机填充且初始无三连",
                    "交换相邻宝石形成 3 个及以上同色连线即消除，上方宝石掉落补位",
                    "4 连生成横/竖爆炸宝石，5 连生成彩虹宝石"
                ),
                acceptanceBoundary = "三消、掉落补位与特殊宝石合成正确"
            ),
            "关卡场景" to TemplateSystemSpec(
                methods = listOf(
                    "3 个递进关卡，每关有目标分数与步数限制",
                    "步数耗尽且未达标失败，达标进入下一关"
                ),
                acceptanceBoundary = "关卡目标/步数限制/过关失败条件正确"
            )
        ),
        "gold_miner" to mapOf(
            "物理" to TemplateSystemSpec(
                methods = listOf(
                    "钩子绕顶部支点左右摆动，点击后沿当前角度伸出，碰到边界或抓取物后停止",
                    "钩子抓住目标后匀速收回，石头等重物回收速度显著变慢",
                    "回收完成才结算本次抓取，未命中则空钩直接收回"
                ),
                acceptanceBoundary = "钩子摆动/伸出/回收符合黄金矿工手感，轻重物回收速度差异明显"
            ),
            "道具" to TemplateSystemSpec(
                methods = listOf(
                    "可抓取物包含黄金、石头、炸弹：黄金分值高，石头体积大且重，炸弹抓取后倒计时爆炸并扣分",
                    "拾取判定：钩子前端与道具做圆形碰撞检测，钩子触碰道具即视为抓取成功",
                    "每类道具在关卡中按比例随机分布，抓取后从场景移除"
                ),
                acceptanceBoundary = "黄金/石头/炸弹均可被钩子触碰抓取，分值与爆炸惩罚正确",
                layer = 0
            ),
            "商店经济" to TemplateSystemSpec(
                methods = listOf(
                    "每关开始前/结束后用金币购买炸药、力量药水、幸运草等增益",
                    "炸药可炸掉当前钩中的石头，力量药水提升回收速度，幸运草提高高价值目标出现率",
                    "过关目标为累计金币达标，未达标则关卡失败"
                ),
                acceptanceBoundary = "金币、购买增益与过关目标结算正确"
            )
        ),
        "piano_tiles" to mapOf(
            "音乐节奏" to TemplateSystemSpec(
                methods = listOf(
                    "4 列黑色钢琴块按节拍从顶部下落，玩家在琴块到达判定线时点按对应列",
                    "点中黑色琴块加分并播放音阶反馈，按错/漏按立即失败",
                    "长条块需按住保持到结束"
                ),
                acceptanceBoundary = "琴块下落、点按判定与错漏失败即时准确"
            ),
            "反应躲避" to TemplateSystemSpec(
                methods = listOf(
                    "琴块下落速度随分数/曲速逐级提升",
                    "连续点中触发 combo，HUD 显示分数与 combo"
                ),
                acceptanceBoundary = "难度随进度提升，combo 与计分可见"
            )
        ),
        "maze" to mapOf(
            "解谜" to TemplateSystemSpec(
                methods = listOf(
                    "迷宫为网格地图，固定起点与终点，滑动四向逐格移动",
                    "墙壁阻挡移动，到达终点即过关并结算用时/步数"
                ),
                acceptanceBoundary = "迷宫路径有效，四向移动受墙约束，到终点判定正确"
            ),
            "关卡场景" to TemplateSystemSpec(
                methods = listOf(
                    "3 个递进迷宫，尺寸与路径复杂度逐步提升",
                    "每关切换后重置玩家位置与计时"
                ),
                acceptanceBoundary = "3 个迷宫递进且切换重置正确"
            )
        ),
        "racing" to mapOf(
            "竞速" to TemplateSystemSpec(
                methods = listOf(
                    "车辆在 3 条车道自动前进，左右滑动变道，点按加速",
                    "跑完 3 圈或规定距离结算名次，碰撞对手减速"
                ),
                acceptanceBoundary = "变道/加速可操控，圈数/距离与名次结算正确"
            ),
            "AI策略" to TemplateSystemSpec(
                methods = listOf(
                    "对手车辆按预设路线与速度自动驾驶，不会主动攻击玩家",
                    "3 档难度控制对手数量与速度"
                ),
                acceptanceBoundary = "对手 AI 行为稳定，难度档位差异明显"
            )
        ),
        "board_game" to mapOf(
            "AI策略" to TemplateSystemSpec(
                methods = listOf(
                    "棋盘为 3×3 井字棋或 5×5 五子棋，玩家先手",
                    "AI 采用 1 步贪心评估：优先成五/成三，其次堵截玩家，否则选择中心或随机空位"
                ),
                acceptanceBoundary = "AI 会正常落子，且能识别并堵截玩家必胜点"
            ),
            "属性等级" to TemplateSystemSpec(
                methods = listOf(
                    "3 档 AI 难度：简单随机落子、普通贪心、困难多步评估",
                    "难度越高 AI 评分搜索越深，HUD 显示先手与胜负统计"
                ),
                acceptanceBoundary = "三档难度行为差异明显，胜负判定正确"
            )
        )
    )

    fun resolve(templateId: String?, system: String): TemplateSystemSpec? =
        templateId?.let { id -> specs[id]?.get(system) }
}
