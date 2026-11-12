# Working on secrt

Rules for anyone changing the code, people and coding agents alike. Start with
[docs/README.md](docs/README.md) for how the app works and [BACKLOG.md](BACKLOG.md) for what's
pending. If a `private-docs/` folder exists, read it too: it holds the maintainer's local notes.

## Commits
- Do not add co-authoring message to AI-made commits (no `Co-Authored-By` or other AI attribution
  lines).
- One change per commit, with a message that says what it does and why.

## Building and testing
- Tests: `./gradlew allTests`. Run them before committing.
- Builds: `./gradlew :app:android:assembleDebug :app:android:assembleRelease :app:desktop:compileKotlin`.
- Debug builds are a separate app (`io.github.igrvlhlb.secrt.debug`, "secrt-debug") and install
  next to a release one without touching its data.
- The desktop app keeps its diary in the user's data dir. For anything that isn't your own real
  diary, run it against a throwaway one: `./gradlew :app:desktop:run -Psecrt.dataDir=<scratch dir>`.

## Secrets
- Never commit `local.properties` (Google OAuth client for the desktop), keystores, or the
  `secrt.signing.*` properties. They live outside the repository; see
  [docs/release.md](docs/release.md).
- Test data uses made-up accounts, addresses and devices, never real ones.

## Code
- `core/*` modules are platform-independent (`commonMain`) apart from a few `expect`/`actual`
  pieces; platform code lives in the apps and in `ui`'s platform source sets.
- The diary never leaves a device unencrypted, and only when the user asks. Keep it that way, and
  update [docs/security.md](docs/security.md) when a change touches what goes where.
- Schema changes go through migrations: see "Changing the database schema" in
  [docs/architecture.md](docs/architecture.md). Never edit or delete an existing schema snapshot.
- Every text the user sees goes in both `strings.xml` files (English and `values-pt-rBR`).
  `StringsTest` fails otherwise. Errors from `core/*` are typed exceptions mapped to texts in
  `ui/text/ErrorMessages.kt`; see "Languages" in docs/architecture.md.
- The release build is shrunk by R8. After adding a library that uses reflection, JNI or JNA, add
  its keep rules to `app/android/proguard-rules.pro` if it ships none, and try a release build on a
  device (docs/release.md explains how to install one next to the real app).
- Keep docs and `BACKLOG.md` up to date in the same commit as the change.
