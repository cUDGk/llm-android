package com.localllm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localllm.app.data.db.MessageEntity
import com.localllm.app.llama.BundledModels
import com.localllm.app.llama.LLMEngine
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()
    val activeId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val loadedModel by viewModel.loadedModel.collectAsStateWithLifecycle()
    val lastTps by viewModel.lastTps.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.ensureEngineReady()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Chats",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = {
                        viewModel.newConversation()
                        scope.launch { drawerState.close() }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations, key = { it.id }) { conv ->
                        NavigationDrawerItem(
                            selected = conv.id == activeId,
                            label = {
                                Text(
                                    conv.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            icon = {
                                IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            },
                            onClick = {
                                viewModel.openConversation(conv.id)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        },
    ) {
        // Scaffold は使わず手動 Column レイアウト。
        // Scaffold の bottomBar + enableEdgeToEdge + WindowInsets の組合せで
        // キーボード出た時に二重吸収し、composer が IME 2 個分上に跳ねる現象があった。
        // 手動だと何がどこに効いているかを完全に制御できる。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding(),
        ) {
            ChatTopBar(
                modelDisplayName = displayNameFor(loadedModel),
                engineState = engineState,
                lastTps = lastTps,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onOpenSettings = onOpenSettings,
            )
            val showProgress = engineState !is LLMEngine.State.ModelReady &&
                engineState !is LLMEngine.State.Error
            if (showProgress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Box(modifier = Modifier.weight(1f)) {
                MessageList(
                    messages = messages,
                    streaming = streaming,
                    engineState = engineState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Composer(
                streaming = streaming,
                enabled = engineState is LLMEngine.State.ModelReady ||
                    engineState is LLMEngine.State.Generating ||
                    engineState is LLMEngine.State.Processing,
                onSend = viewModel::send,
                onCancel = viewModel::cancel,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelDisplayName: String,
    engineState: LLMEngine.State,
    lastTps: Double?,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    modelDisplayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(engineState)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = engineSubtitle(engineState, lastTps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenMenu) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    )
}

@Composable
private fun StatusDot(state: LLMEngine.State) {
    val color = when (state) {
        LLMEngine.State.ModelReady   -> Color(0xFF34C759) // green
        LLMEngine.State.Generating,
        LLMEngine.State.Processing   -> Color(0xFFFF9500) // orange
        is LLMEngine.State.Error     -> MaterialTheme.colorScheme.error
        else                         -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(SolidColor(color)),
    )
}

private fun displayNameFor(path: String?): String {
    if (path.isNullOrEmpty()) return "Loading…"
    val filename = File(path).name
    return BundledModels.find(filename)?.displayName ?: filename
}

private fun engineSubtitle(state: LLMEngine.State, tps: Double?): String = when (state) {
    LLMEngine.State.Uninitialized -> "initializing native…"
    LLMEngine.State.Initializing  -> "initializing native…"
    LLMEngine.State.Initialized   -> "loading model…"
    LLMEngine.State.LoadingModel  -> "loading model…"
    LLMEngine.State.ModelReady    -> if (tps != null) "ready · ${"%.1f".format(tps)} tok/s" else "ready"
    LLMEngine.State.Processing    -> "processing prompt…"
    LLMEngine.State.Generating    -> "generating…"
    is LLMEngine.State.Error      -> "error: ${state.message}"
}

@Composable
private fun MessageList(
    messages: List<MessageEntity>,
    streaming: Boolean,
    engineState: LLMEngine.State,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    if (messages.isEmpty()) {
        EmptyState(engineState = engineState, modifier = modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { msg ->
            MessageBubble(msg)
        }
        if (streaming && messages.lastOrNull()?.role != "assistant") {
            item { TypingIndicator() }
        }
    }
}

@Composable
private fun EmptyState(engineState: LLMEngine.State, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "LocalLLM",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val hint = when (engineState) {
                is LLMEngine.State.ModelReady -> "メッセージを送ってみて"
                is LLMEngine.State.Error      -> "エラー: ${engineState.message}"
                else                          -> "モデル準備中..."
            }
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity) {
    val isUser = msg.role == "user"
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }
    val bg = if (isUser) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurface

    // Qwen3 の thinking モードは `<think>...</think>` で囲まれて出力される。
    // 発言部分だけ普通に表示し、思考はチップでトグルして隠せるようにする。
    val parsed = remember(msg.content) { parseThinking(msg.content) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && parsed.thinking.isNotBlank()) {
            ThinkingBlock(parsed.thinking)
            Spacer(Modifier.size(4.dp))
        }
        if (parsed.content.isNotEmpty() || isUser) {
            Surface(
                shape = shape,
                color = bg,
                contentColor = fg,
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Text(
                    text = (if (isUser) msg.content else parsed.content).ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (!isUser && msg.tokensPerSec != null) {
            Text(
                text = "${"%.1f".format(msg.tokensPerSec)} tok/s · ${msg.predictedN ?: 0} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}

@Composable
private fun ThinkingBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(0.85f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                                  else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "思考過程 (${text.length} chars)",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private data class ParsedMessage(val thinking: String, val content: String)

private fun parseThinking(raw: String): ParsedMessage {
    // ストリーミング中は `<think>` だけで `</think>` がまだ来てない事がある。
    // その場合は「全部思考中」として扱う。
    val openIdx = raw.indexOf("<think>")
    if (openIdx < 0) return ParsedMessage("", raw.trim())
    val afterOpen = raw.substring(openIdx + "<think>".length)
    val closeIdx = afterOpen.indexOf("</think>")
    return if (closeIdx < 0) {
        ParsedMessage(afterOpen.trim(), "")
    } else {
        val thinking = afterOpen.substring(0, closeIdx).trim()
        val content = afterOpen.substring(closeIdx + "</think>".length).trim()
        ParsedMessage(thinking, content)
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "generating…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    streaming: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            // IME が出た時は imePadding() で IME 高さ分だけ上に逃がす。
            // IME が閉じてる時は IME 高=0 なので navigationBarsPadding でナビバー分を
            // 足す。imePadding は内部で IME 有り/無しをアニメで補完する (Compose 1.5+)。
            // 手動 Column レイアウトなので Scaffold による二重吸収も起きない。
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    // 3 行まで。それ以上は内部スクロール。高さ暴れ防止。
                    .heightIn(min = 48.dp, max = 110.dp),
                placeholder = {
                    Text(if (enabled) "メッセージを送信" else "サーバ準備中…")
                },
                enabled = enabled && !streaming,
                maxLines = 3,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
            Spacer(Modifier.width(8.dp))
            // 送信/停止ボタン: FAB 風の filled icon で目立たせる
            if (streaming) {
                FilledIconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        val t = text
                        if (t.isNotBlank()) {
                            onSend(t)
                            text = ""
                        }
                    },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
                }
            }
        }
    }
}
