package com.localllm.app.data.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String = "local",
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    val temperature: Double = 0.7,
    val top_p: Double = 0.9,
    val max_tokens: Int = 1024,
    val cache_prompt: Boolean = true,
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: DeltaDto = DeltaDto(),
    val finish_reason: String? = null,
)

@Serializable
data class DeltaDto(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class ChatStreamChunk(
    val id: String? = null,
    val `object`: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    val timings: TimingsDto? = null,
)

@Serializable
data class TimingsDto(
    val prompt_n: Int? = null,
    val prompt_ms: Double? = null,
    val prompt_per_second: Double? = null,
    val predicted_n: Int? = null,
    val predicted_ms: Double? = null,
    val predicted_per_second: Double? = null,
)
