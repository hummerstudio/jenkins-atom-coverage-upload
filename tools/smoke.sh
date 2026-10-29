#!/usr/bin/env bash
# JaCoCo and Cobertura in, percentages and a threshold out.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# 1. JaCoCo: 30 covered / 10 missed lines, 4/4 branches
dir="$(mktemp -d)"; mkdir -p "$dir/target/site/jacoco"
cat >"$dir/target/site/jacoco/jacoco.xml" <<'XML'
<report name="parcel">
  <package name="demo"><class name="demo/A"><counter type="LINE" missed="10" covered="30"/></class></package>
  <counter type="LINE" missed="10" covered="30"/>
  <counter type="BRANCH" missed="0" covered="4"/>
</report>
XML
printf '{"pattern": "**/jacoco.xml", "label": "Coverage", "minLine": "50", "minBranch": "50"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "jacoco run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" lineCoverage)" = "75.00" ] || fail "line coverage: $(cat "$dir/output.json")"
[ "$(output "$dir" branchCoverage)" = "100.00" ] || fail "branch coverage: $(cat "$dir/output.json")"
[ -f "$dir/out/coverage/index.html" ] || fail "no HTML report"
grep -q "75.00%" "$dir/out/coverage/index.html" || fail "report does not show the number"

# 2. the threshold fails the build, and the report is still published
dir="$(mktemp -d)"; mkdir -p "$dir/reports"
cat >"$dir/reports/jacoco.xml" <<'XML'
<report name="x"><counter type="LINE" missed="40" covered="60"/><counter type="BRANCH" missed="0" covered="0"/></report>
XML
printf '{"pattern": "reports/jacoco.xml", "minLine": "80"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "60%% < 80%% must fail the atom"
[ "$(status "$dir")" = "failure" ] || fail "status must be failure: $(cat "$dir/output.json")"
[ -f "$dir/out/coverage/index.html" ] || fail "the report must still be published on failure"

# 3. Cobertura rates are read from the root element
dir="$(mktemp -d)"; mkdir -p "$dir/coverage"
cat >"$dir/coverage/cobertura-coverage.xml" <<'XML'
<coverage line-rate="0.8" branch-rate="0.5" version="1"><packages/></coverage>
XML
printf '{"pattern": "**/cobertura-coverage.xml", "minLine": "70"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "cobertura run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" lineCoverage)" = "80.00" ] || fail "cobertura line: $(cat "$dir/output.json")"
[ "$(output "$dir" branchCoverage)" = "50.00" ] || fail "cobertura branch: $(cat "$dir/output.json")"

# 4. no report at all is a clear error
dir="$(mktemp -d)"
printf '{"pattern": "**/jacoco.xml"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing report must fail"
grep -q "no coverage report matched" "$dir/output.json" || fail "message not readable"

echo "smoke ok: jacoco counters, threshold gate, cobertura rates, missing report"
