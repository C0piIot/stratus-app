# stratus-app

The Stratus app: one Kotlin Multiplatform codebase for iOS and Android, whose
first job is backing up the camera roll.

Stratus is a self-hosted personal cloud that speaks protocols your existing apps
already understand, rather than shipping its own API. This app is held to the
same rule: it talks **standard WebDAV**, so it works against any WebDAV server —
Stratus, Nextcloud, a box running rclone serve — and it never calls anything
private to Stratus.

> **Early.** You can sign in to more than one server, switch between them, walk
> their files, choose which folders feed each one and watch what the backup is
> doing. On Android it runs in the background and reaches every server you turn
> on; on iOS the photo library is not wired up at all, so there is nothing to
> send yet. The sections below describe what it is
> meant to be; this warning shrinks as that stops being aspirational.

## Why it exists

Automatic camera-roll backup is the thinnest part of Stratus, and on Android it
is already solved by somebody else: FolderSync points the camera folder at a
WebDAV target and does the job. The real gap is iOS, where there is nothing free
worth recommending — and iOS is also where the operating system fights hardest,
which is most of the work here.

## How it uploads

Plain WebDAV `PUT` has no way to resume. A four-gigabyte video on a flaky mobile
connection restarts from zero, forever, and no amount of retry logic fixes it.
So the app negotiates rather than assuming:

| Server | Transport | What you get |
|---|---|---|
| Any WebDAV server | `PUT` | photos and small videos, restart on failure |
| One that advertises [tus](https://tus.io) | `POST` + `PATCH` at an offset | large videos survive a dropped connection |

Baseline first, upgrade when offered: one `OPTIONS` at the start of each backup
pass decides which of the two runs, and a server that answers it with anything
else gets `PUT`. That keeps "works with any WebDAV server" true without giving up
resumable uploads where the server can do them.

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
make conformance # the same client against a real stratus-backend
```

`make test` is the loop you live in. It runs `core`'s JVM tests in a plain JDK
image with no Android SDK, which means no emulation on an ARM machine: about 25
seconds against five minutes through the full toolchain. Reach for
`make gradle` when you need an actual Android artifact.

Three modules: `core` holds the protocol layer and its tests, `ui` holds the
Compose Multiplatform interface and produces the framework Xcode will embed, and
`androidApp` is the Android application. The shared tests run on `core`'s JVM
target, which ships nowhere and exists only so they are fast.

Two caveats `make doctor` will tell you about, and both are about the
architecture rather than about anything you can install.

The **Android SDK is published for x86_64 only**, so on an ARM machine the
toolchain image runs under emulation and needs binfmt registered once. And
**Kotlin/Native does not support `linux-aarch64` as a host at all**, so there the
iOS source sets are skipped rather than compiled and nothing checks them until
CI. On an x86_64 Linux machine both stop being true, and `make test` covers the
iOS sources too. Only *linking* an Apple binary needs macOS, which is why CI has
a job on a macOS runner.

If your machine is ARM, `.devcontainer/` describes a GitHub Codespace that is
not: opening one gives an x86_64 box with Docker, where both caveats disappear
and `make doctor` says so on first login.

## Sharing

Hold a row in the browser and the app makes a link anybody can open without an
account -- a file, or a folder and what is under it -- for a day, a week, a
month, or until you change your password.

Nothing is asked of the server to make one: Stratus signs share links with a key
derived from the password, so an app that holds the password can sign its own.
Other WebDAV servers do not understand them, and the app finds that out by
offering the link to the server before offering it to you -- so sharing and
casting disappear rather than producing a URL nobody can open.
Nothing is written down either, which is what the choice of lifetime is for.
There is no list of what you have shared, no withdrawing one link on its own,
and renaming a shared file breaks its link.

## Casting

Hold a photograph or a video and send it to a Chromecast. It uses Google's
default receiver, so there is nothing to register and nothing to pay: what the
television fetches is a signed link that needs no account.

A photograph is sent as the server's rendering of it, which is how a HEIC from
an iPhone appears on a screen that cannot read one. Video is sent as it is, so
H.264 plays and HEVC needs a 4K Chromecast. And a server whose certificate you
accepted on the phone is one the television cannot check, so the app says so
before trying.

The Cast SDK is part of Google Play Services. On a phone without them the app is
the same app, without the cast button.

## Getting it onto an Android phone

One address, which never changes and needs no account:

**https://github.com/C0piIot/stratus-app/releases/download/latest/stratus.apk**

It is the current state of `main`, rebuilt and replaced on every push. Open it on
the phone and install, or `adb install stratus.apk`.

Every CI run, on a branch or not, also attaches the APK to itself under
[Actions](https://github.com/C0piIot/stratus-app/actions) — useful for trying a
change before it lands, though GitHub serves artifacts as a zip and only to
somebody signed in.

The phone will ask whether you trust an app from outside the store. It will not
ask you to uninstall the last one: every build from this repository is signed
with the same key, kept as a repository secret, so updates install over each
other and keep your session and your cache. A build made anywhere else -- a
fork, or your own laptop -- is signed with a throwaway debug key instead and
will not install over ours.

That key is for sideloading and nothing else. Stable releases, if there are
ever any, get a key of their own and a Play listing is not part of the plan.

## What cannot be promised on Android

Background work that is not in the foreground is killed by the battery managers
some manufacturers ship, however correct it is. Xiaomi, Huawei, Oppo and Samsung
each do it differently, none of them ask, and no amount of care in the app
prevents it — a transfer runs as a foreground service with a notification, which
is the strongest thing an app is allowed to do, and on those phones it can still
be stopped.

Where that happens the honest answer is to say so rather than to look as though
the backup is running, and the app being the only thing in a position to notice
is why that matters. If a backup stops overnight on one of those phones, the
setting to look for is the one their launcher calls autostart or battery
optimisation.

## Licence

MIT. See [LICENSE](LICENSE).

## Related

- Workspace and shared principles: https://github.com/C0piIot/stratus
- Server: https://github.com/C0piIot/stratus-backend
