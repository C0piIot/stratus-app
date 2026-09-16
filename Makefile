# Stratus app.
#
# No JDK, no Gradle and no Android SDK on the host: every toolchain command runs
# in a container, so `docker` is the only hard prerequisite. `make doctor` says
# whether this machine can actually run it.
#
# Precedence for settings: command line > .env > defaults below.

-include .env

IMAGE  ?= stratus-app-toolchain

# The fast loop runs without the Android SDK, and therefore without emulation.
# Nothing in :core's JVM tests ever executes an Android binary, and AGP is happy
# to configure the module without an SDK as long as no Android task runs -- so
# these go native on any architecture. Measured on the ARM development box: 26
# seconds here against five minutes through the emulated toolchain image.
TEST_IMAGE ?= eclipse-temurin:21-jdk-noble
UID    ?= $(shell id -u)
GID    ?= $(shell id -g)

# Caches are bind mounts under .cache/, not named volumes: a fresh named volume
# is created root-owned and the toolchain runs as the invoking user, which could
# not then write to it. Same reasoning as the backend's Makefile.
CACHE_DIR := $(CURDIR)/.cache

# Run as the invoking user, or Gradle leaves build/ owned by root in the working
# tree, unremovable without sudo.
#
# No -t: a TTY injects carriage returns that break $(shell ...) captures.
#
# HOME is redirected because the invoking uid has no passwd entry in the image,
# so anything resolving a home directory would otherwise land in / and fail.
# DOCKER_EXTRA is how the conformance run joins the container network the
# backend is on, without a second copy of every flag below.
DOCKER_EXTRA ?=

DOCKER_RUN = docker run --rm \
	-u $(UID):$(GID) \
	$(DOCKER_EXTRA) \
	-v "$(CURDIR)":/src -w /src \
	-v "$(CACHE_DIR)/gradle":/gradle \
	-v "$(CACHE_DIR)/konan":/konan \
	-e HOME=/tmp \
	-e GRADLE_USER_HOME=/gradle \
	-e KONAN_DATA_DIR=/konan

# The wrapper is the source of truth for the Gradle version as soon as it
# exists; the one baked into the image is only there to create it.
GRADLE_CMD = $(if $(wildcard gradlew),./gradlew,gradle)

GRADLE = $(DOCKER_RUN) $(IMAGE) $(GRADLE_CMD) --no-daemon

.PHONY: help doctor toolchain gradle test conformance shell clean

help:
	@echo "make doctor     check this machine can run the toolchain"
	@echo "make toolchain  build the toolchain image"
	@echo "make test       shared tests, native and fast, no Android SDK"
	@echo "make conformance the same client against a real stratus-backend"
	@echo "make gradle ARGS='tasks'"
	@echo "make shell      a shell inside the toolchain"
	@echo "make clean      drop caches and build output"

# The Android SDK is x86_64-only, so on an ARM host the toolchain image runs
# under emulation and needs binfmt registered before it will start at all. The
# failure without it is an exec format error from a container that looked fine,
# which is worth one target to pre-empt.
doctor:
	@docker version --format '{{.Server.Arch}}' | grep -q amd64 \
	  && echo "host is x86_64: the toolchain runs natively" \
	  || { echo "host is $$(uname -m): the Android SDK has no build for it, so the"; \
	       echo "toolchain image is linux/amd64 and needs emulation."; \
	       docker run --rm --platform linux/amd64 alpine true 2>/dev/null \
	         && echo "emulation is registered: fine" \
	         || echo "NOT registered. Install it with:\n  docker run --privileged --rm tonistiigi/binfmt --install amd64"; }

# BUILD_CACHE is empty locally and set by CI to a buildx cache backend. Keeping
# it a variable rather than a second command is what stops CI and a laptop from
# building the image two different ways.
BUILD_CACHE ?=

toolchain:
	docker build $(BUILD_CACHE) -t $(IMAGE) .

$(CACHE_DIR)/gradle $(CACHE_DIR)/konan:
	@mkdir -p $@

gradle: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan
	$(GRADLE) $(ARGS)

test: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan
	$(DOCKER_RUN) $(TEST_IMAGE) $(GRADLE_CMD) --no-daemon $(or $(ARGS),:core:jvmTest)

# Starts a real backend, runs the suite against it, and tears it down whatever
# happens. See scripts/conformance.sh for why the image is pinned by digest.
conformance: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan
	TEST_IMAGE=$(TEST_IMAGE) scripts/conformance.sh

shell: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan
	$(DOCKER_RUN) -it $(IMAGE) bash

clean:
	rm -rf $(CACHE_DIR) build */build
