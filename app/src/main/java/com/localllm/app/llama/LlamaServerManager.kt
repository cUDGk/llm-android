package com.localllm.app.llama

import android.content.Context
import android.util.Log
import com.localllm.app.data.AssetExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * llama-server 子プロセスのライフサイクル管理。
 *
 * 設計メモ:
 *  - Android は ProcessBuilder で子プロセスを fork できるが、ネイティブ lib は
 *    `/data/app/<pkg>/lib/arm64/` に展開される。子プロセスには自動継承されないので
 *    環境変数 LD_LIBRARY_PATH を明示設定する。
 *  - 生成中にアプリがバックグラウンドに回っても kill されないよう Foreground Service
 *    が常駐する (LlamaServerService)。本クラスは Service から呼ばれる想定。
 *  - シングルトンとして起動状態を共有する。
 */
object LlamaServerManager {
    private const val TAG = "LlamaServer"
    private const val HOST = "127.0.0.1"
    private const val BOOT_TIMEOUT_MS = 60_000L
    private const val HEALTH_POLL_INTERVAL_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var logJob: Job? = null
    @Volatile private var currentConfig: LlamaServerConfig? = null

    val baseUrl: String get() = "http://$HOST:${currentConfig?.port ?: 18080}"

    suspend fun start(context: Context, config: LlamaServerConfig) = withContext(Dispatchers.IO) {
        if (_state.value is ServerState.Running || _state.value is ServerState.Starting) {
            Log.w(TAG, "start() ignored: state=${_state.value}")
            return@withContext
        }
        _state.value = ServerState.Starting
        _logs.value = emptyList()

        try {
            val extracted = AssetExtractor.extract(context)
            val modelFile = File(extracted.modelsDir, config.modelFileName)
            require(modelFile.exists() && modelFile.length() > 0) {
                "model not found: ${modelFile.path}"
            }
            val draftFile = config.draftModelFileName?.let {
                File(extracted.modelsDir, it).also { f ->
                    require(f.exists() && f.length() > 0) { "draft not found: ${f.path}" }
                }
            }

            val libDir = context.applicationInfo.nativeLibraryDir
            val cmd = buildCommand(extracted.serverBin, modelFile, draftFile, config, libDir)
            Log.i(TAG, "spawn: " + cmd.joinToString(" "))

            val pb = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = libDir
            // ggml-backend の動的ロードパス (dlopen の検索パス)
            pb.environment()["GGML_BACKEND_PATH"] = libDir
            // tmp / cache は app 個別領域へ
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.environment()["HOME"] = context.filesDir.absolutePath

            val proc = pb.start()
            process = proc
            currentConfig = config

            logJob = scope.launch { pumpLogs(proc) }

            val booted = waitUntilReady(config.port, BOOT_TIMEOUT_MS)
            if (booted) {
                _state.value = ServerState.Running(config)
                Log.i(TAG, "server ready on port ${config.port}")
            } else {
                val exitValue = runCatching { proc.exitValue() }.getOrNull()
                stopInternal()
                _state.value = ServerState.Error(
                    "server did not become ready within ${BOOT_TIMEOUT_MS}ms " +
                        "(exit=$exitValue)"
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            stopInternal()
            _state.value = ServerState.Error(e.message ?: e::class.java.simpleName)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        stopInternal()
        _state.value = ServerState.Stopped
    }

    private fun stopInternal() {
        logJob?.cancel()
        logJob = null
        process?.let { p ->
            runCatching { p.destroy() }
            val graceful = runCatching { p.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
            if (graceful != true) {
                runCatching { p.destroyForcibly() }
            }
        }
        process = null
        currentConfig = null
    }

    private fun buildCommand(
        serverBin: File,
        modelFile: File,
        draftFile: File?,
        cfg: LlamaServerConfig,
        libDir: String,
    ): List<String> {
        val cmd = mutableListOf(
            serverBin.absolutePath,
            "-m", modelFile.absolutePath,
            "-c", cfg.contextSize.toString(),
            "-t", cfg.threads.toString(),
            "-b", cfg.batchSize.toString(),
            "-ub", cfg.ubatchSize.toString(),
            "--cache-reuse", cfg.cacheReuse.toString(),
            "--host", HOST,
            "--port", cfg.port.toString(),
            "--no-webui",
            "-ngl", "0",
        )
        if (cfg.flashAttention) cmd += listOf("-fa", "on")
        if (draftFile != null) {
            cmd += listOf(
                "-md", draftFile.absolutePath,
                "-ngld", "0",
                "--draft-max", cfg.draftMax.toString(),
                "--draft-min", cfg.draftMin.toString(),
            )
        }
        return cmd
    }

    private fun pumpLogs(proc: Process) {
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val buf = ArrayDeque<String>()
        val maxLines = 500
        try {
            while (scope.isActive) {
                val line = reader.readLine() ?: break
                Log.d(TAG, line)
                buf.addLast(line)
                while (buf.size > maxLines) buf.removeFirst()
                _logs.value = buf.toList()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { reader.close() }
        }
    }

    private suspend fun waitUntilReady(port: Int, timeoutMs: Long): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()
        val url = "http://$HOST:$port/health"
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                try {
                    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (resp.isSuccessful) return@withTimeoutOrNull true
                    }
                } catch (_: Throwable) {
                }
                delay(HEALTH_POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    }
}

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val config: LlamaServerConfig) : ServerState
    data class Error(val message: String) : ServerState
}
