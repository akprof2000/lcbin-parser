#!/usr/bin/env bash
# Smoke-тест собранного jar: прогоняет реальные форматы входа (папка, zip, зашифрованный 7z, вложенный zip)
# и проверяет, что в CSV есть ожидаемые события. Используется в CI и локально:
#   scripts/smoke.sh target/lcbin-parser-<version>.jar
set -euo pipefail
JAR="${1:?usage: smoke.sh <jar>}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
RES="$HERE/src/test/resources/smoke"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }

echo "== usage error must exit 2"
set +e; java -jar "$JAR" >/dev/null 2>&1; code=$?; set -e
[ "$code" -eq 2 ] || fail "usage exit code $code"

echo "== folder with plain file, zip, encrypted 7z, nested zip"
java -jar "$JAR" "$RES" "$OUT/all" --password test --threads 2
ls "$OUT/all"
for f in sample.lcbin_1.csv sample.zip_sample.lcbin_1.csv sample-aes.7z_sample.lcbin_1.csv nested.zip_inner.zip_inner_sample2.lcbin_1.csv; do
  [ -f "$OUT/all/$f" ] || fail "missing $f"
  grep -q ',UE_TA,' "$OUT/all/$f" || fail "no TA rows in $f"
  grep -q 'measurementReport' "$OUT/all/$f" || fail "no MeasurementReport in $f"
  grep -q 'ueInformationResponse-r9' "$OUT/all/$f" || fail "no UEInformationResponse in $f"
  grep -q 'TRACE_SESSION_END' "$OUT/all/$f" || fail "no session end in $f"
  grep -q 'PARSE_ERROR' "$OUT/all/$f" && fail "PARSE_ERROR in $f"
  [ -f "$OUT/all/${f%.csv}.ue_ta.csv" ] || fail "missing ue_ta summary for $f"
done
# ровно 4 потока x 2 файла
[ "$(ls "$OUT/all" | wc -l)" -eq 8 ] || fail "expected 8 output files, got $(ls "$OUT/all" | wc -l)"

echo "== wrong password must fail with exit 1"
set +e; java -jar "$JAR" "$RES/sample-aes.7z" "$OUT/bad" --password wrong >/dev/null 2>&1; code=$?; set -e
[ "$code" -eq 1 ] || fail "wrong password exit code $code"

echo "== single file, ue-events block only, ';' separator, many threads"
java -jar "$JAR" "$RES/sample.lcbin_1" "$OUT/one" --blocks ue-events --sep ';' --threads 16 --no-summary
[ "$(ls "$OUT/one" | wc -l)" -eq 1 ] || fail "expected 1 output file"
head -1 "$OUT/one/sample.lcbin_1.csv" | grep -q '^record_index;record_offset' || fail "separator not applied"

echo "== deadlock regression: 40 copies, 1 thread, must finish in 60 s"
mkdir -p "$OUT/many"
for i in $(seq 1 40); do cp "$RES/sample.lcbin_1" "$OUT/many/ne$i.lcbin_1"; done
timeout 60 java -jar "$JAR" "$OUT/many" "$OUT/many-out" --threads 1 >/dev/null || fail "hung or failed with 1 thread"
[ "$(ls "$OUT/many-out" | wc -l)" -eq 80 ] || fail "expected 80 files"

echo "SMOKE OK"
