#!/usr/bin/env sh
set -eu

VERSION="9.5.0"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/gfn-bootstrap"
DIST="$BASE/gradle-$VERSION"
ZIP="$BASE/gradle-$VERSION-bin.zip"
SHA="$ZIP.sha256"
URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"

if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  command -v curl >/dev/null 2>&1 || { echo "curl is required to bootstrap Gradle" >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to bootstrap Gradle" >&2; exit 1; }
  echo "Bootstrapping Gradle $VERSION from $URL" >&2
  curl -fL "$URL" -o "$ZIP"
  curl -fL "$URL.sha256" -o "$SHA"
  EXPECTED=$(tr -d '[:space:]' < "$SHA")
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$ZIP" | awk '{print $1}')
  else
    echo "sha256sum or shasum is required to verify Gradle" >&2
    exit 1
  fi
  [ "$EXPECTED" = "$ACTUAL" ] || { echo "Gradle distribution checksum mismatch" >&2; exit 1; }
  rm -rf "$DIST"
  unzip -q "$ZIP" -d "$BASE"
fi

exec "$DIST/bin/gradle" "$@"
