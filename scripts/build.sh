#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
bash scripts/prepare-ocr.sh
# Install Gradle 8.11.1 and JDK 17, set ANDROID_HOME before local use.
gradle --no-daemon :core:test :app:assembleDebug :app:lintDebug
