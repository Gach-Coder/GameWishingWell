package com.gamewishingwell.ui.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gamewishingwell.agent.ConfigParam
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 参数调整编辑器（参数外置契约·直列编辑·共享组件）：进页即列全部参数（名称/注释/
 * 当前生效值，覆盖优先）。点任意行内联改值（输入框+确定/取消），可连续修改多项——
 * 已改行高亮并可 ↺ 撤销单项；底部【保存并生效】把全部修改回调给调用方
 * （首页卡片菜单与游戏页共用；保存/落盘/重载语义由调用方实现）。
 * 免 JSON 语法：数字直接填、布尔 true/false、其余按文本原样（调用方宽松解析）。
 *
 * 搜索：顶部输入框按参数名/注释/当前取值（含已改未保存值）不区分大小写过滤，
 * 参数多时（实测 40+ 键）免滚动翻找。
 * 长值编辑：当前取值超过 [LARGE_VALUE_THRESHOLD] 字符时点击行弹出独立大编辑框
 * （多行、可滚动），行内单行输入框编辑长值太局促。
 */
@Composable
fun ConfigEditorDialog(
    params: List<ConfigParam>,
    onSave: (changes: List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit
) {
    val pending = remember { mutableStateMapOf<String, String>() }
    var editingName by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var cfgError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var largeEditingName by remember { mutableStateOf<String?>(null) }
    var largeEditingValue by remember { mutableStateOf("") }

    fun displayValue(el: JsonElement?): String = when (el) {
        null -> ""
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    // 搜索过滤：名称/注释/取值（已改未保存值优先于默认值），忽略大小写
    val query = searchQuery.trim()
    val shownParams = if (query.isEmpty()) params else params.filter { p ->
        val currentValue = pending[p.name] ?: displayValue(p.effective)
        p.name.contains(query, ignoreCase = true) ||
            p.doc.contains(query, ignoreCase = true) ||
            currentValue.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参数调整") },
        text = {
            Column {
                Text(
                    if (pending.isEmpty()) {
                        "点参数行直接修改数值，可连续修改多项后一次保存生效。" +
                            "数值直接填数字，布尔填 true/false，其余按文本原样填写。"
                    } else {
                        "待保存修改 ${pending.size} 项（高亮行，↺ 撤销单项，重置修改全清）。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(4.dp))
                if (params.isEmpty()) {
                    Text(
                        "参数列表解析失败：该游戏的配置不可解析，无法列出参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("搜索参数名 / 注释 / 取值", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                    if (shownParams.isEmpty()) {
                        Text(
                            "没有匹配「$query」的参数（已按名称/注释/取值过滤）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(shownParams, key = { it.name }) { p ->
                                val edited = pending[p.name]
                                if (editingName == p.name) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Text(
                                            p.name + if (p.doc.isNotEmpty()) " · ${p.doc}" else "",
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(
                                                value = editingValue,
                                                onValueChange = {
                                                    editingValue = it
                                                    cfgError = null
                                                },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = {
                                                if (editingValue.isNotBlank()) {
                                                    pending[p.name] = editingValue.trim()
                                                }
                                                editingName = null
                                            }) { Text("确定") }
                                            TextButton(onClick = { editingName = null }) { Text("取消") }
                                        }
                                    }
                                } else {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val current = edited ?: displayValue(p.effective)
                                                if (current.length > LARGE_VALUE_THRESHOLD) {
                                                    // 长值走大编辑框：行内单行输入太局促
                                                    largeEditingName = p.name
                                                    largeEditingValue = current
                                                } else {
                                                    editingName = p.name
                                                    editingValue = current
                                                }
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                p.name,
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                color = if (edited != null) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (p.doc.isNotEmpty()) {
                                                Text(
                                                    p.doc,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        Text(
                                            edited ?: displayValue(p.effective),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = if (edited != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                        if (edited != null) {
                                            TextButton(onClick = { pending.remove(p.name) }) { Text("↺") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                cfgError?.let {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            // 底部同一排：重置修改 / 保存并生效 / 关闭
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = pending.isNotEmpty(),
                    onClick = {
                        pending.clear()
                        editingName = null
                    }
                ) { Text("重置修改") }
                Button(
                    enabled = pending.isNotEmpty(),
                    onClick = {
                        val changes = pending.toList()
                        onSave(changes)
                    }
                ) { Text("保存并生效") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )

    // 长值大编辑框：独立弹层（多行），确定写回待保存表，与行内编辑共用 pending
    largeEditingName?.let { name ->
        AlertDialog(
            onDismissRequest = { largeEditingName = null },
            title = { Text(name, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = largeEditingValue,
                        onValueChange = {
                            largeEditingValue = it
                            cfgError = null
                        },
                        minLines = 5,
                        maxLines = 12,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
                    )
                    Text(
                        "免 JSON 语法：数字直接填、布尔 true/false，其余按文本原样。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (largeEditingValue.isNotBlank()) {
                        pending[name] = largeEditingValue.trim()
                    }
                    largeEditingName = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { largeEditingName = null }) { Text("取消") }
            }
        )
    }
}

/** 超过该长度的取值点击编辑时切换到独立大编辑框。 */
private const val LARGE_VALUE_THRESHOLD = 10
