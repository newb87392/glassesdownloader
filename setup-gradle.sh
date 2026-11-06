#!/bin/sh
# Optional wrapper repair for Linux/macOS, mirroring Setup-Gradle.ps1.
#
# The Gradle wrapper JAR is already part of this archive, so you normally do NOT need
# this script. Run it only if gradle/wrapper/gradle-wrapper.jar is missing or the
# wrapper is broken. It downloads the official Gradle 8.9 distribution, verifies the
# published SHA256, and regenerates the wrapper with Gradle itself.
#
# Requires a JDK 17 (JAVA_HOME or java on PATH).
set -eu

PROJECT=$(cd "$(dirname "$0")" && pwd)
VERSION=8.9
URL="https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip"
EXPECTED=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
JAR_EXPECTED=498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/glassesdownloader-buildtools"
ZIP="$CACHE/gradle-${VERSION}-bin.zip"
GRADLE="$CACHE/gradle-${VERSION}/bin/gradle"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA=$(command -v java)
else
    echo "No Java found. Install JDK 17 (or set JAVA_HOME) and retry." >&2
    exit 1
fi

mkdir -p "$CACHE"
hash_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

if [ ! -f "$ZIP" ]; then
    echo "Downloading official Gradle $VERSION distribution (~130 MB)..."
    curl -fL --retry 3 -o "$ZIP" "$URL"
fi

actual=$(hash_of "$ZIP")
if [ "$actual" != "$EXPECTED" ]; then
    rm -f "$ZIP"
    echo "Gradle ZIP checksum mismatch. Expected $EXPECTED, got $actual. Download discarded." >&2
    exit 1
fi
echo "Gradle distribution SHA256 verified."

[ -x "$GRADLE" ] || unzip -q -o "$ZIP" -d "$CACHE"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf "rootProject.name = 'wrapper-bootstrap'\n" > "$tmp/settings.gradle"
(
    cd "$tmp"
    JAVA_HOME=$(dirname "$(dirname "$JAVA")") "$GRADLE" --offline --no-daemon -q \
        wrapper --gradle-version "$VERSION" --distribution-type bin
)
mkdir -p "$PROJECT/gradle/wrapper"
cp "$tmp/gradle/wrapper/gradle-wrapper.jar" "$PROJECT/gradle/wrapper/gradle-wrapper.jar"
[ -f "$tmp/gradlew" ] && cp "$tmp/gradlew" "$PROJECT/gradlew" && chmod +x "$PROJECT/gradlew"
[ -f "$tmp/gradlew.bat" ] && cp "$tmp/gradlew.bat" "$PROJECT/gradlew.bat"

jar_hash=$(hash_of "$PROJECT/gradle/wrapper/gradle-wrapper.jar")
if [ "$jar_hash" != "$JAR_EXPECTED" ]; then
    rm -f "$PROJECT/gradle/wrapper/gradle-wrapper.jar"
    echo "Generated wrapper JAR checksum mismatch: $jar_hash" >&2
    exit 1
fi
echo "Gradle wrapper generated and verified."
echo "Next: ./gradlew assembleDebug"
