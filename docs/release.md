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
