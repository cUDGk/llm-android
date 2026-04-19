package com.localllm.app.data.chat

import com.localllm.app.llama.LLMEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * LLMEngine (JNI in-process llama.cpp) をチャット UI 向けに薄くラップする。
 * 旧 HTTP 経路は Android 12+ の phantom-process-kill 問題で廃止。
 *
 * ai_chat.cpp 側が chat_template + KV cache を保持するので、履歴全体を
 * 送り直す必要はなく、最新の user 発話だけを渡せば自動的に会話が続く。
 */
class ChatRepository {

    fun streamChat(
        messages: List<ChatMessageDto>,
        maxTokens: Int = 512,
        noThink: Boolean = true,
    ): Flow<StreamEvent> = flow {
        val userText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (userText.isBlank()) {
            emit(StreamEvent.Error("empty user prompt"))
            return@flow
        }
        // /no_think は Qwen3 の thinking モードをスキップする公式指示子。
        // 非 Qwen3 モデルではただの文字列として扱われてしまうので、ロード中の
        // モデルファイル名を見て Qwen3 系のときだけ付ける。
        val currentModel = LLMEngine.loadedModel.value ?: ""
        val isQwen3Thinking = currentModel.contains("Qwen3", ignoreCase = true)
        val prompt = if (noThink && isQwen3Thinking) "/no_think\n$userText" else userText
        val startedAt = System.currentTimeMillis()
        var tokenCount = 0
        try {
            LLMEngine.generate(prompt, predictLength = maxTokens).collect { token ->
                tokenCount++
                emit(StreamEvent.Token(token))
            }
            val ms = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            val tps = tokenCount * 1000.0 / ms
            emit(
                StreamEvent.Done(
                    TimingsDto(
                        predicted_n = tokenCount,
                        predicted_ms = ms.toDouble(),
                        predicted_per_second = tps,
                    )
                )
            )
        } catch (e: Throwable) {
            emit(StreamEvent.Error(e.message ?: "generation failed"))
        }
    }
}

sealed interface StreamEvent {
    data class Token(val text: String) : StreamEvent
    data class Error(val message: String) : StreamEvent
    data class Done(val timings: TimingsDto?) : StreamEvent
}
