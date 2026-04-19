#!/usr/bin/env bash
# Qwen3 GGUF を app/src/main/assets/models/ にダウンロード。
# APK に同梱してアプリ初回起動時に filesDir/models/ へ展開する想定。
#
# 使い方:
#   ./scripts/download_models.sh
#
# すでにダウンロード済みならスキップ。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/models"
mkdir -p "$DEST"

# Unsloth の GGUF ミラー (公式 Qwen GGUF リポは現在 gated)
# size は HEAD で取った Content-Length (bytes)
declare -a MODELS=(
    # 4B は 2.4GB で AGP の単一 asset 上限 (2GB) を越えるため、llama-gguf-split で
    # 事前に 2 分割した gguf のみをコミット。llama.cpp が 00001 を開けば後続を自動でロードする。
    # このスクリプトでダウンロードする必要はない (既にリポに入っている)。
    "Qwen3-0.6B-Q4_K_M.gguf|https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf|396705472"
    # Q4_0 は KleidiAI の ARM 最適化 matmul が効くので、対応 CPU では Q4_K_M より速い
    "Qwen3-0.6B-Q4_0.gguf|https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_0.gguf|382156480"
)

for entry in "${MODELS[@]}"; do
    IFS='|' read -r name url expected_size <<<"$entry"
    out="$DEST/$name"
    if [[ -f "$out" ]]; then
        actual=$(stat -c '%s' "$out" 2>/dev/null || stat -f '%z' "$out" 2>/dev/null || echo 0)
        if [[ "$actual" == "$expected_size" ]]; then
            echo "[skip] $name (already $actual bytes)"
            continue
        fi
        echo "[redo] $name (size mismatch: $actual != $expected_size)"
        rm -f "$out"
    fi
    echo "[get ] $name ($((expected_size / 1024 / 1024)) MB)"
    curl -L --fail --retry 3 --retry-delay 5 -o "$out.part" "$url"
    mv "$out.part" "$out"
done

echo
echo "==> models:"
ls -lh "$DEST"
