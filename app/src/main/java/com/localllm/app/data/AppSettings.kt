package com.localllm.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localllm.app.llama.BundledModels
import com.localllm.app.llama.LlamaServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore by preferencesDataStore(name = "settings")

object SettingsKeys {
    val MAIN_MODEL = stringPreferencesKey("main_model")
    val DRAFT_MODEL = stringPreferencesKey("draft_model") // "" = 無効
    val CONTEXT_SIZE = intPreferencesKey("context_size")
    val THREADS = intPreferencesKey("threads")
    val FLASH_ATTENTION = booleanPreferencesKey("flash_attention")
    val DRAFT_MAX = intPreferencesKey("draft_max")
}

class SettingsRepository(private val context: Context) {

    val config: Flow<LlamaServerConfig> = context.settingsDataStore.data.map { prefs ->
        prefs.toConfig()
    }

    suspend fun update(transform: (LlamaServerConfig) -> LlamaServerConfig) {
        context.settingsDataStore.edit { prefs ->
            val cur = prefs.toConfig()
            val next = transform(cur)
            prefs[SettingsKeys.MAIN_MODEL] = next.modelFileName
            prefs[SettingsKeys.DRAFT_MODEL] = next.draftModelFileName ?: ""
            prefs[SettingsKeys.CONTEXT_SIZE] = next.contextSize
            prefs[SettingsKeys.THREADS] = next.threads
            prefs[SettingsKeys.FLASH_ATTENTION] = next.flashAttention
            prefs[SettingsKeys.DRAFT_MAX] = next.draftMax
        }
    }

    private fun Preferences.toConfig(): LlamaServerConfig {
        // 8GB端末では 0.6B 単独 + ctx 2048 + draft 無効 が速度/安定性の最適点。
        // 4B + speculative は目標(~30 tok/s)に遠く、OSの phantom-process-kill を誘発しやすい。
        val main = this[SettingsKeys.MAIN_MODEL] ?: BundledModels.QWEN25_0_5B
        val draftRaw = this[SettingsKeys.DRAFT_MODEL] ?: ""
        val draft = if (draftRaw.isBlank()) null else draftRaw
        return LlamaServerConfig(
            modelFileName = main,
            draftModelFileName = draft,
            contextSize = this[SettingsKeys.CONTEXT_SIZE] ?: 2048,
            threads = this[SettingsKeys.THREADS] ?: 4,
            flashAttention = this[SettingsKeys.FLASH_ATTENTION] ?: true,
            draftMax = this[SettingsKeys.DRAFT_MAX] ?: 8,
        )
    }
}
