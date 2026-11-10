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

Raise `versionCode` (and `versionName`) in `app/android/build.gradle.kts` for every release:
Play and F-Droid refuse a version code they already have.
