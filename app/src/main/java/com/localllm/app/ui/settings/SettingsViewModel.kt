package com.localllm.app.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.app.data.AppContainer
import com.localllm.app.data.AssetExtractor
import com.localllm.app.llama.BundledModels
import com.localllm.app.llama.LLMEngine
import com.localllm.app.llama.LlamaServerConfig
import com.localllm.app.llama.LlamaServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.from(app)
    private val settings = container.settings

    val config: StateFlow<LlamaServerConfig?> = settings.config.stateIn(
        viewModelScope, SharingStarted.Eagerly, null,
    )
    val engineState: StateFlow<LLMEngine.State> = LLMEngine.state

    // 0.6B は JNI in-process で動く速度重視モデル。4B は重いが同梱のみ。
    val mainModels: List<String> = BundledModels.all.map { it.fileName }

    // 旧 speculative の draft モデルはメモリ圧で phantom-kill を誘発するため無効固定。
    // UI にスライダーだけ残しても実効しないので空リストにして draft ドロップダウンごと非表示扱い。
    val draftModels: List<String> = listOf("")

    fun update(transform: (LlamaServerConfig) -> LlamaServerConfig) {
        viewModelScope.launch {
            settings.update(transform)
        }
    }

    /**
     * モデル変更を反映。LLMEngine は別モデルロード時に自動で旧モデル unload する。
     */
    fun applyAndRestart() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val cfg = settings.config.first()
            LlamaServerService.start(app)
            LLMEngine.ensureInitialized(app)
            val modelPath = withContext(Dispatchers.IO) {
                val extracted = AssetExtractor.extract(app)
                File(extracted.modelsDir, cfg.modelFileName).absolutePath
            }
            runCatching { LLMEngine.loadModel(modelPath) }
                .onFailure { Log.e(TAG, "reload failed", it) }
        }
    }

    companion object {
        private const val TAG = "SettingsVM"
    }
}
