package com.localllm.app.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.app.data.AppContainer
import com.localllm.app.data.AssetExtractor
import com.localllm.app.data.chat.StreamEvent
import com.localllm.app.data.db.ConversationEntity
import com.localllm.app.data.db.MessageEntity
import com.localllm.app.llama.LLMEngine
import com.localllm.app.llama.LlamaServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val repo = container.chatRepository
    private val db = container.database
    private val settings = container.settings

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    // UI は LLMEngine の state を監視する。Running/Starting etc. を ChatScreen が参照。
    val engineState: StateFlow<LLMEngine.State> = LLMEngine.state
    val loadedModel: StateFlow<String?> = LLMEngine.loadedModel

    val conversations: StateFlow<List<ConversationEntity>> =
        db.conversationDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val messages: StateFlow<List<MessageEntity>> =
        _activeConversationId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else db.messageDao().observeByConversation(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _lastTps = MutableStateFlow<Double?>(null)
    val lastTps: StateFlow<Double?> = _lastTps.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            val first = db.conversationDao().observeAll().first()
            if (_activeConversationId.value == null) {
                _activeConversationId.value = first.firstOrNull()?.id
            }
        }
    }

    /**
     * 起動時 / 設定変更時に呼ぶ。native init → モデル展開 → loadModel。
     * 冪等: 同じモデルが既にロード済みならスキップ。
     */
    fun ensureEngineReady() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            LLMEngine.ensureInitialized(app)
            LlamaServerService.start(app) // FG 通知でアプリが kill されにくくなる
            val cfg = settings.config.first()
            val modelPath = withContext(Dispatchers.IO) {
                val extracted = AssetExtractor.extract(app)
                File(extracted.modelsDir, cfg.modelFileName).absolutePath
            }
            if (LLMEngine.loadedModel.value != modelPath) {
                runCatching { LLMEngine.loadModel(modelPath) }
                    .onFailure { Log.e(TAG, "loadModel failed", it) }
            }
        }
    }

    /**
     * 設定画面で別モデルに切り替えた際に呼ぶ。
     */
    fun reloadModel() = ensureEngineReady()

    fun newConversation() {
        viewModelScope.launch {
            val id = db.conversationDao().insert(ConversationEntity(title = "New chat"))
            _activeConversationId.value = id
        }
    }

    fun openConversation(id: Long) {
        _activeConversationId.value = id
    }

    fun renameCurrent(title: String) {
        val id = _activeConversationId.value ?: return
        viewModelScope.launch {
            db.conversationDao().rename(id, title)
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            val c = db.conversationDao().findById(id) ?: return@launch
            db.conversationDao().delete(c)
            if (_activeConversationId.value == id) {
                _activeConversationId.value = conversations.value
                    .firstOrNull { it.id != id }?.id
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value) return

        viewModelScope.launch {
            val convId = _activeConversationId.value ?: run {
                val id = db.conversationDao().insert(
                    ConversationEntity(title = trimmed.take(40))
                )
                _activeConversationId.value = id
                id
            }
            val conv = db.conversationDao().findById(convId)
            if (conv != null && conv.title == "New chat") {
                db.conversationDao().rename(convId, trimmed.take(40))
            }

            val now = System.currentTimeMillis()
            db.messageDao().insert(
                MessageEntity(
                    conversationId = convId,
                    role = "user",
                    content = trimmed,
                    createdAt = now,
                )
            )
            db.conversationDao().touch(convId, now)

            val assistantId = db.messageDao().insert(
                MessageEntity(
                    conversationId = convId,
                    role = "assistant",
                    content = "",
                    createdAt = now + 1,
                )
            )

            _streaming.value = true
            streamJob = launch {
                try {
                    val history = db.messageDao().listByConversation(convId)
                        .filter { it.id != assistantId }
                        .map { com.localllm.app.data.chat.ChatMessageDto(it.role, it.content) }
                    val buf = StringBuilder()
                    repo.streamChat(messages = history).collect { ev ->
                        when (ev) {
                            is StreamEvent.Token -> {
                                buf.append(ev.text)
                                db.messageDao().updateContent(
                                    id = assistantId,
                                    content = buf.toString(),
                                    tps = null,
                                    predN = null,
                                )
                            }
                            is StreamEvent.Done -> {
                                db.messageDao().updateContent(
                                    id = assistantId,
                                    content = buf.toString(),
                                    tps = ev.timings?.predicted_per_second,
                                    predN = ev.timings?.predicted_n,
                                )
                                _lastTps.value = ev.timings?.predicted_per_second
                            }
                            is StreamEvent.Error -> {
                                db.messageDao().updateContent(
                                    id = assistantId,
                                    content = buf.toString() +
                                        "\n\n[error] ${ev.message}",
                                    tps = null,
                                    predN = null,
                                )
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "stream failed", e)
                    db.messageDao().updateContent(
                        id = assistantId,
                        content = "[error] ${e.message}",
                        tps = null,
                        predN = null,
                    )
                } finally {
                    _streaming.value = false
                }
            }
        }
    }

    fun cancel() {
        LLMEngine.requestCancel()
        streamJob?.cancel()
        _streaming.value = false
    }

    companion object {
        private const val TAG = "ChatVM"
    }
}
