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
# A second server with the same user and another password, which is what a
# server whose password changed looks like from the app (stratus-app#87).
PORT2="${PORT2:-18097}"
PASSWORD2="e2e-secret-2"
NAME2="stratus-e2e-two-$$"
DATA2="$(mktemp -d)"
cleanup() {
    status=$?
    # The backend's side of whatever went wrong, which the test report cannot show.
    if [ "$status" -ne 0 ]; then
        echo "--- backend at the end of the run" >&2
        docker ps -a --filter "name=$NAME" >&2 || true
        # All of it, not a tail: a run is a quarter of an hour of fourteen
        # tests, and the last forty lines are whichever one happened to finish
        # last rather than the one that failed.
        docker logs "$NAME" 2>&1 >&2 || true
    fi
    docker rm -f "$NAME" "$NAME2" >/dev/null 2>&1 || true
    rm -rf "$DATA" "$DATA2"
}
trap cleanup EXIT

docker run -d --name "$NAME" --user "$RUN_AS" \
    -p "127.0.0.1:$PORT:8080" \
    -e STRATUS_ADDR=:8080 -e STRATUS_DATA_DIR=/data \
    -e STRATUS_USERNAME="$USER_NAME" -e STRATUS_PASSWORD="$PASSWORD" \
    -v "$DATA:/data" "$IMAGE" >/dev/null
docker run -d --name "$NAME2" --user "$RUN_AS" \
    -p "127.0.0.1:$PORT2:8080" \
    -e STRATUS_ADDR=:8080 -e STRATUS_DATA_DIR=/data \
    -e STRATUS_USERNAME="$USER_NAME" -e STRATUS_PASSWORD="$PASSWORD2" \
    -v "$DATA2:/data" "$IMAGE" >/dev/null

# Asking /healthz for its answer rather than for a connection: docker's proxy
# accepts on the published port before anything inside is listening.
for port in "$PORT" "$PORT2"; do
    ready=
    for _ in $(seq 120); do
        if [ "$(curl -s "http://127.0.0.1:$port/healthz" 2>/dev/null)" = "ok" ]; then
            ready=1
            break
        fi
        sleep 0.5
    done
    if [ -z "$ready" ]; then
        echo "the backend never answered on port $port" >&2
        exit 1
    fi
    echo "backend ready on 127.0.0.1:$port"
done

make gradle \
    ARGS=":androidApp:emulatorDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.stratusPort=$PORT \
        -Pandroid.testInstrumentationRunnerArguments.stratusUser=$USER_NAME \
        -Pandroid.testInstrumentationRunnerArguments.stratusPassword=$PASSWORD \
        -Pandroid.testInstrumentationRunnerArguments.stratusPort2=$PORT2 \
        -Pandroid.testInstrumentationRunnerArguments.stratusPassword2=$PASSWORD2" \
    DOCKER_EXTRA="--network host --device /dev/kvm \
        -v $PWD/.cache/android-sdk/system-images:/opt/android-sdk/system-images"
