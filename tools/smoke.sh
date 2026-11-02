#!/usr/bin/env bash
# JaCoCo and Cobertura in, percentages and a threshold out.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# 1. JaCoCo: 30 covered / 10 missed lines, 4/4 branches
dir="$(mktemp -d)"; mkdir -p "$dir/target/site/jacoco"
cat >"$dir/target/site/jacoco/jacoco.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
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
<!DOCTYPE coverage SYSTEM "http://cobertura.sourceforge.net/xml/coverage-04.dtd">
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

# 5. lcov: two records, LF/LH order swapped in the second one, branches too
dir="$(mktemp -d)"; mkdir -p "$dir/build/coverage"
cat >"$dir/build/coverage/lcov.info" <<'LCOV'
TN:
SF:src/calc.c
LF:10
LH:8
BRF:4
BRH:2
end_of_record
TN:
SF:src/main.c
LH:5
LF:5
BRF:0
BRH:0
end_of_record
LCOV
printf '{"pattern": "**/lcov.info"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "lcov run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" lineCoverage)" = "86.67" ] || fail "lcov line coverage must be 13/15: $(cat "$dir/output.json")"
[ "$(output "$dir" branchCoverage)" = "50.00" ] || fail "lcov branch coverage: $(cat "$dir/output.json")"

# 6. Sonar generic format: per-line, with branches on some lines
dir="$(mktemp -d)"
cat >"$dir/generic-coverage.xml" <<'SONAR'
<coverage version="1">
  <file path="src/app.py">
    <lineToCover lineNumber="1" covered="true"/>
    <lineToCover lineNumber="2" covered="true" branchesToCover="2" coveredBranches="1"/>
    <lineToCover lineNumber="3" covered="false"/>
    <lineToCover lineNumber="4" covered="false" branchesToCover="2" coveredBranches="0"/>
  </file>
</coverage>
SONAR
printf '{"pattern": "**/generic-coverage.xml", "minLine": "60"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "50%% lines is under a 60%% gate, it must fail"
grep -q "below the threshold" "$dir/output.json" || fail "gate message: $(cat "$dir/output.json")"
printf '{"pattern": "**/generic-coverage.xml"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "sonar run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" lineCoverage)" = "50.00" ] || fail "sonar line coverage: $(cat "$dir/output.json")"
[ "$(output "$dir" branchCoverage)" = "25.00" ] || fail "sonar branch coverage: $(cat "$dir/output.json")"

# a truncated report must not be averaged into the percentage the gate runs on
dir="$(mktemp -d)"
printf '<report name="good"><counter type="LINE" missed="0" covered="10"/></report>' >"$dir/jacoco.xml"
printf '<report name="half"><counter type="LINE" missed="0"' >"$dir/jacoco-old.xml"
printf '{"pattern": "*.xml", "minLine": "50"}' >"$dir/input.json"
if run "$dir" >/dev/null 2>&1; then fail "a truncated coverage report must fail the gate"; fi
grep -q '"errorCode":40003' "$dir/output.json" || fail "expected errorCode 40003: $(cat "$dir/output.json")"
grep -q 'jacoco-old.xml' "$dir/output.json" || fail "the failure must name the file: $(cat "$dir/output.json")"
printf '{"pattern": "*.xml", "minLine": "50", "allowUnreadableReports": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "allowUnreadableReports should let it through: $(cat "$dir/output.json")"

# Clover: <coverage><project><metrics> — the counts are statements, not rates
dir="$(mktemp -d)"
cat >"$dir/clover.xml" <<'XML'
<coverage generated="1793600000000" clover="4.5.2">
  <project timestamp="1793600000000" name="demo">
    <metrics statements="200" coveredstatements="150" conditionals="40" coveredconditionals="30"
             methods="20" coveredmethods="15" elements="260" coveredelements="195" complexity="0"/>
    <package name="demo">
      <file name="Calc.java">
        <metrics statements="10" coveredstatements="1" conditionals="2" coveredconditionals="0"/>
      </file>
    </package>
  </project>
</coverage>
XML
printf '{"pattern": "*.xml", "minLine": "70"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "clover: $(cat "$dir/output.json")"
[ "$(output "$dir" lineCoverage)" = "75.00" ] || fail "clover line coverage: $(cat "$dir/output.json")"
[ "$(output "$dir" branchCoverage)" = "75.00" ] || fail "clover branch coverage: $(cat "$dir/output.json")"
# the per-file <metrics> must not be mistaken for the project total
grep -q '"lineCoverage":{"type":"string","value":"75.00"}' "$dir/output.json" \
  || fail "a file-level metrics node was used as the total: $(cat "$dir/output.json")"

echo "smoke ok: jacoco counters, threshold gate, cobertura rates, missing report, lcov records, sonar generic, clover, truncated report fails by default"
