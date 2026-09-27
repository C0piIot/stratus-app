#!/usr/bin/env bash
#
# Uploads the debug symbols under a directory to the Sentry project crash
# reports go to, so an iOS stack arrives as Kotlin functions and lines rather
# than addresses.
#
#   SENTRY_AUTH_TOKEN=... SENTRY_DSN=... scripts/upload-symbols.sh <dir>
#
# The organisation and the project are read off the DSN as ids, which the API
# takes in place of slugs, so the one thing to configure beyond the DSN the app
# already has is the token.
set -euo pipefail

dir="${1:?usage: $0 <directory with dSYMs>}"
: "${SENTRY_AUTH_TOKEN:?the upload needs an auth token}"
: "${SENTRY_DSN:?the upload needs the DSN, to know which project}"

# https://<key>@o<org>.ingest.<region>.sentry.io/<project>
org="$(sed -nE 's#^https://[^@]+@o([0-9]+)\..*#\1#p' <<<"$SENTRY_DSN")"
project="${SENTRY_DSN##*/}"
if [ -z "$org" ] || ! [[ "$project" =~ ^[0-9]+$ ]]; then
  echo "not a sentry.io DSN, so the project cannot be read off it" >&2
  exit 1
fi

command -v sentry-cli >/dev/null || brew install getsentry/tools/sentry-cli

# --include-sources so a frame shows the line around it, which is most of what
# makes a report readable when nobody can attach a debugger to the phone.
sentry-cli debug-files upload --include-sources --org "$org" --project "$project" "$dir"
