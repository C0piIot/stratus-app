#!/usr/bin/env bash
#
# Runs the conformance suite against a real stratus-backend in a container.
#
# Unit tests prove the client matches our idea of a server. This proves it
# matches the server, which is a different claim and the only one that catches a
# protocol assumption we got wrong.
set -euo pipefail

cd "$(dirname "$0")/.."

# Pinned by digest rather than by tag. A moving `main` would break this repo's
# CI for reasons belonging to another repo, and the two tests that pin current
# server limitations would flip without anybody deciding to look. Bumping this
# is a commit, which is the point.
IMAGE="${BACKEND_IMAGE:-ghcr.io/c0piiot/stratus-backend@sha256:c039e7b1034e5205c6696e95afff46289a586a509e2514d1aae48bc89ef846b3}"

DAV_USER="${DAV_USER:-conformance}"
DAV_PASS="${DAV_PASS:-conformance-secret}"
PORT="${PORT:-18099}"

SUFFIX="$$"
NET="stratus-conformance-$SUFFIX"
NAME="stratus-conformance-$SUFFIX"
DATA="$(mktemp -d)"

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$DATA"
}
trap cleanup EXIT

docker network create "$NET" >/dev/null

# Runs as the invoking user because the data directory is a bind mount created
# here; the image's own nonroot uid could not write to it.
docker run -d --name "$NAME" --network "$NET" \
    --user "$(id -u):$(id -g)" \
    -p "127.0.0.1:$PORT:8080" \
    -e STRATUS_ADDR=:8080 \
    -e STRATUS_DATA_DIR=/data \
    -e STRATUS_USERNAME="$DAV_USER" \
    -e STRATUS_PASSWORD="$DAV_PASS" \
    -v "$DATA:/data" \
    "$IMAGE" >/dev/null

# Waiting for the port to answer rather than sleeping a fixed amount: a constant
# is either too short on a loaded runner or wasted on an idle one.
ready=
for _ in $(seq 120); do
    if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then
        ready=1
        break
    fi
    sleep 0.5
done
if [ -z "$ready" ]; then
    echo "the backend never answered on port $PORT" >&2
    docker logs "$NAME" 2>&1 | tail -30 >&2
    exit 1
fi

# The tests reach the server by container name on a shared network, so nothing
# depends on how the host resolves anything.
make gradle \
    IMAGE="${TEST_IMAGE:-eclipse-temurin:21-jdk-noble}" \
    ARGS=":core:jvmTest -Pconformance" \
    DOCKER_EXTRA="--network $NET \
        -e STRATUS_TEST_URL=http://$NAME:8080/dav/ \
        -e STRATUS_TEST_USER=$DAV_USER \
        -e STRATUS_TEST_PASS=$DAV_PASS"
