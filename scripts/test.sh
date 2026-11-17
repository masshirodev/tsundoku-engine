#!/usr/bin/env bash
# Runs the Gradle test suite in a throwaway JDK 21 container, as the
# Dockerfile's build stage does, so nothing needs installing on the host and
# no Gradle daemon or JVM is left running afterwards: on a machine where
# another JVM runs as the same user (Suwayomi, an IDE), "kill the java
# processes" after a host build is not something to do casually. The Gradle
# cache grows to gigabytes; point GRADLE_CACHE elsewhere to keep it off the
# default, ${XDG_CACHE_HOME:-$HOME/.cache}/tsundoku-engine/gradle.
set -euo pipefail

cd "$(dirname "$0")/.."
cache="${GRADLE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/tsundoku-engine/gradle}"
mkdir -p "$cache"
docker build -q -t tsundoku-engine-test -f scripts/test.Dockerfile scripts >/dev/null

docker run --rm \
	--user "$(id -u):$(id -g)" \
	-e HOME=/gradle-home \
	-e GRADLE_USER_HOME=/gradle-home \
	-v "$cache:/gradle-home" \
	-v "$PWD:/work" \
	-w /work \
	tsundoku-engine-test \
	bash -c '[ -f AndroidCompat/lib/android.jar ] || ./AndroidCompat/getAndroid.sh; ./gradlew --no-daemon "$@"' \
	-- "${@:-test}"
