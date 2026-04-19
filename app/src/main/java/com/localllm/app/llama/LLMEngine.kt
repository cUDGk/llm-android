package com.localllm.app.llama

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * llama.cpp を in-process で動かす JNI ラッパ。llama-server を exec するのを止め、
 * 同一プロセス内で推論を回すことで Android 12+ の phantom-process-killer を回避する。
 *
 * JNI 本体は app/src/main/cpp/llama_jni.cpp (llama.cpp の llama.android サンプル由来)。
 * シングルトン。llama_model/context はグローバル 1 本。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object LLMEngine {
    private const val TAG = "LLMEngine"

    sealed interface State {
        data object Uninitialized : State
        data object Initializing : State
        data object Initialized : State
        data object LoadingModel : State
        data object ModelReady : State
        data object Processing : State
        data object Generating : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Uninitialized)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _loadedModel = MutableStateFlow<String?>(null)
    val loadedModel: StateFlow<String?> = _loadedModel.asStateFlow()

    // llama ライブラリは thread-unsafe なので全呼出しを単一 dispatcher に通す。
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    @Volatile private var cancelFlag = false
    @Volatile private var nativeLibDir: String? = null

    init {
        System.loadLibrary("localllm-jni")
    }

    fun ensureInitialized(context: Context) {
        val dir = context.applicationInfo.nativeLibraryDir
        scope.launch {
            if (_state.value != State.Uninitialized) return@launch
            _state.value = State.Initializing
            try {
                init(dir)
                nativeLibDir = dir
                _state.value = State.Initialized
                Log.i(TAG, "native init ok; sysinfo:\n${systemInfo()}")
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                _state.value = State.Error(e.message ?: "init failed")
            }
        }
    }

    suspend fun loadModel(modelPath: String) = withContext(dispatcher) {
        val current = _loadedModel.value
        if (current == modelPath && _state.value is State.ModelReady) {
            Log.i(TAG, "model already loaded: $modelPath")
            return@withContext
        }
        // 既にロード済みの別モデルがあれば unload してから。
        if (_state.value is State.ModelReady || _state.value is State.Generating) {
            runCatching { unload() }
            _loadedModel.value = null
            _state.value = State.Initialized
        }

        val f = File(modelPath)
        require(f.exists() && f.isFile && f.canRead()) { "model not accessible: $modelPath" }

        _state.value = State.LoadingModel
        val loadRc = load(modelPath)
        if (loadRc != 0) {
            _state.value = State.Error("load($modelPath) rc=$loadRc")
            throw IllegalStateException("llama load failed: $loadRc")
        }
        val prepRc = prepare()
        if (prepRc != 0) {
            _state.value = State.Error("prepare rc=$prepRc")
            throw IllegalStateException("llama prepare failed: $prepRc")
        }
        _loadedModel.value = modelPath
        _state.value = State.ModelReady
        Log.i(TAG, "model ready: ${f.name}")
    }

    suspend fun setSystemPrompt(prompt: String) = withContext(dispatcher) {
        if (prompt.isBlank()) return@withContext
        require(_state.value is State.ModelReady) { "not ready: ${_state.value}" }
        _state.value = State.Processing
        val rc = processSystemPrompt(prompt)
        if (rc != 0) {
            _state.value = State.Error("processSystemPrompt rc=$rc")
            throw IllegalStateException("system prompt rc=$rc")
        }
        _state.value = State.ModelReady
    }

    /**
     * ユーザ発話を送って回答トークンを Flow<String> で流す。
     * Flow を cancel すれば生成も止まる (cancelFlag 経由)。
     */
    fun generate(userPrompt: String, predictLength: Int = 1024): Flow<String> = flow {
        require(_state.value is State.ModelReady) { "not ready: ${_state.value}" }
        require(userPrompt.isNotEmpty()) { "empty prompt" }

        _state.value = State.Processing
        cancelFlag = false
        val rc = processUserPrompt(userPrompt, predictLength)
        if (rc != 0) {
            _state.value = State.Error("processUserPrompt rc=$rc")
            return@flow
        }
        _state.value = State.Generating
        try {
            while (!cancelFlag) {
                val tok = generateNextToken() ?: break
                if (tok.isNotEmpty()) emit(tok)
            }
        } finally {
            _state.value = State.ModelReady
        }
    }.flowOn(dispatcher)

    fun requestCancel() { cancelFlag = true }

    suspend fun unloadModel() = withContext(dispatcher) {
        cancelFlag = true
        if (_loadedModel.value != null) {
            runCatching { unload() }
            _loadedModel.value = null
            _state.value = State.Initialized
        }
    }

    fun shutdownNow() {
        cancelFlag = true
        runBlocking(dispatcher) {
            when (_state.value) {
                is State.Uninitialized -> {}
                is State.Initialized -> shutdown()
                else -> { runCatching { unload() }; shutdown() }
            }
            _loadedModel.value = null
            _state.value = State.Uninitialized
        }
    }

    // ---- JNI 境界 (llama_jni.cpp と対応) ----------------------------------
    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun prepare(): Int
    private external fun systemInfo(): String
    private external fun processSystemPrompt(prompt: String): Int
    private external fun processUserPrompt(prompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()
}
