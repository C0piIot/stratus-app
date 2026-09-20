# Stratus app

One Kotlin Multiplatform codebase for iOS and Android. Its first and primary job
is backing up the camera roll to a WebDAV server.

The principles this obeys, the protocol surface it talks to and the working
agreements it is held to are one level up, in the workspace repo's `CLAUDE.md`.
Claude Code reads both. What is here is the app's own half, in the repo whose
code it describes.

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

**A listing asks for the five properties it reads and no others.** This was
`allprop` on the reasoning that a round trip costs the same either way, which is
true of the round trip and false of the server: answering `supportedlock` and
`creationdate` for ten thousand entries is work done for a client that throws
all of it away. Measured against stratus-backend, ten thousand files in one
folder: 6.70 MB and 1.43 s for `allprop`, 4.11 MB and 0.18 s for the five. The
list lives next to the parser that reads them and the request is built from it,
so the two cannot drift.

A path is therefore a key, and a key has to have one form. **The trailing slash
on a collection's href is normalised away where the multistatus is parsed**, and
that is the only place it can be done once: most servers end a collection's href
with a slash -- Apache, sabre/dav, Nextcloud, RFC 4918's own examples -- ours did
not until it changed libraries, and the cache, the self entry a `Depth: 1`
listing has to drop, and a signed link would each have decided it separately.

That gives two requirements on how files are named and checked:

- **A deterministic remote path**, so that "is this already uploaded?" is a
  question the server can answer about a path rather than one that needs local
  memory.

  This was written as "derived from capture time and a stable identifier", and
  the trouble is that **neither platform has one**: MediaStore ids change on a
  rescan and `PHAsset` identifiers do not survive restoring onto a new phone --
  which is precisely the moment the path must not move. So identity comes from
  the photograph rather than from the device: **capture time, original filename
  and byte count**, all three intrinsic and all three unchanged by a restore.
  `localId` exists only to ask the platform for the bytes again.

  The name that falls out is `2026-09-17_143022_IMG_0001.fe038252.heic` under
  `/<root>/2026/09/`, the eight characters being a digest of those three fields.
  Two photographs land on one path only when their second, their name and their
  size all match -- at which point they are almost certainly the same picture
  imported twice, and storing it once is the right answer rather than a collision
  to design around. `RemoteLayoutTest` pins that exact string on purpose:
  changing how a path is derived orphans every library already uploaded, and it
  should cost a failing test and a deliberate answer rather than a tidy-up.

  The time is the **wall clock the device reports, never converted to UTC**.
  Converting moves an evening photograph into the previous day's folder for
  anybody east of Greenwich, and makes the answer depend on where the phone was
  when it was asked.
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
they are typing what their browser shows them. Credentials are proved with a real
request before the screen is dismissed, so that a typo fails at the keyboard
rather than silently four hours later when the first upload runs.

**There is no standard way to discover a files collection, and this was measured
rather than assumed.** `current-user-principal` (RFC 5397) is the answer for
CalDAV and CardDAV because those define a home-set property pointing at the
collection; WebDAV files define nothing of the kind. Against Nextcloud 35:
`/.well-known/caldav` does redirect to the DAV root, and the principal does come
back from there -- and then it stops, because the container the files live in
answers `405` to a `PROPFIND`, so it cannot even be walked into. The only way
across that last step is to know that the path is `files/<principal id>/`, which
is product knowledge in a client that is supposed to work against any server.

So a typed path stays the answer for servers that are not ours, and the effort
goes into failing in a way that says so: the sign-in failure carries what each
path answered, because nothing there (404 everywhere) and something there that
is not WebDAV are different sentences, and a folder that answers `405` is
explained as a folder the server will not list rather than as an error. This is
stratus-app#32, closed with the measurements on it.

**Plain `http` is consented to, not blocked and not silent.** It is the normal
case for a self-hosted server on somebody's own network, so refusing it would
make the app useless where it is most used -- but a password in clear text is
worth one interaction. With no scheme typed, https is tried first and the offer
of http comes only after it fails; a typed `http://` is warned about too. The
answer is **remembered per host**, because the risk belongs to the host rather
than to this particular sign-in, and a warning shown every time is a warning
nobody reads.

