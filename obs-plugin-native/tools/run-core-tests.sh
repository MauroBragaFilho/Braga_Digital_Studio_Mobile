#!/usr/bin/env bash
# Compila e roda os testes da logica pura (core/) com g++ ou clang++, SEM OBS e SEM Qt.
# Uso: tools/run-core-tests.sh   (a partir de qualquer pasta)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CXX="${CXX:-g++}"
# no Windows (MinGW) as DLLs do compilador precisam estar no PATH para rodar o .exe
export PATH="$(dirname "$(command -v "$CXX")"):$PATH"
OUT="$ROOT/build_core_tests"
mkdir -p "$OUT"
EXTRA=""
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXTRA="-lcrypt32" ;; esac
"$CXX" -std=c++17 -Wall -Wextra -Wpedantic -O1 -I"$ROOT/core" -I"$ROOT/tests" \
  "$ROOT"/core/*.cpp "$ROOT"/tests/*.cpp -o "$OUT/tests.exe" $EXTRA
"$OUT/tests.exe"
