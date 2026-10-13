#!/usr/bin/env sh
# Which build this is, for the row at the bottom of Settings (stratus-app#148).
#
# One place rather than two: the Makefile reads it for every container it
# starts, and CI's iOS job reads it because that one calls Gradle directly --
# a macOS runner has no toolchain container to put it in. Written twice, the
# two would drift and the symptom would be an iOS build that quietly says
# `dev` while the APK beside it names a commit.
#
# The date is the **commit's** and not today's: a rebuild of one commit has to
# produce the same artifact, and what somebody reads off that row is how old
# the code is. Outside a git tree both answer honestly rather than guessing.
set -eu

version() {
    git describe --tags --match "v*" --always --dirty 2>/dev/null || echo dev
}

date_of_commit() {
    TZ=UTC git show -s --date=format-local:%Y-%m-%dT%H:%M:%SZ --format=%cd 2>/dev/null || echo unknown
}

case "${1:-env}" in
    version) version ;;
    date)    date_of_commit ;;
    # Two lines of KEY=value, which is what $GITHUB_ENV takes.
    env)     printf 'STRATUS_VERSION=%s\nSTRATUS_BUILD_DATE=%s\n' "$(version)" "$(date_of_commit)" ;;
    *)       echo "usage: $0 [version|date|env]" >&2; exit 2 ;;
esac
