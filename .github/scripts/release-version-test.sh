#!/usr/bin/env bash
# Tests release-version.sh against a throwaway repository.
set -uo pipefail

script="$(cd "$(dirname "$0")" && pwd)/release-version.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

# expect <description> <exit status> <stdout> <arguments of the script...>
expect() {
  local description="$1" expected_status="$2" expected_output="$3" output status
  shift 3
  output="$("$script" "$@" 2>/dev/null)"
  status=$?
  if [ "$status" -eq "$expected_status" ] && [ "$output" = "$expected_output" ]; then
    echo "ok   $description"
  else
    echo "FAIL $description: status $status, output '$output'"
    failures=$((failures + 1))
  fi
}

git init -q --bare "$work/origin.git"
git init -q -b main "$work/repo"
cd "$work/repo" || exit 1
git config user.name test
git config user.email test@example.invalid
git config commit.gpgsign false
git config tag.gpgsign false
git remote add origin "$work/origin.git"
git commit -q --allow-empty -m "first"
git push -q origin main
git tag -a v1.0.1 -m "Version 1.0.1"
git tag v1.0.2
git tag -a v1.0 -m "two parts"
git tag -a v01.0.0 -m "leading zero"
git tag -a 1.0.3 -m "no prefix"
git tag -a v1.0.1-rc1 -m "suffix"
git checkout -q -b side
git commit -q --allow-empty -m "off main"
git tag -a v1.0.4 -m "off main"
git checkout -q main

expect "annotated tag on main" 0 "15.7.0-1.0.1" v1.0.1 15.7.0
expect "lightweight tag" 1 "" v1.0.2 15.7.0
expect "two parts" 1 "" v1.0 15.7.0
expect "leading zero" 1 "" v01.0.0 15.7.0
expect "no prefix" 1 "" 1.0.3 15.7.0
expect "suffix" 1 "" v1.0.1-rc1 15.7.0
expect "tag off main" 1 "" v1.0.4 15.7.0
expect "missing tag" 1 "" v9.9.9 15.7.0
expect "malformed Fess version" 1 "" v1.0.1 15.7
expect "snapshot Fess version" 1 "" v1.0.1 15.7.0-SNAPSHOT
expect "missing argument" 2 "" v1.0.1

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all tests passed"
