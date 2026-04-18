package com.localllm.app.data

import android.content.Context
import com.localllm.app.data.chat.ChatRepository
import com.localllm.app.data.db.AppDatabase

/**
 * 簡易サービスロケータ。Hilt を持ち込むほどでもないので手で寄せる。
 */
class AppContainer private constructor(context: Context) {
    val database: AppDatabase = AppDatabase.get(context)
    val settings: SettingsRepository = SettingsRepository(context.applicationContext)
    val chatRepository: ChatRepository = ChatRepository()

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun from(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
