package com.localllm.app.llama

/**
 * llama-server の起動時パラメータ。UI / 設定画面と直接 1:1 対応させる。
 */
data class LlamaServerConfig(
    val modelFileName: String,
    val draftModelFileName: String? = null,
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val batchSize: Int = 512,
    val ubatchSize: Int = 128,
    val flashAttention: Boolean = true,
    val cacheReuse: Int = 256,
    val draftMax: Int = 8,
    val draftMin: Int = 2,
    val port: Int = 18080,
)

/**
 * 同梱モデルの一覧 (assets/models/ に置かれているファイル名と対応)。
 */
object BundledModels {
    const val QWEN3_0_6B_Q4_0 = "Qwen3-0.6B-Q4_0.gguf"
    const val QWEN25_0_5B     = "Qwen2.5-0.5B-Instruct-Q4_0.gguf"
    const val LLAMA32_1B      = "Llama-3.2-1B-Instruct-Q4_0.gguf"

    // 旧実装互換: 他の場所で参照されていた定数名は Qwen2.5 0.5B をエイリアスする。
    const val QWEN3_0_6B_DRAFT = QWEN25_0_5B

    val all: List<BundledModel> = listOf(
        BundledModel(
            fileName = QWEN25_0_5B,
            displayName = "Qwen2.5-0.5B (Q4_0, 最速・即答)",
            role = ModelRole.Main,
            paramBillion = 0.5,
        ),
        BundledModel(
            fileName = LLAMA32_1B,
            displayName = "Llama-3.2-1B (Q4_0, 速・賢さ両立)",
            role = ModelRole.Main,
            paramBillion = 1.0,
        ),
        BundledModel(
            fileName = QWEN3_0_6B_Q4_0,
            displayName = "Qwen3-0.6B (Q4_0, 思考モード)",
            role = ModelRole.Main,
            paramBillion = 0.6,
        ),
    )

    fun find(fileName: String): BundledModel? = all.firstOrNull { it.fileName == fileName }
}

data class BundledModel(
    val fileName: String,
    val displayName: String,
    val role: ModelRole,
    val paramBillion: Double,
)

enum class ModelRole { Main, Draft }
