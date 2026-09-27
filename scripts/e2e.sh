#!/usr/bin/env bash
#
# Runs the whole app on an emulator against a real stratus-backend
# (stratus-app#78).
#
# The emulator reaches its host at 10.0.2.2, and its host is the toolchain
# container -- so that container shares the host's network and the backend is
# published on the host's loopback. The tests put a proxy of their own between
# the app and that address, which is how a server disappears or stops answering
# on cue without anything out here having to be told.
set -euo pipefail

cd "$(dirname "$0")/.."

# The same pin as the conformance suite, for the same reason.
IMAGE="${BACKEND_IMAGE:-$(sed -nE 's/^IMAGE="\$\{BACKEND_IMAGE:-(.*)\}"$/\1/p' scripts/conformance.sh)}"
PORT="${PORT:-18098}"
USER_NAME="e2e"
PASSWORD="e2e-secret"
RUN_AS="${RUN_AS:-$(id -u):$(id -g)}"

NAME="stratus-e2e-$$"
DATA="$(mktemp -d)"
cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    rm -rf "$DATA"
}
trap cleanup EXIT

docker run -d --name "$NAME" --user "$RUN_AS" \
    -p "127.0.0.1:$PORT:8080" \
    -e STRATUS_ADDR=:8080 -e STRATUS_DATA_DIR=/data \
    -e STRATUS_USERNAME="$USER_NAME" -e STRATUS_PASSWORD="$PASSWORD" \
    -v "$DATA:/data" "$IMAGE" >/dev/null

for _ in $(seq 120); do
    curl -fsS -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null && break
    sleep 0.5
done

make gradle \
    ARGS=":androidApp:emulatorDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.stratusPort=$PORT \
        -Pandroid.testInstrumentationRunnerArguments.stratusUser=$USER_NAME \
        -Pandroid.testInstrumentationRunnerArguments.stratusPassword=$PASSWORD" \
    DOCKER_EXTRA="--network host --device /dev/kvm \
        -v $PWD/.cache/android-sdk/system-images:/opt/android-sdk/system-images"
