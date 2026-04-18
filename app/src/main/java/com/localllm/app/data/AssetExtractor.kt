package com.localllm.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * assets/ 配下の実行ファイル・モデルを filesDir/ へ展開する。
 *
 * なぜ展開が必要か:
 *  - assets 配下のファイルは APK 内 (読み取り専用) に置かれ、直接 exec() できない。
 *  - Android は jniLibs/ の .so を `/data/app/<pkg>/lib/arm64/` に自動展開するが、
 *    実行ファイル (llama-server) は対象外。自前でコピーして chmod 700 する。
 *  - モデルも同様。mmap 可能な実ファイルパスが必要。
 *
 * 冪等性: 展開済みなら再コピーしない (dest が存在し、0 バイトでない事を確認)。
 * APK 更新時は VersionCode を見て binary だけ強制再展開する。
 */
object AssetExtractor {
    private const val TAG = "AssetExtractor"
    private const val MARKER_FILE = ".extracted"

    data class ExtractResult(
        val serverBin: File,
        val modelsDir: File,
    )

    fun extract(context: Context): ExtractResult {
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

        val versionCode = context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode
        val marker = File(binDir, MARKER_FILE)
        val needBinaryExtract = !marker.exists() ||
            marker.readText().trim().toLongOrNull() != versionCode

        val serverBin = File(binDir, "llama-server")
        if (needBinaryExtract || !serverBin.exists() || serverBin.length() == 0L) {
            copyAsset(context, "bin/llama-server", serverBin)
            if (!serverBin.setExecutable(true, /* ownerOnly = */ true)) {
                Log.w(TAG, "setExecutable(true) failed for ${serverBin.path}")
            }
            marker.writeText(versionCode.toString())
        }

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

        return ExtractResult(serverBin = serverBin, modelsDir = modelsDir)
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
