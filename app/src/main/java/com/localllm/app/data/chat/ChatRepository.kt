package com.localllm.app.data.chat

import android.util.Log
import com.localllm.app.llama.LlamaServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * llama-server の OpenAI 互換 /v1/chat/completions を叩く。
 * SSE ストリーミング (`data: ...\n\n` 形式) を行単位で読み進めながら、
 * [StreamEvent] の Flow として UI に流す。
 */
class ChatRepository(
    private val baseUrlProvider: () -> String = { LlamaServerManager.baseUrl },
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming なので無制限
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun streamChat(
        messages: List<ChatMessageDto>,
        temperature: Double = 0.7,
        maxTokens: Int = 1024,
        cachePrompt: Boolean = true,
    ): Flow<StreamEvent> = flow {
        val payload = ChatCompletionRequest(
            messages = messages,
            stream = true,
            temperature = temperature,
            max_tokens = maxTokens,
            cache_prompt = cachePrompt,
        )
        val body = json.encodeToString(ChatCompletionRequest.serializer(), payload)
            .toRequestBody("application/json".toMediaType())
        val url = "${baseUrlProvider()}/v1/chat/completions"
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "text/event-stream")
            .build()

        val resp = client.newCall(req).execute()
        try {
            if (!resp.isSuccessful) {
                emit(StreamEvent.Error("HTTP ${resp.code}: ${resp.message}"))
                return@flow
            }
            val source = resp.body?.source()
            if (source == null) {
                emit(StreamEvent.Error("empty body"))
                return@flow
            }
            var lastTimings: TimingsDto? = null
            var done = false
            while (!done && !source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val payloadLine = line.removePrefix("data:").trim()
                if (payloadLine == "[DONE]") {
                    done = true
                    continue
                }
                val chunk = try {
                    json.decodeFromString(ChatStreamChunk.serializer(), payloadLine)
                } catch (e: Throwable) {
                    Log.w(TAG, "parse failed: $payloadLine", e)
                    continue
                }
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    emit(StreamEvent.Token(delta))
                }
                chunk.timings?.let { lastTimings = it }
                val finish = chunk.choices.firstOrNull()?.finish_reason
                if (!finish.isNullOrEmpty()) {
                    done = true
                }
            }
            emit(StreamEvent.Done(lastTimings))
        } finally {
            resp.close()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "ChatRepository"
    }
}

sealed interface StreamEvent {
    data class Token(val text: String) : StreamEvent
    data class Error(val message: String) : StreamEvent
    data class Done(val timings: TimingsDto?) : StreamEvent
}
