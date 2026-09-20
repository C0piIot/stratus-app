# syntax=docker/dockerfile:1

# The development toolchain. Nothing built from this image ships to a phone --
# it exists so that `docker` is the only thing a machine needs installed to work
# on this repo, the same bargain the backend's Makefile makes for Go.

ARG JDK_VERSION=21
ARG CMDLINE_TOOLS=16111833
ARG ANDROID_PLATFORM=36
# Not the newest build-tools, but the one AGP asks for: nothing pins
# `buildToolsVersion` in Gradle, so AGP picks its own default and downloads it if
# the image has anything else -- which is what a baked 36.1.0 was doing, adding
# 150 MB to the image that no build ever opened and a download to every run.
# A mismatch degrades rather than breaks, so it shows up as `Install Android SDK
# Build-Tools` in the log rather than as a failure. Re-check it when AGP moves.
ARG BUILD_TOOLS=36.0.0
ARG GRADLE_VERSION=9.7.1
ARG GRADLE_SHA256=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a

# The platform is pinned rather than inherited from the host, and it is not a
# preference. Google publishes no Android SDK for linux/aarch64: every aarch64
# archive in repository2-3.xml is macOS, and Google's Maven has aapt2 with
# `linux`, `osx` and `windows` classifiers and no arm64 among them. On an ARM
# host this image therefore runs emulated -- see `make doctor` -- and on an
# x86_64 host it is native. Inheriting the host platform would instead produce
# an image that builds and then fails at aapt2 with nothing explaining why.
FROM --platform=linux/amd64 eclipse-temurin:${JDK_VERSION}-jdk-noble

ARG CMDLINE_TOOLS
ARG ANDROID_PLATFORM
ARG BUILD_TOOLS
ARG GRADLE_VERSION
ARG GRADLE_SHA256

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools"

# APT fetches packages as the unprivileged `_apt` user rather than as root, and
# that is a uid the build has to actually contain. A rootless podman whose host
# has no subuid ranges gets a user namespace one uid wide, so there is no uid 42
# to drop to and apt does not degrade -- it dies:
#
#   E: setgroups 65534 failed - setgroups (1: Operation not permitted)
#   E: Method http has died unexpectedly!
#
# Turning the download sandbox off costs little here and nothing on a host where
# the namespace is wide enough: every other step of this build already runs as
# root, the image is a throwaway toolchain that ships to no phone, and what the
# sandbox defends against is a compromised mirror attacking apt's fetch methods.
# The alternative is writing subuid ranges into /etc, which is root on the host
# and outside what a Dockerfile can promise.
RUN echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/00no-sandbox

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip git \
    && rm -rf /var/lib/apt/lists/*

# What the emulator needs to start. It is a desktop application even headless,
# and a missing library here does not produce an error -- it produces
# "Unable to start Android emulator ... Error message from emulator process = []",
# which is an afternoon if nobody wrote this down.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libpulse0 libnss3 libnspr4 libasound2t64 \
        libx11-6 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 \
        libgl1 libglu1-mesa libdbus-1-3 libatk1.0-0t64 libxkbcommon0 \
    && rm -rf /var/lib/apt/lists/*

RUN set -eu; \
    curl -fsSL -o /tmp/tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip"; \
    mkdir -p "$ANDROID_HOME/cmdline-tools"; \
    unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools"; \
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"; \
    rm /tmp/tools.zip

# Licences are accepted at build time so that no interactive prompt can appear
# in the middle of somebody's first build.
# The emulator is here rather than in the Makefile's cache, and the reason is
# mechanical rather than a preference: sdkmanager installs a package by unpacking
# it beside the target and then *deleting and replacing* that directory, and a
# bind mount point cannot be deleted --
#
#   java.nio.file.FileSystemException: /opt/android-sdk/emulator: Device or
#   resource busy ... at FileOpUtils.moveOrCopyAndDelete
#
# The system image escapes this because its package lands in a subdirectory of
# the mount rather than on the mount itself, which is why that one is cached and
# this one is a layer. It costs about 800 MB in the image; the system image
# would have cost 8.2 GB, which is the whole argument for splitting them.
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
         "platform-tools" \
         "platforms;android-${ANDROID_PLATFORM}" \
         "build-tools;${BUILD_TOOLS}" \
         "emulator" \
    && chmod -R a+rwX "$ANDROID_HOME"

# This Gradle exists to bootstrap the wrapper and nothing else. The moment the
# project has a `gradlew`, that file is the only thing that decides which Gradle
# a build uses -- here, in CI and on a laptop alike -- and the Makefile prefers
# it. Leaving a second version in the image to be picked up silently is how the
# two drift.
RUN set -eu; \
    curl -fsSL -o /tmp/gradle.zip \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"; \
    echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c -; \
    unzip -q /tmp/gradle.zip -d /opt; \
    ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle; \
    rm /tmp/gradle.zip

# Gradle runs as the invoking user, whose uid does not exist in this image and
# therefore has no home directory. Everything that would land in one is
# redirected by the Makefile; this only keeps the SDK writable for the packages
# a future build may add to it.
WORKDIR /src
