#!/usr/bin/env sh
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_VERSION="8.9"
DIST_DIR="$ROOT_DIR/.gradle-dist"
DIST_ZIP="$DIST_DIR/gradle-${GRADLE_VERSION}-bin.zip"
DIST_HOME="$DIST_DIR/gradle-${GRADLE_VERSION}"
if [ ! -x "$DIST_HOME/bin/gradle" ]; then
  mkdir -p "$DIST_DIR"
  if [ ! -f "$DIST_ZIP" ]; then
    command -v curl >/dev/null 2>&1 || { echo "curl is required to bootstrap Gradle ${GRADLE_VERSION}." >&2; exit 1; }
    echo "Downloading Gradle ${GRADLE_VERSION}..." >&2
    curl -fL --retry 3 -o "$DIST_ZIP" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to bootstrap Gradle ${GRADLE_VERSION}." >&2; exit 1; }
  rm -rf "$DIST_HOME"
  unzip -q "$DIST_ZIP" -d "$DIST_DIR"
fi
exec "$DIST_HOME/bin/gradle" "$@"
