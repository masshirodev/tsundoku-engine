#!/usr/bin/env bash
# Runs the Gradle test suite in a throwaway JDK 21 container, as the
# Dockerfile's build stage does, so nothing needs installing on the host and
# no Gradle daemon or JVM is left running afterwards. On the workstation,
# Suwayomi's JVM runs as the same user, so "kill the java processes" after a
# host build is not something to do casually. The Gradle cache lives outside
# home (GRADLE_CACHE, default /mnt/hdd2/tsundoku/engine/gradle-home), because
# it grows to gigabytes.
set -euo pipefail

cd "$(dirname "$0")/.."
cache="${GRADLE_CACHE:-/mnt/hdd2/tsundoku/engine/gradle-home}"
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