**A certificate nothing vouches for is a question, and the order of operations is
the security control.** Self-hosted servers very often present one, so refusing
outright would be refusing the normal case; accepting quietly would be worse than
plain http, because it looks encrypted. So: the system's validation runs exactly
as it always did, and only a certificate it *rejected* is compared against a
fingerprint somebody has vouched for. There is no mode in which verification is
off -- a fingerprint captured from a handshake made without checking would mean
the vulnerability has already shipped and the dialog is all that stands between
it and a user. Three rules follow, each with a test:

- **Pin per `host:port`, and only what failed validation.** Recording a
  certificate that validated would break sign-in every sixty days, when Let's
  Encrypt renews it, for everybody with a real one.
- **A refused certificate never falls back to http.** "I do not trust this" must
  not become "then send it in clear", and that is one line of control flow.
- **The hostname is checked the same way**, because a home-lab certificate is
  usually self-signed *and* wrong-named, and a name mismatch reports through the
  verifier rather than the trust manager -- without handling it there, the
  commonest case of all arrives with no certificate to show anybody.

The Android trust manager is plain JDK code, so it lives in a `jvmCommon` source
set and the fast loop proves it against a committed self-signed certificate --
no private key in the repository and no TLS server in the test, because what is
under test is the decision rather than the handshake. iOS is stratus-app#58 and
until then behaves as it always has.

Two properties hold around that, and both have tests. Nothing carrying a password
goes out over http before consent -- the probe that precedes it is anonymous, so
nobody is asked to accept a risk for a host that turns out not to exist. And an
anonymous `207` is never success: a server allowing anonymous reads would
otherwise dismiss the screen having proved nothing about the password.

**The browser is WebDAV verbs, and inherits their limits.** Browse is `PROPFIND`,
download is `GET`, rename is `MOVE`, delete is `DELETE` -- all standard, all
working against any server, no exception needed to the rule above. But the limits
come along, and one of them is worth keeping even after it stopped applying here:
**a server that refuses to rename a folder with anything in it must be explained
in those words**, because a generic failure reads as a broken app rather than a
server that cannot do it. Stratus refused exactly that until it learned to
rewrite every path beneath a directory; plenty of WebDAV servers still refuse, so
the message stays and the conformance suite now pins the rename working. Deleting a full
folder does work, and there is no trash anywhere in Stratus, so deletion asks
first -- the same bargain the web UI already makes.

