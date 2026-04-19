<div align="center">

# LocalLLM

### Android on-device LLM チャットアプリ

[![Platform](https://img.shields.io/badge/Platform-Android%2028%2B-3DDC84?style=flat&logo=android&logoColor=white)](#)
[![Inference](https://img.shields.io/badge/Inference-llama.cpp%20%2B%20JNI-000000?style=flat)](https://github.com/ggml-org/llama.cpp)
[![CPU](https://img.shields.io/badge/CPU-arm64--v8a%20%2B%20KleidiAI-blue?style=flat)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-green?style=flat)](#license)

**端末内で完結する LLM チャット。サーバ無し、通信無し。**

</div>

---

## これ何

Xiaomi 13T (Dimensity 8200-Ultra, 8GB RAM) で LLM をローカルで回して ChatGPT 風に話す、というのを Android ネイティブでやりたかったので作ったアプリ。llama.cpp を JNI で同一プロセスに組み込んで推論する。

同じ作者の [`cUDGk/drift`](https://github.com/cUDGk/drift) — 完全ローカル型 AI チャットアプリ (Android) の構想 — の **動く最小プロトタイプ** にあたる。drift が「階層化メモリ / キャラクター管理 / 会話が端末外に出ない」という設計ドキュメント段階のプロダクトなのに対して、こっちはそのうち「オンデバイスで llama.cpp を回す部分」だけを手早く実装した実機検証用のコードベース。

## スタック

| 層 | 採用 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| ナビ | Navigation Compose + Room (Conversation / Message) |
| 設定 | DataStore Preferences |
| 推論 | llama.cpp (git submodule) を `externalNativeBuild` で CMake 直リンク、JNI で呼ぶ |
| CPU | arm64-v8a 固定、GGML `CPU_ALL_VARIANTS` + `CPU_KLEIDIAI` |
| 量子化 | Q4_0 (KleidiAI の ARM 最適化 matmul 経路) を優先 |
| プロセスモデル | 全部アプリ本体プロセスの中。子プロセスは spawn しない |

## 同梱モデル

| ファイル | 容量 | 用途 |
|---|---|---|
| `Qwen2.5-0.5B-Instruct-Q4_0.gguf` | 337 MB | デフォルト・最速・即答 |
| `Llama-3.2-1B-Instruct-Q4_0.gguf` | 738 MB | 速度と賢さのバランス |
| `Qwen3-0.6B-Q4_0.gguf` | 365 MB | 思考モード (`<think>` 折り畳み表示) |

モデル本体は `.gitignore` で除外。`scripts/download_models.sh` で Hugging Face (unsloth ミラー) から取得して `app/src/main/assets/models/` に置く前提。

## 開発メモ (踏んだ地雷)

### 1. llama-server を子プロセス spawn したら OS に殺される

初期実装は `llama-server` 実行ファイルを `app/src/main/jniLibs/arm64-v8a/libllama-server.so` に仕込んで `ProcessBuilder` で起動し、OpenAI 互換 HTTP で叩く形だった。ロードまで通るのに **30 秒後に必ず落ちる**。logcat を追うと:

```
ActivityManager: Process PhantomProcessRecord {... libllama-server.so/u0a305} died
```

Android 12+ の **Phantom Process Killer** (ActivityManager の管理外で fork された子プロセスを自動で殺す機構) の餌食。MIUI が `adb shell device_config put activity_manager max_phantom_processes` 系の回避策を全部拒否するので、JNI 組み込みにして同一プロセスで動かす方針に変えた。

### 2. AGP は 2GB を超える単一アセットでコケる

Qwen3-4B Q4_K_M (約 2.4GB) を `assets/models/` に置くと `compressDebugAssets` で Java の `byte[]` 上限 (INT_MAX = 2GB) に当たって死ぬ。`llama-gguf-split --split-max-size 1800M` で事前分割した GGUF を 2 枚同梱すれば、llama.cpp が 00001 を開くと自動で後続を読み込んでくれる。

ただ APK 全体が 4GB を超えると今度は **ZIP32 の中央ディレクトリオフセット上限** (`Zip32 cannot place Central directory at offset ... (MAX=4294967295)`) に当たる。小さいモデルだけに絞って 1.5GB APK に落ち着かせた。

### 3. W^X でアプリデータ領域からは `execve` できない

`filesDir/bin/llama-server` に chmod 700 で置いても `error=13, Permission denied`。Android 10+ は SELinux + W^X で `/data/data/<pkg>/` 以下の実行を禁止する。実行可能なのは `nativeLibraryDir` (= APK 内 `lib/<abi>/*.so` が install 時展開される場所) だけ。バイナリを `libllama-server.so` という名前で `jniLibs` に入れ、`packaging { jniLibs { useLegacyPackaging = true } }` で強制抽出すれば exec 可能になる。

…が、そもそも [[1]](#1-llama-server-を子プロセス-spawn-したら-os-に殺される) の理由で子プロセス方式はやめたので現在は使っていない。

### 4. `Scaffold` + `enableEdgeToEdge` + `imePadding` で IME 起動時に二段ぶち上がり

Compose の `Scaffold` に `bottomBar` を入れて、その `bottomBar` に `Modifier.imePadding()` を当てる標準パターンをやると、システム側の `adjustResize` と Compose の `imePadding` が両方効いて Composer が IME の **2 倍** 上に跳ねる。

解決: Manifest で `android:windowSoftInputMode="adjustNothing"` にしてシステム側のリサイズを止め、Scaffold をやめて手動 `Column` レイアウトにして、Composer にだけ `imePadding()` + `navigationBarsPadding()` を当てる。

### 5. Qwen3 の thinking モードは遅い

Qwen3-0.6B は reasoning モデルなので放っておくと `<think>...</think>` ブロックを長々と出してから答える。生成トークン数が 3〜5 倍になって体感で遅い。Qwen 公式仕様の `/no_think` をユーザ発話の頭に自動挿入して reasoning pass をスキップするようにした (`ChatRepository`)。`<think>` タグはストリーミング中にパースして UI 側で折り畳み表示。

## ビルド

```bash
# 初回だけ: submodule 取得
git submodule update --init --recursive

# モデルを取ってくる (app/src/main/assets/models/ に置かれる)
./scripts/download_models.sh

# 組み立て
./gradlew :app:assembleDebug

# 転送 (USB デバッグ ON、開発者オプション必要)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

初回起動時に GGUF を `assets` → `filesDir/models/` へ展開する (10〜30 秒)。`nativeLibraryDir` から `liblocalllm-jni.so` + `libllama.so` + `libggml*.so` が自動で mmap / dlopen される。

## 実機での tok/s

Xiaomi 13T, CPU のみ, 4 threads, Q4_0, FA auto:

| モデル | 生成速度 (目安) |
|---|---|
| Qwen2.5-0.5B-Instruct | 25〜35 tok/s |
| Qwen3-0.6B (no_think) | 20〜30 tok/s |
| Llama-3.2-1B-Instruct | 12〜18 tok/s |

数字は暫定。キャッシュ温度や同時起動アプリで揺れる。

## ディレクトリ

```
.
├── app/
│   ├── build.gradle.kts          # externalNativeBuild + CMake path
│   └── src/main/
│       ├── AndroidManifest.xml   # FG Service + adjustNothing IME
│       ├── assets/models/        # GGUF 同梱 (gitignored)
│       ├── cpp/
│       │   ├── CMakeLists.txt    # llama.cpp add_subdirectory
│       │   ├── llama_jni.cpp     # JNI 境界 (llama.android 例を改変)
│       │   └── logging.h
│       └── java/com/localllm/app/
│           ├── data/             # Room / DataStore / AssetExtractor
│           ├── llama/            # LLMEngine (JNI wrapper) + FG Service
│           └── ui/               # Compose: ChatScreen / SettingsScreen
├── third_party/llama.cpp/        # git submodule
└── scripts/
    ├── build_llama_android.sh    # ※ 旧: 別プロセス方式の名残、現在は未使用
    └── download_models.sh        # HF から GGUF をダウンロード
```

## 関連

- [`cUDGk/drift`](https://github.com/cUDGk/drift) — このプロジェクトの上位互換コンセプト。階層化メモリ・キャラクター管理・完全ローカル設計の構想リポ。本リポはそのうちの「llama.cpp を Android で動かす」部分の現実可動コード。
- [`ggml-org/llama.cpp`](https://github.com/ggml-org/llama.cpp) — 推論コア。`examples/llama.android/` の JNI 実装を土台にしている。

## License

MIT
