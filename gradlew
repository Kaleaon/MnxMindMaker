#!/usr/bin/env sh
set -eu

if ! command -v gradle >/dev/null 2>&1; then
  echo "ERROR: 'gradle' was not found in PATH." >&2
  echo "Install Gradle 8.2.1+ and ensure it is available on PATH." >&2
  exit 1
fi

extract_java_major() {
  version_line="$1"
  version="$(printf '%s' "$version_line" | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
  [ -n "$version" ] || return 1
  major="$(printf '%s' "$version" | cut -d. -f1)"
  if [ "$major" = "1" ]; then
    major="$(printf '%s' "$version" | cut -d. -f2)"
  fi
  printf '%s' "$major"
}

maybe_set_compatible_java_home() {
  current_java_line="$(java -version 2>&1 | head -n 1 || true)"
  current_major="$(extract_java_major "$current_java_line" || true)"
  [ -n "${current_major:-}" ] || return 0

  if [ "$current_major" -le 21 ]; then
    return 0
  fi

  for candidate in \
    "$HOME/.local/share/mise/installs/java/21.0.2" \
    "$HOME/.local/share/mise/installs/java/21" \
    "$HOME/.local/share/mise/installs/java/17.0.2" \
    "$HOME/.local/share/mise/installs/java/17"
  do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      echo "INFO: Detected Java $current_major; using JAVA_HOME=$JAVA_HOME for Gradle compatibility." >&2
      return 0
    fi
  done

  echo "WARNING: Detected Java $current_major. Use Java 17-21 when running Gradle for this project." >&2
}

maybe_set_compatible_java_home

exec gradle "$@"