**A link somebody without an account can open, minted here rather than asked
for.** The server signs share links with a key derived from the password
(stratus-backend#169), and this app holds that password -- so it can produce one
itself, and no endpoint had to be invented for it. That is the difference
between adding sharing and breaking the no-private-API rule, and it is worth
noticing that the rule was what made the good design findable.

The link is the **WebDAV** address with a signature on it, not the web UI's
`/files/`, though the server takes the token at both (stratus-backend#180). The
app already holds this URL, so nothing has to be derived from it -- and a mount
is not the surface that changes shape, while the web UI has a PWA and a calendar
filed against it. The one exception is a thumbnail, which has nowhere else to
live because WebDAV has no such thing.

**A signed link is Stratus's, and this app is meant to work against any WebDAV
server**, so whether one means anything is a question rather than an assumption.
It is asked with the link somebody is actually sending, at the moment they send
it, and the answer is remembered: a speculative check at startup would have to
sign something to ask about, and the only path always available to sign is the
root -- putting an all-access signature on the wire to answer a question nobody
had yet. The request carries no credentials, deliberately, because with them it
would succeed everywhere and prove nothing.

The cost is that two codebases in two languages have to agree on a token byte
for byte with no shared artefact between them, which no unit test here can
promise. So the format is pinned twice: against a golden value computed with an
independent implementation, which fails in seconds, and against a real server in
the conformance suite, which is what actually settles it. What is signed is the
raw path; what travels is the encoded one, and a name with a space, an accent
and an ampersand is in the conformance test for that reason.

The screen says what cannot be taken back, because none of it can be taken back
one link at a time: there is no list of what has been shared, no withdrawing a
single link -- changing the password withdraws every link and signs every
browser out -- and renaming a shared file breaks its link, since a signature
names a path and not a file.

**Casting is a signed link and Google's own receiver, which is why it costs
nothing.** A Chromecast fetches the media itself and cannot send credentials, so
the choice used to be between a receiver of our own -- a five-dollar
registration, a Google account, an HTTPS page to host -- and a proxy running on
the phone for the length of a film. A link that needs no credentials removes
both: the default receiver loads an ordinary URL.

What is sent is decided in `commonMain` and is the interesting half. **A
photograph goes as the server's JPEG, not as itself**, because no Chromecast
reads HEIC and HEIC is what an iPhone records and what this app uploads
untouched; `/thumb/` already renders one, and the conformance suite proves a
HEIC comes back as a JPEG to a request with no account behind it. Video goes as
it is and an older Chromecast will not play HEVC -- refusing it here would also
refuse everything that does work, so the television reports its own failure.

**The one failure worth predicting is the certificate.** A server trusted only
because somebody pinned it on this phone is one a television has nobody to ask
about: it fetches nothing and says nothing, which reads as the app being broken.
So the app warns first -- and warns rather than refuses, because a pin is
consulted only when system validation fails, so a server given a real
certificate since would be blocked for nothing.

The Cast SDK lives inside Google Play Services, so a phone without them gets the
whole app minus the button: one availability check in the entry point, behind
the `Caster` interface, and the emulator CI already runs -- an `aosp-atd` image
with no Play Services -- is that phone on every run.

**Open and download are not the same button.** Open hands the file to whatever
the system uses to view it; download keeps a copy. On Android that copy has an
obvious home, on iOS it means the share sheet or the Files app, because there is
no general filesystem to put it in.

**"Which folders" does not survive the crossing to iOS.** Android has folders and
`DCIM` is one of them. iOS has a photo library with albums, no directories, and
no notion of a path. The menu entry is the same on both and what it opens cannot
be, so the shared model has to be a list of **sources** the platform resolves,
not a list of paths.

**A fourth surface: backup status.** Nothing in the three above answers "is my
backup working?", and on iOS that question has no other answer -- the system
decides when uploads run, so nothing happening is the normal state and is
indistinguishable from broken. It shows what is pending, what failed and why,
when it last ran, and what it is waiting for: no wifi, not charging, permission
withdrawn.

It is **a strip at the top of the browser that opens a screen**, not a screen
alone. The question is asked far more often than it is investigated, and an
answer that costs a navigation to reach is one nobody checks until they already
distrust the app. The strip carries the one-line state; the screen behind it
carries the queue, the failures and the reasons. Its absence is the most common
complaint aimed at every self-hosted photo backup that already exists, and it is
the surface that decides whether this one gets trusted.

## How this is tested, and what that budget buys

CI runs on **free GitHub Actions standard runners and nothing else**. Larger
runners are charged for even on a public repository, so a test that needs one is
a test we do not have. That is a budget, and like any budget it decides the
design rather than merely constraining it.

What it buys, in descending order of how much of the app it covers:

- **JVM unit tests over shared code, against a mock HTTP engine.** No network, no
  emulator, no container, milliseconds per test. This is where the WebDAV verbs,
  the capability negotiation, the upload queue and the dedup rules get proved,
  and it is the direct payoff of the rule that every decision worth getting wrong
  lives in `commonMain`. If this layer is thin, the budget has been wasted.
- **A conformance suite against a real `stratus-backend` container**, started as
  a service on an ubuntu runner. Docker costs nothing there, and it is the only
  way to know the client works against the server rather than against our
  assumptions about it. The backend already asserts its own container from the
  outside; this is the same habit from the other end. Server limitations get
  pinned here too, as tests that assert the limitation and are meant to fail the
  day it lifts. Two have: a `Depth: 1` PROPFIND omitting the collection itself,
  and a non-empty folder rename being refused. Both pins became guarantees, which
  is the argument for writing them rather than leaving a limitation undescribed.
- **Android instrumented tests on an emulator**, on the ubuntu runner, only where
  the platform API is the thing under test: media enumeration, permissions, the
  foreground service surviving what Android does to it.
- **iOS: compile the framework and run the shared tests on a simulator**, on the
  macOS runner. Everything in `commonTest` runs there as well as on the JVM, for
  nothing, which is worth remembering when deciding where a piece of logic lives.

  What that does **not** reach is the Keychain. A Kotlin/Native test binary has no
  app bundle and no entitlements, so `SecItemAdd` answers `errSecNotAvailable`
  (-25291) and every keychain test fails for a reason that has nothing to do with
  the code. Running them would need a host application the test runner does not
  provide. So `KeychainSecureStore` is unverified until the app meets a real
  device, which is the reason it is kept to the smallest possible piece of
  platform code behind an interface everything else is faked through.

What it does not buy, and no amount of cleverness will: the behaviour of a
background `URLSession` on a real device over days. That is unverifiable in CI at
any price, which is the reason the native layer is a dumb executor. Everything it
decides is decided somewhere a test can reach.

## Toolchain: Docker, and where that stops working

**No JDK, no Gradle and no Android SDK on the host.** Every toolchain command
runs in a container through the `Makefile`, so `docker` is the only hard
prerequisite -- the same bargain the backend makes for Go, for the same reason:
a contributor should not have to install a version of anything to be useful, and
a version installed on one machine and not another is a class of bug nobody
should be debugging.

**Docker is the interface, not the requirement.** Podman answers the same API,
so a `docker` CLI pointed at its socket runs every target here, and the README
says what else to set. It costs exactly one concession in the `Dockerfile`: APT
fetches packages as the `_apt` user, and a rootless namespace one uid wide has
no uid for it to drop to, so the download sandbox is turned off there. That is
inert on a host whose namespace is wide enough, which is the argument for
putting it in the image rather than in a setting somebody has to discover.

Two walls to know about before assuming this covers everything.

**Android has no ARM Linux toolchain.** Google publishes no Android SDK for
`linux/aarch64`: every aarch64 archive in their `repository2-3.xml` is macOS,
and their Maven serves aapt2 with `linux`, `osx` and `windows` classifiers and
nothing for arm64. The toolchain image is therefore pinned to `linux/amd64`, and
on an ARM host it runs emulated -- which needs binfmt registered first, or the
container dies with an exec format error that explains nothing. `make doctor`
checks for exactly that and prints the one command that fixes it.

None of that touches the loop you actually work in. `make test` runs the shared
JVM tests in a plain JDK image with **no Android SDK**, because nothing in them
ever executes an Android binary and AGP will configure the module without one so
long as no Android task runs. No SDK means no x86_64 dependency, which means no
emulation: about 25 seconds on the ARM box against five minutes through the full
toolchain. Only producing an actual Android artifact needs the emulated image,
and that is rare compared to running tests.

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

**The composition root is where that rule leaks, so watch it.** `AppContainer`
opens a database, builds HTTP clients and reads the keychain, which means no test
can construct one -- so anything that drifts in there has left the tested part of
the app without anybody deciding that it should. It happened twice: the choice
between tus and `PUT` lived in the container, and the fan-out over instances --
which of them get a pass, and whether to ask the system to come back -- lived in
`BackupWorker`, where iOS would have had to reimplement it. Both are in `Backup`
now, behind a `Connections` seam whose only job is to be the part that needs an
engine and a keychain. The container is wiring, the platform holds a
notification and a return value, and everything in between is in `commonTest`.

There is one qualification to all of that, with a sting in it: **the iOS source
sets compile on Linux -- but only on x86_64.** Kotlin/Native does not support
`linux-aarch64` as a host at all, so on the ARM development box every Apple
compilation is skipped with a warning and `make test` checks nothing about iOS.
On an x86_64 machine the same command catches a missing symbol or a wrong
cinterop signature in seconds; here it does not, and the first thing that knows
is CI.

This was learned the hard way, by reading a `BUILD SUCCESSFUL` from a task that
had been skipped and believing it. `make doctor` now says which side of that line
the machine is on, because a warning in the middle of Gradle output is not a
thing anybody reads. It is also the second time the ARM host has cost something
concrete -- the Android SDK was the first -- which is worth weighing the next
time moving the work to an x86_64 machine comes up.

What follows from the second wall, for whoever writes the Gradle build: declaring
the Apple targets is fine on Linux, and only *linking* them fails. Keep it that
way. `configure` must succeed everywhere so that shared code, JVM tests and
linting work on any machine, and let the iOS link step be the only thing that
demands a Mac.

## More than one Stratus

The app holds **several instances** and somebody switches between them, and the
backup reaches every instance they mark rather than only the one they are looking
at -- a camera roll that ends up on the NAS at home *and* on the VPS, which is
the only redundancy a self-hosted backup can offer.

Two consequences that decide code rather than merely describing it.

**An instance is identified by a generated opaque id, never by its address.**
Somebody who moves their server to a new domain still has the same instance and
its backup history has to follow them there. Identity by URL is free today and a
migration with real photographs behind it later.

**The unit of backup work is a (photograph, instance) pair, not a photograph.**
One asset with two destinations is two pieces of work that succeed and fail
independently: an instance that is down must not hold up the other. The bytes are
read once per destination, because two servers cannot share one upload stream and
keeping a four-gigabyte video in a temporary file to avoid a second read from the
photo library is the worse trade on a phone.

Everything cached about the server is keyed by that id, and a rebuild stops at
its own rows. Plaintext consent is the deliberate exception: it is keyed by
**host**, because the risk belongs to the machine at the other end and two
instances on the same host share the answer with good reason.

## What the queue decides

The transport is the part that will be a background `URLSession`, running where
no test can watch it. So it decides nothing, and all of this lives in
`UploadQueue` with a fake transport around it:

- **Newest photograph first.** The picture somebody just took is the one they
  check for; a first backup that starts in 2014 looks broken for days.
- **One upload at a time.** On a phone, several at once drains the battery and
  saturates the uplink without finishing any sooner.
- **Waiting helps or it does not, and that is not a retry count.** Rejected
  credentials do not improve by being asked again, and asking again is another
  failed login on a server that counts them; a timeout does improve. A permanent
  failure stays in the list so somebody can be told and is never picked up again.
- **Resuming asks the server, never the local offset alone.** Stratus answers a
  tus `PATCH` at the wrong offset with a 409 precisely because a client can
  believe it is somewhere the server does not agree with.
- **The queue is in the database, not in memory.** On iOS the system kills the
  app between transfers and relaunches it to report the result, so a queue that
  only exists while the app runs is a queue that does not exist.
- **A second look at the camera roll must not restart a large video**, which is
  why enqueuing ignores what is already queued rather than replacing it.

The transport interface is shaped around "ask where you got to and continue",
which `PUT` cannot do and tus can. It was written that way while `PUT` was the
only implementation, and tus arrived as a second `Transport` plus a negotiation
-- no change to the queue, which is what that shape was for.

**Negotiation is per pass, not per install.** `OPTIONS` on the origin's `/tus/`
once per backup run, and `Tus-Resumable` in the reply is the whole test; anything
else means `PUT`. Remembering the answer would mean a server that gained or lost
tus keeps being addressed the old way until something clears a cache, and one
request per pass is not worth that.

## Where credentials live

**Credentials live in the Keychain on iOS and under an Android Keystore key on
Android**, behind a `SecureStore` interface narrow enough to fake -- "put this
string somewhere safe and give it back". One record per instance over that same
interface, which is why holding several cost no platform code at all: the two
halves that CI cannot test did not have to change. Two choices inside that are this app's
purpose talking rather than defaults: the Keychain item is
`kSecAttrAccessibleAfterFirstUnlock` and the Keystore key requires no user
authentication, because uploads run while the phone is locked on a charger
overnight and the stricter settings would make the credentials unreadable exactly
then -- a backup that silently stops at night and gives no hint why.

Worth being exact about the Android half, since the shape invites a wrong
assumption: the Keystore holds *keys*, not arbitrary secrets. There is no
equivalent of a Keychain generic-password item, so every implementation of this
-- `androidx.security:security-crypto` included -- is a Keystore key wrapping a
blob in an ordinary file. "Never in ordinary preferences" is satisfied in
substance, since the file holds ciphertext undecryptable without a key the app
cannot export, but not in letter.

## Still open

Decided later, deliberately not guessed at here: whether Nextcloud's chunked
upload convention is worth supporting alongside tus for servers that have it.

The UI toolkit is **Compose Multiplatform**, and the reason is the missing Mac
rather than any judgement about SwiftUI. Without one you cannot build or preview
a SwiftUI screen at all, so every visual change would be a CI round trip and a
TestFlight upload; with Compose the interface is developed against Android at
full speed and the same code renders on iOS. The price is that iOS will not feel
entirely native -- scrolling, text fields, system pickers -- and that price is
worth paying only because of the constraint, so if a Mac ever appears this is
worth revisiting.
