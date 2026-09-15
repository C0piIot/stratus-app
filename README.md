# stratus-app

The Stratus app: one Kotlin Multiplatform codebase for iOS and Android, whose
first job is backing up the camera roll.

Stratus is a self-hosted personal cloud that speaks protocols your existing apps
already understand, rather than shipping its own API. This app is held to the
same rule: it talks **standard WebDAV**, so it works against any WebDAV server —
Stratus, Nextcloud, a box running rclone serve — and it never calls anything
private to Stratus.

> **Early.** The project builds and its tests run, but the app does nothing yet:
> Android launches to an empty screen, and there is no iOS project at all. Nothing
> is backed up and nothing is browsed. The sections below describe what it is
> meant to be; this warning shrinks as that stops being aspirational.

## Why it exists

Automatic camera-roll backup is the thinnest part of Stratus, and on Android it
is already solved by somebody else: FolderSync points the camera folder at a
WebDAV target and does the job. The real gap is iOS, where there is nothing free
worth recommending — and iOS is also where the operating system fights hardest,
which is most of the work here.

## How it will upload

Plain WebDAV `PUT` has no way to resume. A four-gigabyte video on a flaky mobile
connection restarts from zero, forever, and no amount of retry logic fixes it.
So the app negotiates rather than assuming:

| Server | Transport | What you get |
|---|---|---|
| Any WebDAV server | `PUT` | photos and small videos, restart on failure |
| One that advertises [tus](https://tus.io) | `POST` + `PATCH` at an offset | large videos survive a dropped connection |

Baseline first, upgrade when offered. That keeps "works with any WebDAV server"
true without giving up resumable uploads where the server can do them.

## Platform reality

Worth knowing before judging the app for it:

- **iOS backup is opportunistic, never immediate.** iOS does not allow a
  long-running background upload loop. Transfers are handed to the system, which
  paces them — in practice while charging, on wifi, often overnight. This is the
  platform, not the app.
- **Android needs to survive the manufacturer.** Scheduled work plus a
  foreground service is the reliable shape; battery managers on some OEMs will
  still interfere.
- **Originals are uploaded as they are**, including HEIC and Live Photos. A
  backup that transcodes is not a backup.

## Building

No JDK, Gradle or Android SDK on your machine — only Docker.

```sh
make doctor     # can this machine run it?
make toolchain  # build the toolchain image
make gradle ARGS=build
make test       # shared tests, native and fast
```

`make test` is the loop you live in. It runs `core`'s JVM tests in a plain JDK
image with no Android SDK, which means no emulation on an ARM machine: about 25
seconds against five minutes through the full toolchain. Reach for
`make gradle` when you need an actual Android artifact.

Three modules: `core` holds the protocol layer and its tests, `ui` holds the
Compose Multiplatform interface and produces the framework Xcode will embed, and
`androidApp` is the Android application. The shared tests run on `core`'s JVM
target, which ships nowhere and exists only so they are fast.

Two caveats `make doctor` will tell you about. The Android SDK is published for
x86_64 only, so on an ARM machine the toolchain image runs under emulation and
needs binfmt registered once. And iOS cannot be built in a container by anyone:
Kotlin/Native needs Xcode, which means a Mac or a macOS CI runner.

## Licence

MIT. See [LICENSE](LICENSE).

## Related

- Workspace and shared principles: https://github.com/C0piIot/stratus
- Server: https://github.com/C0piIot/stratus-backend
