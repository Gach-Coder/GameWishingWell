package com.gamewishingwell.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gamewishingwell.agent.GameSession
import com.gamewishingwell.agent.IntentConfirmation
import com.gamewishingwell.agent.QualityTier
import com.gamewishingwell.agent.ResumeCardPayload
import com.gamewishingwell.data.ChatMessage
import com.gamewishingwell.ui.rememberContainer
import com.gamewishingwell.ui.viewmodels.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    gameId: Long?,
    resumeDraft: Boolean = false,
    /** 立即游玩：参数=是否对齐保存区（true=与首页同源加载；false=编辑区预览最新修改）。 */
    onPlay: (alignedToSavedArea: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onSaved: () -> Unit
) {
    val container = rememberContainer()
    val mode = gameId?.let { "game-$it" } ?: if (resumeDraft) "draft" else "new"
    val vm: ChatViewModel = viewModel(
        key = "chat-$mode",
        factory = viewModelFactory { initializer { ChatViewModel(container.agentHub, gameId, resumeDraft) } }
    )
    val session by vm.session.collectAsState()
    val repoSettings by container.settingsRepository.settings.collectAsState()
    val context = LocalContext.current

    // 游戏制作成功后，输入框提示语切换为“继续改进”语义
    val inputPlaceholder = when {
        session.pendingConfirmation != null -> "如与预期不符，直接在这里输入修改内容…"
        session.currentHtml != null -> "你想如何改进你的游戏..."
        else -> "描述你想玩的游戏，例如：做一个接水果的小游戏"
    }

    var input by remember { mutableStateOf("") }
    // 首次保存（未命名草稿）的命名框；已命名游戏的保存不经此框直接覆盖。
    var showSaveDialog by remember { mutableStateOf(false) }
    // 撤销确认：丢弃未保存的修改不可恢复，须二次确认。
    var showUndoDialog by remember { mutableStateOf(false) }
    // 撤销目标版号（保存点冻结的 deliveredVersion）：弹窗打开时异步装载，
    // 显示"将回到第 N 版"；无保存点/旧格式保存点为 null（不展示版号）。
    var undoTargetVersion by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(showUndoDialog) {
        if (showUndoDialog) vm.loadUndoTargetVersion { undoTargetVersion = it }
    }
    // 确认卡质量档位的屏幕级持有：修正回合重建卡片时不丢失用户已选择的档位；
    // 新的首张确认卡（非重组）出现时清空，避免跨局残留。
    var pendingTier by remember { mutableStateOf<String?>(null) }
    // 新契约：首次对话在输入框周围直接确定游戏基本信息——维度/方向（"自动"=
    // 跟随识别与 LLM 推导）与质量档位（默认均衡）；随发送消息一并生效。
    var formDimension by remember { mutableStateOf<String?>(null) }
    var formOrientation by remember { mutableStateOf<String?>(null) }
    var formTier by remember { mutableStateOf(QualityTier.BALANCED) }
    val listState = rememberLazyListState()

    // 列表总条目数 = 消息（含确认卡） + 生成中占位 + 错误卡片占位
    val listItemCount = session.messages.size +
        (if (session.isGenerating) 1 else 0) +
        (if (session.error != null) 1 else 0)

    // 使用 reverseLayout 模拟微信消息流：index 0 固定在列表底部，
    // 新消息/生成状态变化时滚到 0，让最新气泡始终贴着输入区。
    LaunchedEffect(listItemCount) {
        if (listItemCount > 0) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (gameId != null) "编辑游戏" else if (resumeDraft) "继续创作" else "创作") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        // 外层 Scaffold 已处理系统栏与底部导航，内层内容区不需要再次叠加系统栏 inset。
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        // imePadding 作用在“消息区 + 操作区 + 输入区”整体上：
        // 键盘展开时三者一起向上移动，输入框始终保持在键盘上方。
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LaunchedEffect(session.pendingConfirmation) {
                if (session.pendingConfirmation?.revised == false) pendingTier = null
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                reverseLayout = true
            ) {
                // reverseLayout 下先声明的 item 在底部；错误/生成中状态离输入框最近。
                val error = session.error
                if (error != null) {
                    item(key = "error") {
                        ErrorCard(
                            message = error,
                            onRetry = { vm.regenerate() },
                            onSettings = onOpenSettings
                        )
                    }
                }
                if (session.isGenerating) {
                    item(key = "typing") {
                        TypingBubble(stage = session.agentStage, streamPreview = session.streamPreview)
                    }
                }
                // 确认卡随聊天流保留；仅最后一张与 pendingConfirmation 匹配的卡片
                // 可交互（生成中临时禁用），历史卡片永久只读回显（勾选框与按钮 disabled）。
                val pendingKey = session.pendingConfirmation
                    ?.let { IntentConfirmation.cardContent(it) }
                val enabledCardIndex = if (pendingKey == null) -1 else session.messages.indexOfLast {
                    it.isConfirmCard && it.content == pendingKey
                }
                val lastIndex = session.messages.lastIndex
                items(
                    count = session.messages.size,
                    key = { reversedIndex -> lastIndex - reversedIndex }
                ) { reversedIndex ->
                    val index = lastIndex - reversedIndex
                    val msg = session.messages[index]
                    if (msg.isResumeCard) {
                        // 续跑卡片：停止时追加的「中断点 + 继续按钮」消息；按钮仅在
                        // 会话仍持有对应的可续跑状态时可用（历史卡片只读回显）
                        val payload = ResumeCardPayload.fromCardContent(msg.content)
                        if (payload != null) {
                            ResumeTurnCard(
                                payload = payload,
                                active = !session.isGenerating &&
                                    session.resumableTurn?.instruction == payload.instruction,
                                onResume = { vm.resumeInterruptedTurn() }
                            )
                        } else {
                            MessageBubble(msg)
                        }
                    } else if (msg.isConfirmCard) {
                        val card = IntentConfirmation.fromCardContent(msg.content)
                        if (card != null) {
                            IntentConfirmationCard(
                                confirmation = card,
                                enabled = index == enabledCardIndex && !session.isGenerating,
                                tier = if (index == enabledCardIndex) pendingTier ?: card.qualityTier else card.qualityTier,
                                onTierChange = { pendingTier = it },
                                onConfirm = { unchecked, tier, orientation, dimension ->
                                    vm.confirmIntent(unchecked, tier, orientation, dimension)
                                }
                            )
                        } else {
                            MessageBubble(msg)
                        }
                    } else {
                        MessageBubble(msg)
                    }
                }
            }
            if (session.currentHtml != null && !session.isGenerating && session.error == null && session.pendingConfirmation == null) {
                // 两入口对齐判据：已入库 + 无未保存新版本 + 无停止中断的未交付修改
                // ＝保存区与编辑区一致，立即游玩直连运行区（与首页同源：同 HTML/参数/存档）
                val playAligned = gameId != null && !session.unsavedVersion && session.resumableTurn == null
                ActionBar(
                    lastWarning = session.lastWarning,
                    // 撤销＝保存的逆操作（保存区覆盖编辑区+上下文回滚），只对已保存游戏的编辑会话有意义
                    canUndo = gameId != null,
                    onPlay = { onPlay(playAligned) },
                    // 保存按钮：草稿（首存命名）或关闭了交付自动保存的用户才需要；
                    // 已入库 + 自动保存开启时每次交付即固化，隐藏冗余入口
                    showSave = gameId == null || !repoSettings.autoSaveOnDelivery,
                    // 仅首次保存询问命名（未命名草稿）；已命名游戏直接覆盖保持原名，改名在首页。
                    onSave = {
                        vm.saveGame(
                            onNeedName = { showSaveDialog = true },
                            onDone = { ok ->
                                if (ok) {
                                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                    onSaved()
                                } else {
                                    Toast.makeText(context, "保存失败，请先生成一个游戏", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    },
                    onUndo = { showUndoDialog = true }
                )
            }
            // 新契约：首次对话（还没有任何用户消息）在输入框上方常驻形态/档位选择，
            // 随发送消息一并确定游戏基本信息；发送后收起。
            val firstTurn = session.messages.none { it.isUser } &&
                session.pendingConfirmation == null && !session.isGenerating
            if (firstTurn) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Text(
                        "游戏基本信息（随发送生效，可留“自动”由 AI 推断）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf<String?>(null) + listOf("2D", "2.5D", "3D")).forEach { d ->
                            FilterChip(
                                selected = formDimension == d,
                                onClick = { formDimension = d },
                                label = { Text(d ?: "自动") }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf<String?>(null) + listOf("横板", "竖版")).forEach { o ->
                            FilterChip(
                                selected = formOrientation == o,
                                onClick = { formOrientation = o },
                                label = { Text(o ?: "自动") }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QualityTier.ALL.forEach { t ->
                            FilterChip(
                                selected = formTier == t,
                                onClick = { formTier = t },
                                label = { Text(QualityTier.label(t)) }
                            )
                        }
                    }
                }
            }
            // 停止键动态计时：Agent 生成中每秒刷新本轮已运行时长（发送/确认/修复/再试一次起算）。
            var elapsedMs by remember { mutableStateOf(0L) }
            LaunchedEffect(session.isGenerating) {
                if (session.isGenerating) {
                    while (true) {
                        elapsedMs = vm.turnElapsedMs()
                        delay(1_000)
                    }
                }
            }
            InputRow(
                value = input,
                enabled = !session.isGenerating,
                isGenerating = session.isGenerating,
                elapsedText = formatElapsedCompact(elapsedMs),
                placeholder = inputPlaceholder,
                onValueChange = { input = it },
                onSend = {
                    // 确认卡待确认期间发送文本＝修正：携带当前质量档位，重建卡不丢失用户选择；
                    // 首次发送携带输入区形态/档位选择（新契约的基本信息前置确定）。
                    vm.send(
                        input.trim(),
                        session.pendingConfirmation?.let { pendingTier } ?: formTier.takeIf { firstTurn },
                        formDimension.takeIf { firstTurn },
                        formOrientation.takeIf { firstTurn }
                    )
                    input = ""
                },
                onStop = { vm.stopGeneration() }
            )
        }
    }

    if (showSaveDialog) {
        SaveGameDialog(
            defaultTitle = defaultTitle(session),
            onDismiss = { showSaveDialog = false },
            onConfirm = { title ->
                showSaveDialog = false
                vm.saveAs(title) { meta ->
                    if (meta != null) {
                        Toast.makeText(context, "已保存到游戏库", Toast.LENGTH_SHORT).show()
                        onSaved()
                    } else {
                        Toast.makeText(context, "保存失败，请先生成一个游戏", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showUndoDialog) {
        // 版号后缀：保存点带版号时明确"回到第 N 版"，与交付消息的「第 N 版」对上号
        val verSuffix = undoTargetVersion?.let { "（第 $it 版）" } ?: ""
        AlertDialog(
            onDismissRequest = { showUndoDialog = false },
            title = { Text(if (repoSettings.autoSaveOnDelivery) "撤销到上一版本$verSuffix" else "撤销未保存的修改") },
            text = {
                Text(
                    if (repoSettings.autoSaveOnDelivery) {
                        "已开启「交付后自动保存」：保存点在每个成功回合后刷新，本操作将回到上一交付版本$verSuffix（上一个成功回合），其后的修改与对话记录会被丢弃。此操作不可恢复。"
                    } else {
                        "保存区（上次保存的版本$verSuffix）将覆盖编辑区，本次保存之后的修改与对话记录会被丢弃。此操作不可恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUndoDialog = false
                    vm.undoToSaved { ok ->
                        Toast.makeText(
                            context,
                            if (ok) "已撤销到上次保存的版本$verSuffix" else "当前没有可撤销的修改",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text("撤销") }
            },
            dismissButton = {
                TextButton(onClick = { showUndoDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            // 气泡文本可选中复制：长按起选、拖动扩选，系统选择工具栏提供"复制"。
            // 逐气泡独立 SelectionContainer——跨懒加载条目的选择本身不可行，
            // 单气泡内的选择与列表滚动互不干扰。
            SelectionContainer {
                Text(msg.content, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun TypingBubble(stage: String, streamPreview: String? = null) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            // 前端 LLM SSE 不显示源代码文本，只阶段显示 Agent 状态。
            Text(
                stage.ifBlank { "正在处理，请稍候…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 阶段条下方的流预览（临时动态"打字机"）：只在 SSE 接收期间
            // 非空（Agent 侧节流写入、流结束即清空）；兼容回环的整 HTML 流不回显。
            // 6 行：推理模型的思考流是工具模式多数轮的唯一可见产物，3 行太碎。
            if (!streamPreview.isNullOrBlank()) {
                Text(
                    streamPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun IntentConfirmationCard(
    confirmation: IntentConfirmation,
    enabled: Boolean,
    tier: String,
    onTierChange: (String) -> Unit,
    onConfirm: (unchecked: Set<String>, tier: String, orientation: String, dimension: String) -> Unit
) {
    // 勾选状态默认全选；确认时锁定进卡片消息（uncheckedModules），历史卡重显不复位。
    // 质量档位 tier 由调用方（ChatScreen）持有：修正回合重建卡片不丢失用户已选择的档位。
    val checkedModules = remember(confirmation) {
        mutableStateMapOf<String, Boolean>().apply {
            confirmation.modules.forEach { put(it.module, it.module !in confirmation.uncheckedModules) }
        }
    }
    // 画面形态选择器（方向/维度一票否决下游，识别错了玩家在卡上直接改）：
    // 初值取卡片回显字段（旧卡空串时回退 schema 值）；确认时随 onConfirm 回传。
    val schema = confirmation.schema
    var selectedOrientation by remember(confirmation) {
        mutableStateOf(confirmation.screenOrientation.ifBlank { schema.screenOrientation })
    }
    var selectedDimension by remember(confirmation) {
        mutableStateOf(confirmation.visualDimension.ifBlank { schema.visualDimension })
    }
    val selectedTier = QualityTier.normalize(tier)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (confirmation.revised) "已按你的补充重新整理，请再次确认" else "确认游戏方案",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.size(4.dp))
            Text(
                confirmation.summary,
                style = MaterialTheme.typography.bodySmall
            )
            // 画面形态：识别结果可就地修正（错误类型 ↔ 修正入口对位）。
            Spacer(Modifier.size(6.dp))
            Text(
                "画面形态（识别不符可直接点选修改）：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("2D", "2.5D", "3D").forEach { d ->
                    FilterChip(
                        selected = selectedDimension == d,
                        onClick = { if (enabled) selectedDimension = d },
                        enabled = enabled,
                        label = { Text(d) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("横板", "竖版").forEach { o ->
                    FilterChip(
                        selected = selectedOrientation == o,
                        onClick = { if (enabled) selectedOrientation = o },
                        enabled = enabled,
                        label = { Text(o) }
                    )
                }
            }
            if (confirmation.modules.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    "游戏系统（勾选＝实现，取消＝不实现，只聊玩法不涉及技术实现）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
                confirmation.modules.forEach { module ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                    ) {
                        Checkbox(
                            checked = checkedModules[module.module] ?: true,
                            onCheckedChange = { checkedModules[module.module] = it },
                            enabled = enabled,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 4.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Text(
                                "${module.module}：${module.implementation}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "验收边界：${module.acceptanceBoundary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
                // 全选开关（置于最后一项下方）：一键全选 / 全不选所有系统；半选表示当前部分勾选。
                // 全不选＝裸需求模式（不套用预设系统清单，以用户原话为准），下方给出一行提示。
                val allSelected = confirmation.modules.all { checkedModules[it.module] == true }
                val noneSelected = confirmation.modules.none { checkedModules[it.module] == true }
                val allToggleState = when {
                    allSelected -> ToggleableState.On
                    noneSelected -> ToggleableState.Off
                    else -> ToggleableState.Indeterminate
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = allSelected,
                            role = Role.Checkbox,
                            enabled = enabled,
                            onValueChange = { selectAll ->
                                confirmation.modules.forEach { checkedModules[it.module] = selectAll }
                            }
                        )
                        .padding(top = 4.dp)
                ) {
                    TriStateCheckbox(
                        state = allToggleState,
                        onClick = null,
                        enabled = enabled
                    )
                    Text(
                        "全选",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                if (noneSelected) {
                    Text(
                        "当前全部未勾选：将不套用预设系统清单，完全以你的原话为准，由 AI 自行判断需要哪些系统。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                    )
                }
            } else if (confirmation.systemExplanations.isNotEmpty()) {
                // 旧会话兼容：无结构化 module 数据时退化为纯文本回显
                Spacer(Modifier.size(6.dp))
                Text(
                    "游戏系统玩法与验收边界：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
                confirmation.systemExplanations.forEach { explanation ->
                    Text(
                        "· $explanation",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            // 质量档位（四挡）：驱动生成提示词、测试深度（快速档跳过沙箱）、自检轮数与内容丰富度。
            Spacer(Modifier.size(10.dp))
            Text(
                "质量档位（决定测试深度、自检轮数与内容丰富度）：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
            )
            QualityTier.ALL.forEach { t ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = selectedTier == t,
                            role = Role.RadioButton,
                            enabled = enabled,
                            onValueChange = { if (it) onTierChange(t) }
                        )
                        .padding(top = 2.dp)
                ) {
                    RadioButton(
                        selected = selectedTier == t,
                        onClick = null,
                        enabled = enabled
                    )
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(QualityTier.label(t), style = MaterialTheme.typography.bodySmall)
                        Text(
                            QualityTier.description(t),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                        )
                    }
                }
            }
            if (confirmation.designAssumptions.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    "默认值 / 对标游戏假设：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
                confirmation.designAssumptions.forEach { assumption ->
                    Text(
                        "· $assumption",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (confirmation.excludedSystems.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    "已明确排除系统：${confirmation.excludedSystems.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.size(10.dp))
            Button(
                onClick = { onConfirm(checkedModules.filterValues { !it }.keys, selectedTier, selectedOrientation, selectedDimension) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("确认，按此方案生成")
            }
            if (enabled) {
                Spacer(Modifier.size(4.dp))
                Text(
                    "如与预期不符，可直接在下方输入框补充修改；也可先取消勾选不需要的系统。确认后才会进入代码生成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** 停止中断的续跑卡片：中断轮次/耗时说明 + 「继续」按钮（断点恢复，非重做）。 */
@Composable
private fun ResumeTurnCard(
    payload: ResumeCardPayload,
    active: Boolean,
    onResume: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(payload.message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Button(onClick = onResume, enabled = active) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("继续")
            }
        }
    }
}

@Composable
private fun ActionBar(
    lastWarning: String?,
    canUndo: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    /** 保存按钮显隐：交付自动保存开启的已入库游戏不需要手动保存（真需求只剩草稿首存命名与关闭自动保存的用户）。 */
    showSave: Boolean = true
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (lastWarning != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                Text(
                    "提示：$lastWarning",
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, modifier = Modifier.weight(1.6f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("立即游玩")
            }
            // 保存按钮条件显隐：自动保存开启时已入库游戏的每次交付都已固化，
            // 手动保存冗余（还会与撤销锚点语义打架）；草稿首存与关闭自动保存时保留
            if (showSave) {
                FilledTonalButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("保存")
                }
            }
            // 撤销＝保存的逆操作：保存区覆盖编辑区、上下文回滚到保存点；草稿会话无保存点不显示
            if (canUndo) {
                FilledTonalButton(onClick = onUndo, modifier = Modifier.weight(1f)) {
                    Text("撤销")
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("再试一次") }
                TextButton(onClick = onSettings) { Text("去设置") }
            }
        }
    }
}

@Composable
private fun InputRow(
    value: String,
    enabled: Boolean,
    isGenerating: Boolean,
    elapsedText: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                maxLines = 3,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            if (isGenerating) {
                // 发送后按钮切换为停止键（动态显示本轮已运行时长），可随时中断 Agent Loop。
                Button(onClick = onStop) {
                    Icon(Icons.Filled.Close, contentDescription = "停止")
                    Spacer(Modifier.width(4.dp))
                    Text("停止 $elapsedText")
                }
            } else {
                Button(
                    onClick = onSend,
                    enabled = enabled && value.isNotBlank()
                ) {
                    Text("发送")
                }
            }
        }
    }
}

/** 停止键上的紧凑计时：0分0秒 → 1分5秒 → 12分30秒。 */
private fun formatElapsedCompact(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000
    return "${totalSec / 60}分${totalSec % 60}秒"
}

/** 首次保存（未命名草稿）命名框：默认名取会话首条用户消息前 20 字。 */
@Composable
private fun SaveGameDialog(
    defaultTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf(defaultTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存游戏") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("游戏名称") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun defaultTitle(session: GameSession): String {
    val first = session.messages.firstOrNull { it.isUser }?.content ?: return ""
    return first.trim().replace(Regex("\\s+"), " ").take(20)
}
