package com.localllm.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.app.data.AppContainer
import com.localllm.app.llama.BundledModels
import com.localllm.app.llama.LlamaServerConfig
import com.localllm.app.llama.LlamaServerManager
import com.localllm.app.llama.LlamaServerService
import com.localllm.app.llama.ServerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.from(app)
    private val settings = container.settings

    val config: StateFlow<LlamaServerConfig?> = settings.config.stateIn(
        viewModelScope, SharingStarted.Eagerly, null,
    )
    val serverState: StateFlow<ServerState> = LlamaServerManager.state

    val mainModels: List<String> = BundledModels.all.map { it.fileName }
    val draftModels: List<String> = listOf("") + BundledModels.all
        .filter { it.role == com.localllm.app.llama.ModelRole.Draft }
        .map { it.fileName }

    fun update(transform: (LlamaServerConfig) -> LlamaServerConfig) {
        viewModelScope.launch {
            settings.update(transform)
        }
    }

    fun applyAndRestart() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val cfg = settings.config.first()
            LlamaServerManager.stop()
            LlamaServerService.start(app)
            LlamaServerManager.start(app, cfg)
        }
    }
}
