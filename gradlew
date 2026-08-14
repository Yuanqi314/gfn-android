#!/usr/bin/env sh
set -eu

VERSION="9.5.0"
EXPECTED_SHA256="553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/gfn-bootstrap"
DIST="$BASE/gradle-$VERSION"
ZIP="$BASE/gradle-$VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"

if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  command -v curl >/dev/null 2>&1 || { echo "需要 curl 才能自动下载 Gradle" >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "需要 unzip 才能解压 Gradle" >&2; exit 1; }
  echo "正在下载 Gradle $VERSION：$URL" >&2
  curl -fL "$URL" -o "$ZIP"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$ZIP" | awk '{print $1}')
  else
    echo "需要 sha256sum 或 shasum 才能校验 Gradle" >&2
    exit 1
  fi
  [ "$EXPECTED_SHA256" = "$ACTUAL" ] || { echo "Gradle 分发包 SHA-256 校验失败" >&2; exit 1; }
  rm -rf "$DIST"
  unzip -q "$ZIP" -d "$BASE"
fi

exec "$DIST/bin/gradle" "$@"
