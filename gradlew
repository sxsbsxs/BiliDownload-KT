#!/usr/bin/env sh
# Lightweight Gradle 8.9 bootstrapper for environments where the wrapper JAR
# is not present. It downloads the official Gradle distribution once and reuses it.
set -eu
GRADLE_VERSION="8.9"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/manual-dists/gradle-$GRADLE_VERSION"
GRADLE_BIN="$DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$DIST_DIR"
  ZIP="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "Error: curl or wget is required to download Gradle $GRADLE_VERSION." >&2
    exit 1
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP" -d "$DIST_DIR"
  else
    echo "Error: unzip is required to extract Gradle $GRADLE_VERSION." >&2
    exit 1
  fi
fi
exec "$GRADLE_BIN" "$@"
