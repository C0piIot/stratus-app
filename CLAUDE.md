# Stratus app

One Kotlin Multiplatform codebase for iOS and Android. Its first and primary job
is backing up the camera roll to a WebDAV server.

The principles this obeys, the protocol surface it talks to and the working
agreements it is held to are one level up, in the workspace repo's `CLAUDE.md`.
Claude Code reads both. What is here is the app's own half, in the repo whose
code it describes.

**Nothing is written yet.** The Gradle project is not scaffolded. What follows is
the set of decisions already made, so that whoever writes the first commit does
not have to relitigate them — not a description of code that exists.

## Non-negotiable, and the reasons

1. **No private Stratus API, ever.** The app speaks standard WebDAV and nothing
   else that Stratus alone understands. This is the server's second principle
   applied from the other side: if a feature looks like it needs a new endpoint
   on the server, that is a conversation, not a commit. The discipline is what
   keeps the app honest, and the test of it is that it works against Nextcloud or
   `rclone serve webdav` with nothing special configured.
2. **Capability negotiation, not a version check.** On connecting, the app finds
   out what the server can do and picks the best transport it offers. A plain
   WebDAV server gets `PUT`; a server advertising tus gets resumable uploads.
   Degrading has to be graceful and silent — a worse server means slower
   recovery from a dropped connection, never a broken app.
3. **Originals, never transcoded.** HEIC stays HEIC, HEVC stays HEVC. A backup
   that re-encodes is not a backup. The consequence is that the server may hold
   files it cannot make a thumbnail of, which is the server's problem to solve
   and not a reason to degrade the archive.
4. **Minimal dependencies**, in the same spirit as the server. Every library
   added to a mobile app is a permission, a size increase and a review risk.

## The architecture decision that shapes everything

The control plane is shared Kotlin. The bytes are not.

An HTTP client like Ktor is the right tool for the small requests — probing what
the server supports, `PROPFIND` to see what is already there, creating a tus
upload and asking it for its current offset. Those are ordinary
request-and-response calls and they belong in `commonMain`.

The transfer itself cannot work that way on iOS. A long upload has to survive the
app being suspended and killed, which means handing it to the system rather than
awaiting it in a coroutine: iOS does this with a background `URLSession`, where
the system performs the transfer out of process and relaunches the app to report
the result. That flow is fundamentally not a function that returns a response, so
no general-purpose HTTP client can express it. Android has its own shape for the
same problem — scheduled work plus a foreground service.

So the byte transport is `expect`/`actual`, native on both sides, and only the
protocol logic above it is shared.

**This is also the reason tus fits and other resumable schemes would not.** A
background upload task on iOS sends a *file*, not a stream, and each tus `PATCH`
is exactly that: one self-contained request carrying one chunk from disk, whose
success or failure the system can report later. A protocol that needed a live
connection held open across chunks would be unimplementable on iOS, however
elegant it looked on paper.

## State, and what is the record

**The server is the record of what has been backed up.** The local database is a
cache of that, and it must be rebuildable by walking the server — because a
reinstall, a restore to a new phone or a cleared app storage will all destroy it,
and none of those should cause the whole camera roll to upload again.

That gives two requirements on how files are named and checked:

- **A deterministic remote path**, derived from the asset's capture time and a
  stable identifier, so that "is this already uploaded?" is a question the server
  can answer about a path rather than one that needs local memory. Beware the
  obvious trap: filenames like `IMG_0001.JPG` collide across devices and across
  years.
- **Integrity from the ETag**, not from the file size. Stratus's ETag is a
  SHA-256 of the bytes it actually stored, which is a real verification and
  better than most servers give — but the app must treat a weak or absent ETag as
  "cannot verify" and fall back to existence, since it has to work against
  servers that offer nothing better.

## Platform notes that will otherwise be rediscovered painfully

- **iOS background work is opportunistic and unschedulable.** The system decides
  when, and in practice that means charging and on wifi. Do not build a UI that
  promises an upload will happen now, and do not treat a slow first backup as a
  bug to be engineered away. It is the platform.
- **Live Photos are two resources**, a still and a movie, and they are only a
  Live Photo together. Upload them as a pair or not at all; half of one is worse
  than neither.
- **Android OEM battery managers** will kill background work regardless of
  correctness. The mitigation is a foreground service during transfers and
  telling the user plainly when their manufacturer is the problem.
- **Photo library permissions are scrutinised by both stores.** Full-library read
  access is the whole point of the app and has to be justified as such; the
  user-mediated photo pickers both platforms push instead cannot express
  "everything, continuously".

## The interface

Three surfaces, and no more than three:

1. **Sign in**, which also takes the server URL. There is no Stratus to default
   to; every install is somebody's own.
2. **The file browser**, which is the main screen: walk the tree, open or
   download a file, rename it, delete it.
3. **A menu**, holding sign out and the choice of which folders are watched for
   backup.

What that implies, in the order it will be discovered:

