#!/usr/bin/env sh
set -eu

if ! command -v gradle >/dev/null 2>&1; then
  echo "ERROR: 'gradle' was not found in PATH." >&2
  echo "Install Gradle 8.2.1+ and ensure it is available on PATH." >&2
  exit 1
fi

exec gradle "$@"
