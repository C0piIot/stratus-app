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

# The user the *backend* containers of the conformance run get, kept as one
# variable rather than a second `id -u` inside the script -- so that a host
# where the invoking uid is not the right answer says so once. A rootless
# podman with no subuid ranges is such a host: its user namespace holds a
# single uid, container root is the invoking user, and asking for any other
# uid is refused by the runtime rather than merely inconvenient.
RUN_AS ?= $(UID):$(GID)

# Caches are bind mounts under .cache/, not named volumes: a fresh named volume
# is created root-owned and the toolchain runs as the invoking user, which could
# not then write to it. Same reasoning as the backend's Makefile.
CACHE_DIR := $(CURDIR)/.cache

# The emulator's system image is a cache rather than a layer in the toolchain
# image, and size is the whole reason: 586 MiB to download and 8.2 GB once
# unpacked, which would take the image from under two gigabytes to nearly
# eleven -- and `BUILD_CACHE` pushes every layer of it to the Actions cache with
# `mode=max`, against the ten gigabytes GitHub keeps for a repository.
#
# The emulator itself is a layer instead, because sdkmanager cannot install onto
# a mount point; the Dockerfile says why. Build-tools stays a layer too, which is
# why the Dockerfile pins the version AGP asks for rather than moving it here.
SDK_CACHE := $(CACHE_DIR)/android-sdk

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
	-e KONAN_DATA_DIR=/konan \
	-e STRATUS_KEYSTORE_BASE64 -e STRATUS_KEYSTORE_PASSWORD

# The wrapper is the source of truth for the Gradle version as soon as it
# exists; the one baked into the image is only there to create it.
GRADLE_CMD = $(if $(wildcard gradlew),./gradlew,gradle)

GRADLE = $(DOCKER_RUN) $(IMAGE) $(GRADLE_CMD) --no-daemon

.PHONY: help doctor toolchain gradle test conformance device-test shell clean

help:
	@echo "make doctor     check this machine can run the toolchain"
	@echo "make toolchain  build the toolchain image"
	@echo "make test       shared tests and the iOS sources, native and fast"
	@echo "make conformance the same client against a real stratus-backend"
	@echo "make device-test the Android halves on an emulator, cached after one run"
	@echo "make gradle ARGS='tasks'"
	@echo "make shell      a shell inside the toolchain"
	@echo "make clean      drop caches and build output, the emulator's included"

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
	@uname -m | grep -q x86_64 \
	  && echo "Kotlin/Native compiles the iOS sources here" \
	  || echo "Kotlin/Native does NOT support this host, so 'make test' skips the iOS\nsources and only CI compiles them. Nothing to install: it is the architecture."

# BUILD_CACHE is empty locally and set by CI to a buildx cache backend. Keeping
# it a variable rather than a second command is what stops CI and a laptop from
# building the image two different ways.
BUILD_CACHE ?=

# The builder is a variable because `docker build` is not always the one that
# works. Docker 29's CLI routes it to buildx, whose default driver keeps the
# result in a build cache rather than in the image store, and its legacy builder
# unpacks the context through the daemon API -- which a rootless podman with no
# subuid ranges refuses, since the context carries files owned by a uid its user
# namespace does not contain. `podman build` has neither problem.
DOCKER_BUILD ?= docker build

toolchain:
	$(DOCKER_BUILD) $(BUILD_CACHE) -t $(IMAGE) .

$(CACHE_DIR)/gradle $(CACHE_DIR)/konan $(SDK_CACHE)/system-images:
	@mkdir -p $@

gradle: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan
	$(GRADLE) $(ARGS)

# The default also compiles the iOS source sets, which is possible without a Mac
# -- but only on an x86_64 host. Kotlin/Native does not support linux-aarch64 as
# a host at all, so on an ARM machine these are skipped with a warning and iOS
# goes unchecked until CI. `make doctor` says so, rather than leaving it to a
# line of build output nobody reads.
IOS_SOURCES := :core:compileIosMainKotlinMetadata :ui:compileIosMainKotlinMetadata

test: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan
	$(DOCKER_RUN) $(TEST_IMAGE) $(GRADLE_CMD) --no-daemon $(or $(ARGS),:core:jvmTest $(IOS_SOURCES))

# Starts a real backend, runs the suite against it, and tears it down whatever
# happens. See scripts/conformance.sh for why the image is pinned by digest.
conformance: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan
	TEST_IMAGE=$(TEST_IMAGE) RUN_AS=$(RUN_AS) scripts/conformance.sh

# The one thing that actually runs the Android code rather than compiling it.
# The emulator wants the host's KVM.
#
# It also wants a system image the toolchain image does not carry, and
# `sdkmanager` installs it under ANDROID_HOME -- inside a container started with
# `--rm`, so without this mount it is fetched again on every single run. It is
# mounted here rather than in DOCKER_RUN because `make test` runs in a plain JDK
# image with no /opt/android-sdk to mount it over.
#
# Expect about 8 GB under .cache/ after the first run. `make clean` takes it
# with the rest.
device-test: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan $(SDK_CACHE)/system-images
	$(DOCKER_RUN) --device /dev/kvm \
		-v "$(SDK_CACHE)/system-images":/opt/android-sdk/system-images \
		$(IMAGE) $(GRADLE_CMD) --no-daemon :core:emulatorAndroidDeviceTest

shell: | $(CACHE_DIR)/gradle $(CACHE_DIR)/konan
	$(DOCKER_RUN) -it $(IMAGE) bash

clean:
	rm -rf $(CACHE_DIR) build */build
