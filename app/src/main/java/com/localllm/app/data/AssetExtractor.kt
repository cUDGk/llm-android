package com.localllm.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * assets/models/ の GGUF を filesDir/models/ へ展開する。
 *
 * llama-server バイナリは jniLibs に libllama-server.so として同梱し、
 * Android が nativeLibraryDir に自動配置する (W^X の制約で、exec 可能なのは
 * nativeLibraryDir 配下だけ)。モデルは mmap 用に実ファイルパスが必要。
 *
 * 冪等性: 展開済みなら再コピーしない (dest が存在し、0 バイトでない事を確認)。
 */
object AssetExtractor {
    private const val TAG = "AssetExtractor"

    data class ExtractResult(
        val modelsDir: File,
    )

    fun extract(context: Context): ExtractResult {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

        val modelsList = context.assets.list("models").orEmpty()
        for (name in modelsList) {
            if (!name.endsWith(".gguf")) continue
            val dest = File(modelsDir, name)
            if (dest.exists() && dest.length() > 0) {
                Log.d(TAG, "model already extracted: $name (${dest.length()} B)")
                continue
            }
            copyAsset(context, "models/$name", dest)
        }

        return ExtractResult(modelsDir = modelsDir)
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        Log.i(TAG, "extracting $assetPath -> ${dest.path}")
        val tmp = File(dest.parentFile, "${dest.name}.part")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 20) // 1 MiB
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.fd.sync()
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            error("rename failed: ${tmp.path} -> ${dest.path}")
        }
    }
}
