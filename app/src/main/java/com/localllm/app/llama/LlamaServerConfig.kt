package com.localllm.app.llama

/**
 * llama-server の起動時パラメータ。UI / 設定画面と直接 1:1 対応させる。
 */
data class LlamaServerConfig(
    val modelFileName: String,
    val draftModelFileName: String? = null,
    val contextSize: Int = 4096,
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
    // split GGUF の 1 枚目を指定すれば llama-server が自動で後続を読み込む。
    // split した理由: AGP の compressAssets が 2GB を超える単一アセットで死ぬため。
    const val QWEN3_4B_INSTRUCT = "Qwen3-4B-Instruct-2507-Q4_K_M-split-00001-of-00002.gguf"
    const val QWEN3_0_6B_DRAFT = "Qwen3-0.6B-Q4_K_M.gguf"

    val all: List<BundledModel> = listOf(
        BundledModel(
            fileName = QWEN3_4B_INSTRUCT,
            displayName = "Qwen3-4B Instruct (Q4_K_M)",
            role = ModelRole.Main,
            paramBillion = 4.0,
        ),
        BundledModel(
            fileName = QWEN3_0_6B_DRAFT,
            displayName = "Qwen3-0.6B (Q4_K_M)",
            role = ModelRole.Draft,
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
