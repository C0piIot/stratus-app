# syntax=docker/dockerfile:1

# The development toolchain. Nothing built from this image ships to a phone --
# it exists so that `docker` is the only thing a machine needs installed to work
# on this repo, the same bargain the backend's Makefile makes for Go.

ARG JDK_VERSION=21
ARG CMDLINE_TOOLS=16111833
ARG ANDROID_PLATFORM=36
ARG BUILD_TOOLS=36.1.0
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

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip git \
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
RUN yes | sdkmanager --no-metrics --licenses > /dev/null \
    && sdkmanager --no-metrics --install \
         "platform-tools" \
         "platforms;android-${ANDROID_PLATFORM}" \
         "build-tools;${BUILD_TOOLS}" \
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