**The URL is a field users get wrong.** Take a base address and find the WebDAV
path from it rather than demanding somebody know that Stratus serves `/dav/` --
they are typing what their browser shows them. Plain `http` on a local network
is the normal case for a self-hosted server and must not be blocked or buzzed
at. Credentials are proved with a real request before the screen is dismissed, so
that a typo fails at the keyboard rather than silently four hours later when the
first upload runs, and they are kept in the Keychain or in Keystore-backed
storage, never in ordinary preferences.

**The browser is WebDAV verbs, and inherits their limits.** Browse is `PROPFIND`,
download is `GET`, rename is `MOVE`, delete is `DELETE` -- all standard, all
working against any server, no exception needed to the rule above. But the limits
come along: `internal/files` in the backend says plainly that Move "renames a
file or an empty directory", so **renaming a folder with anything in it fails**,
and it will keep failing until moving a directory stops meaning rewriting every
path beneath it. The app should say that, in those words. A generic failure here
reads as a broken app rather than a server that cannot do it yet. Deleting a full
folder does work, and there is no trash anywhere in Stratus, so deletion asks
first -- the same bargain the web UI already makes.

**Open and download are not the same button.** Open hands the file to whatever
the system uses to view it; download keeps a copy. On Android that copy has an
obvious home, on iOS it means the share sheet or the Files app, because there is
no general filesystem to put it in.

**"Which folders" does not survive the crossing to iOS.** Android has folders and
`DCIM` is one of them. iOS has a photo library with albums, no directories, and
no notion of a path. The menu entry is the same on both and what it opens cannot
be, so the shared model has to be a list of **sources** the platform resolves,
not a list of paths.

**The surface this list is missing is backup status**, and it is recommended
rather than specified -- the call is Edu's. Nothing in the three screens above
answers "is my backup working?", and on iOS that question has no other answer:
the system decides when uploads run, so nothing happening is the normal state and
is indistinguishable from broken. What it needs to show is what is pending, what
failed and why, when it last ran, and what it is waiting for -- no wifi, not
charging, permission withdrawn. This is the screen that decides whether the app
gets trusted, and its absence is the most common complaint aimed at every
self-hosted photo backup that already exists.

## Toolchain: Docker, and where that stops working

**No JDK, no Gradle and no Android SDK on the host.** Every toolchain command
runs in a container through the `Makefile`, so `docker` is the only hard
prerequisite -- the same bargain the backend makes for Go, for the same reason:
a contributor should not have to install a version of anything to be useful, and
a version installed on one machine and not another is a class of bug nobody
should be debugging.

Two walls to know about before assuming this covers everything.

**Android has no ARM Linux toolchain.** Google publishes no Android SDK for
`linux/aarch64`: every aarch64 archive in their `repository2-3.xml` is macOS,
and their Maven serves aapt2 with `linux`, `osx` and `windows` classifiers and
nothing for arm64. The toolchain image is therefore pinned to `linux/amd64`, and
on an ARM host it runs emulated -- which needs binfmt registered first, or the
container dies with an exec format error that explains nothing. `make doctor`
checks for exactly that and prints the one command that fixes it.

**iOS cannot be containerised at all.** Kotlin/Native needs the Xcode toolchain
to produce Apple binaries, and Xcode is macOS-only and not licensed to run
elsewhere. No amount of Docker changes this.

There is no Mac on this project and there is not going to be one, so **CI is the
only iOS build host**: GitHub Actions standard macOS runners, which are free on
a public repository and were confirmed by a real run to be Apple Silicon with a
current Xcode and iOS SDK. A borrowed phone over TestFlight is the only time the
app meets real hardware.

What that costs is not money but the loop. There is no run button: every iOS
change is a push, a CI round trip and a TestFlight upload before anybody sees
it, and the phone is somebody else's and only occasionally available. **Design
against that.** Anything that can live in `commonMain` and be proved by a JVM
test must live there, and the iOS-native layer has to be as thin as it can be
made.

Note the cruelty in that, because it decides how the upload code is shaped: the
native layer *is* the background `URLSession` transport, which is precisely the
part a simulator cannot tell you the truth about and which needs days on a real
device to trust. So the native side must be a dumb executor -- hand it a file, a
destination and a callback -- and every decision worth getting wrong, which
chunk comes next, what to retry, when to give up, when a file counts as done,
belongs in shared code with tests around it. The rule of thumb: if debugging it
would need a device, it should not be the thing on the device.

What follows from the second wall, for whoever writes the Gradle build: declaring
the Apple targets is fine on Linux, and only *compiling* them fails. Keep it that
way. `configure` must succeed everywhere so that shared code, JVM tests and
linting work on any machine, and let the iOS link step be the only thing that
demands a Mac.

## Still open

Decided later, deliberately not guessed at here: the UI toolkit and how much of
the interface is shared, whether the app browses and downloads or only uploads,
how credentials are stored on each platform, and whether Nextcloud's chunked
upload convention is worth supporting alongside tus for servers that have it.
