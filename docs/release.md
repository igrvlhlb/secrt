# Releasing the Android app

secrt is meant for Google Play and F-Droid. They sign the app differently, so the two builds have
different signatures. A user who switches from one to the other has to uninstall and reinstall
(after backing up the diary).

| Channel | Who builds | Who signs what users install |
|---|---|---|
| Google Play | You: `bundleRelease`, signed with your **upload key** | Google, with secrt's **app signing key** (Play App Signing; Google generates it and keeps it) |
| F-Droid | F-Droid, from the source, unsigned | F-Droid, with its own key |

## The signing key

The upload key is yours, and only reaches Play. Give secrt its own key: either a new keystore, or
a new entry (alias) in a keystore you already have. One keystore file can hold the keys of several
apps, each under its own alias and password:

```
keytool -genkeypair -keystore /path/to/upload-keystore.jks -alias secrt \
  -keyalg RSA -keysize 4096 -validity 10000
```

Back the keystore up somewhere safe. If the upload key is lost or leaks, Play's support can reset
it; the app signing key stays with Google.

Debug builds are a separate app, `io.github.igrvlhlb.secrt.debug`, named "secrt-debug" on the
launcher, so they install next to a release build, each with its own data.

## Signing the build

The build reads four Gradle properties. Keep them outside the repository, in
`~/.gradle/gradle.properties`:

```
secrt.signing.storeFile=/path/to/upload-keystore.jks
secrt.signing.storePassword=…
secrt.signing.keyAlias=secrt
secrt.signing.keyPassword=…
```

On CI, pass them on the command line instead (`-Psecrt.signing.storeFile=…`), from the CI's secrets.

- With all four set, `./gradlew :app:android:bundleRelease` gives a signed
  `app/android/build/outputs/bundle/release/android-release.aab` for Play, and `assembleRelease`
  a signed `android-release.apk`.
- With none set, the release build is unsigned (`android-release-unsigned.apk`). That is what
  F-Droid builds.
- With only some of them set, the build stops and names the missing ones.

## Shrinking (R8)

The release build is shrunk and optimized by R8 (`isMinifyEnabled`, `isShrinkResources`). R8 only
keeps what it can see being used, so code reached by name at runtime needs keep rules. Most
libraries ship theirs (SQLCipher, kotlinx-serialization, coroutines, Ktor, CameraX); the ones that
don't are in `app/android/proguard-rules.pro`. Today that is JNA, through which the libsodium
bindings call the native library: shrink it and creating or unlocking a diary fails.

What R8 breaks only shows when the app runs, so try a release build before shipping one, and after
adding a library that uses reflection, JNI or JNA. To install it next to the real app without
touching its data, build it once with, in `buildTypes.release`, the debug key and another package
(don't commit these):

```
signingConfig = signingConfigs.getByName("debug")
applicationIdSuffix = ".r8test"
```

Then go through creating a diary, unlocking it (password and recovery key), writing, searching,
export and import, cloud backup and pairing. Google sign-in won't work under the other package
unless it has its own Android client. Crashes show in `adb logcat`; R8's
`app/android/build/outputs/mapping/release/mapping.txt` turns the renamed names in a stack trace
back into the real ones (`retrace`). Keep that file for every release you ship.

Raise `versionCode` (and `versionName`) in `app/android/build.gradle.kts` for every release:
Play and F-Droid refuse a version code they already have.

## Google Drive and the signing certificate

Google lets an app sign in for Drive only if its package name and the SHA-1 of the certificate it
was signed with match an Android OAuth client in secrt's Google Cloud project. Each certificate
and package needs its own Android client (Google Auth Platform → Clients → Create client →
Android, package `io.github.igrvlhlb.secrt`, or `io.github.igrvlhlb.secrt.debug` for debug builds):

| Certificate | Where to find its SHA-1 | Needed for |
|---|---|---|
| Debug key (this machine's `~/.android/debug.keystore`) | `keytool -list -v -keystore ~/.android/debug.keystore -storepass android` | `installDebug` (package `io.github.igrvlhlb.secrt.debug`) |
| Play's app signing key | Play Console → Test and release → App integrity → App signing | Installs from Play |
| Your upload key | `keytool -list -v -keystore /path/to/upload-keystore.jks -alias secrt` | Signed release builds you install yourself |

F-Droid builds don't use Google Play services (F-Droid takes no closed-source libraries), so they
need another way to sign in to Google; see the backlog.

## Privacy policy

Play and Google's OAuth consent screen link to https://igrvlhlb.github.io/secrt/privacy.html. It
lives on the `gh-pages` branch (only `privacy.html`), which GitHub Pages serves as is. To change
it, commit to that branch and push; Pages republishes it in about a minute. Update it whenever
secrt starts handling data differently (a new permission, service or kind of backup).

# Releasing the desktop app

```
./gradlew :app:desktop:packageReleaseDistributionForCurrentOS
```

builds the installer for the OS it runs on (`.dmg` on macOS, `.msi` on Windows, `.deb` on Linux)
into `app/desktop/build/compose/binaries/main-release/`. jpackage can't build for another OS, so
each installer is made on its own OS. The macOS app isn't signed or notarized yet (see the
backlog), so Gatekeeper blocks it until the user allows it in System Settings.

"Release" only adds a ProGuard step that drops unused classes before packaging: the macOS app
goes from about 200 MB to 145 MB. Nothing else differs from `packageDistributionForCurrentOS`
(no debug mode, same signing). Names aren't obfuscated, and ProGuard's optimizer is off
(`optimize.set(false)`): it rewrote Okio's methods into bytecode the JVM rejects
(`VerifyError: Bad return type`), and saved only about 3 MB.

## The runtime JDK

The app runs on, and bundles, Temurin 21 (`desktopRuntime` in `app/desktop/build.gradle.kts`);
Gradle downloads it when the machine has no copy. Stay on a JDK that ships `jmods/`: ProGuard reads
the JDK's own classes from there. From JDK 24 on, Temurin leaves them out (JEP 493), and ProGuard
then can't resolve even `java.lang.Object`, failing with hundreds of thousands of warnings. Moving
to a newer JDK means picking a vendor whose JDK still has `jmods/`, and adding to `modules(...)`
whatever the new JDK moved (on 21, TLS needs `jdk.crypto.ec`).

## Keep rules

`app/desktop/proguard-rules.pro` keeps what ProGuard can't see being used: code reached through
reflection, JNI, JNA or ServiceLoader (libsodium via JNA, the SQLite driver), and silences
references to optional libraries the app doesn't ship (OkHttp's extra TLS providers). They only
change when such a library is added. A missing keep rule doesn't fail the build; the packaged app
crashes when it first reaches that code. So before publishing, try the release package against a
throwaway diary:

```
open -n --env "JAVA_TOOL_OPTIONS=-Dsecrt.dataDir=/tmp/secrt-try" \
  app/desktop/build/compose/binaries/main-release/app/secrt.app
```

and go through: create a diary (with a recovery key), write an entry with Markdown and an
attachment, preview it, search, lock and unlock, export and import a `.secrt` file, connect a
WebDAV backup and back up, sign in to Google Drive, pair with a phone over the LAN, and quit.
