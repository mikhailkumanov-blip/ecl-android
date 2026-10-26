#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
model_dir="$project_dir/app/src/main/assets/tessdata"
mkdir -p "$model_dir"
# Official tessdata_fast 4.1.0, pinned to its verified release commit.
# Git blob hashes below verify the downloaded bytes; models are bundled into APK.
model_commit="65727574dfcd264acbb0c3e07860e4e9e9b22185"
for language in rus eng; do
  destination="$model_dir/$language.traineddata"
  if [[ ! -s "$destination" ]]; then
    temporary="$destination.tmp"
    trap 'rm -f -- "$temporary"' EXIT
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 \
      "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$model_commit/$language.traineddata" \
      --output "$temporary"
    test "$(wc -c < "$temporary")" -gt 100000
    mv -- "$temporary" "$destination"
    trap - EXIT
  fi
  case "$language" in
    rus) expected_blob="b146cb2263acbc6383f8e92ea0ce759537687bb8" ;;
    eng) expected_blob="bbef4675053b5b468cdb477053e28b1c698ba08e" ;;
  esac
  actual_blob="$(git hash-object --no-filters -- "$destination")"
  if [[ "$actual_blob" != "$expected_blob" ]]; then
    rm -f -- "$destination"
    printf 'OCR model checksum mismatch: %s\n' "$language" >&2
    exit 1
  fi
done
printf 'Bundled OCR models ready: rus + eng\n'
sha256sum "$model_dir/rus.traineddata" "$model_dir/eng.traineddata"
