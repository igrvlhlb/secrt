# Backlog

Pending work, grouped by area. The architecture and the reasoning behind it live in
[docs/architecture.md](docs/architecture.md).

Status: `[ ]` todo · `[~]` in progress · `[x]` done.

## Milestone 1 — local-only Android app (current)
- [x] Platform-independent core: model, crypto, vault, merge engine, storage, session
- [x] Android specifics: SQLCipher driver, file locations, app wiring
- [x] Compose UI: setup/unlock, journal, folders, labels, editor, settings

## Sync & devices
- [x] LAN pairing via QR: a device shows a one-time code, a phone scans it (CameraX + ZXing)
- [x] Set up a computer from the phone: it receives the encrypted diary and opens it with the password
- [x] Set up a phone from another device ("Set up from another device" on its first screen): it scans
      the code of a device that has the diary and receives it, once the user confirms on that device
      (see "LAN join" under Security & privacy)
- [ ] Numeric-code pairing with a PAKE (CPace on ristretto255)
- [x] Authenticated, encrypted LAN channel (ephemeral X25519 via crypto_kx + Ed25519 identity signatures, SIGMA-I style)
- [ ] mDNS discovery of already-paired devices (Android NsdManager, iOS Network.framework, desktop JmDNS);
      today the phone remembers the computer's address and fixed port, and a new pairing code updates them
- [x] Sync protocol over the channel (exchange version vectors, then missing revisions and attachments)
- [x] One password on every device: syncs first agree on the most recent password / recovery-key
      change; the device that would change asks its user, and a "no" (on either side) cancels the sync
- [ ] Start a sync from the device that showed the code, e.g. the computer (today only the one that
      scanned connects; the other listens on its Devices screen). Two phones can scan each other's code
- [ ] Sync on its own while both apps are open (today: "Sync now")
- [ ] Accept syncs from paired devices on any screen while unlocked and in the foreground (today the
      receiving side must have its Devices screen open; the requester still starts from Devices).
      Settings switch, on by default. Trade-offs: any device on the Wi-Fi can probe the port and tell
      secrt is running (worse on public networks), and the pre-authentication code is exposed for
      longer. **Prerequisite:** the editor must save against the revision it opened (today `commit`
      uses the heads at save time), or a change synced in while editing the same entry is overwritten
- [ ] Live session between connected paired devices (changes flow while connected; idea, not designed)
- [x] Phone ↔ phone pairing and sync: phones show codes too (one shows, the other scans)
- [ ] Computer ↔ computer pairing: neither can scan, so it needs a code typed or pasted (e.g. the PAKE one)
- [ ] Android: when `targetSdk` moves to 37, declare and request `ACCESS_LOCAL_NETWORK` before LAN use
      (not needed, and not to be requested, below 37)
- [ ] Stream sync batches instead of holding all received revisions in memory until the end
- [ ] Re-request attachments still missing locally (today only those referenced by revisions received in that sync)
- [ ] Show the other device's identity fingerprint, so users can compare it after pairing
- [x] Peer table: paired devices, names, identity keys, remembered addresses, last sync, last-seen version vectors
- [x] "Shared seq" watermark: `DiaryStore.revisionsSince` marks what it returns as shared, so
      revisions that left the device are never amended in place. New transports must export through it.
- [ ] Conflict resolution UI: side-by-side versions, pick one or merge by hand (data layer is done)
- [ ] Entry history UI (revisions are already kept)

## Backup & export
- [x] `.secrt` encrypted archive export/import (vault.key + revision segments + blobs)
- [ ] Plaintext Markdown export (zip, YAML front-matter, `_attachments/`); requires re-entering the password
- [x] Cloud backup engine (`core:backup`): `BackupTarget` interface, append-only per-device segments,
      push / pull / sync, restore from cloud on first run, bookkeeping and credentials in the encrypted DB
- [x] WebDAV target (home servers, NAS, Nextcloud; finds Nextcloud's folder from the server address)
- [x] Settings → Cloud backup (connect, test, back up, sync, disconnect) and "Restore from cloud backup" on first run
- [x] Google Drive `appDataFolder` target (like WhatsApp), with Google sign-in on Android
      (Play services AuthorizationClient) and the desktop (browser, loopback + PKCE). Works on
      Android against real Google; the desktop is off until a Desktop OAuth client is configured
- [ ] Google Drive: "Disconnect" could also revoke secrt's access (today Google keeps the grant
      until the user removes it under their Google account's third-party access)
- [ ] Google Drive on a release build: register Play's app signing and the upload key's SHA-1s (docs/release.md)
- [ ] F-Droid: a build flavor without Google Play services (F-Droid takes no closed-source
      libraries); Drive sign-in there through the browser or not at all, to be decided
- [ ] Flaky: `LanSyncTest.refusesToPairDifferentDiaries` once got `ConnectionLost` after 15 s instead
      of `DifferentDiary` during a full `allTests` run (passes alone and on rerun); likely a race
      between the host's failure message and closing the connection, under load
- [ ] OneDrive, Dropbox, S3-compatible targets (the `BackupTarget` interface is all they need)
- [ ] WebDAV: servers with self-signed certificates (trust a pinned certificate); today TLS must validate
- [ ] Cloud backup: prune the backup (compact old segments, delete orphaned blobs); today it only grows
- [ ] Cloud backup: show the backup's devices and sizes; cancel a running backup
- [x] Cloud backup: when another device changed the password, offer to adopt the backup's `vault.key`:
      the most recent key-slot change wins, through a sealed copy (`vault.key.sealed`); pull and sync
      ask first, and a "no" syncs nothing (LAN sync does the same, see docs/security.md)
- [ ] Cloud backup: stream segments and blobs instead of holding each file in memory (fine at the 20 MB cap)
- [x] Restore-from-backup flow on the first screen of a fresh install
- [ ] Import a backup of a *different* diary (re-encrypt its attachments and remap blob ids)
- [ ] Replace the current diary with a backup (today restore is only offered on first run)
- [ ] Stream archives instead of holding each entry in memory (fine today: attachments are capped at 20 MB)
- [ ] Markdown import (from other apps / previous plaintext exports)

## Platforms
- [x] Desktop (JVM) app module: window, file pickers, encrypted DB via `sqlite-jdbc-crypt`, restore from a phone backup
- [x] Desktop: generate the pairing QR code
- [ ] Desktop: sign and notarize the macOS app (today the `.dmg` is unsigned, so Gatekeeper blocks it); `.icns` icon
- [ ] Desktop: Windows/Linux packages built and tried on those OSes (declared, never built)
- [ ] Desktop: RPM package for Linux (`TargetFormat.Rpm`; the build host needs `rpm-build`, so a Linux CI runner or machine)
- [ ] Desktop: idle auto-lock while the window stays open; today it locks after being minimized for a
      minute, on system sleep or user switch, and on quit, but not when merely left in the background
- [ ] Desktop: keyboard shortcuts (new entry, search, lock) and a menu bar
- [ ] Desktop: a two-pane layout (list + editor) for wide windows; today the phone layout is centered
- [ ] iOS app: SQLCipher-linked SQLDelight native driver, Keychain, photo picker
- [ ] Web client (in-browser, talks to WebDAV/Drive backups directly; no LAN sync)

## Security & privacy
- [x] LAN join: the sending device asks before sending its diary to a device without one, naming it, both
      when the phone scans a computer's "Set up from your phone" code ("Send a copy of your encrypted diary
      to …?") and when a new device scans this one's code ("… wants a copy of your diary. Send it?").
      A "no", no answer within 90 s, or locking sends nothing, and the other side is told why
- [x] Biometric unlock on Android, opt-in: DEK wrapped by an AES-GCM Android Keystore key (StrongBox when
      present) that needs a Class 3 biometric for every use and dies when fingerprints change
- [ ] Biometric unlock on iOS (Keychain + LocalAuthentication)
- [ ] Biometric unlock on Android 8 (left out: the support library's fallback dialog needs an AppCompat theme)
- [ ] Upgrade `androidx.biometric` from 1.1.0 (latest stable) once 1.4 is stable
- [x] Configurable auto-lock timeout (immediately … 1 hour, or only on app restart), kept in a plaintext
      `settings.json` readable while locked
- [ ] Review memory hygiene: passwords are `String`s today and cannot be wiped
- [ ] Screenshot protection toggle (FLAG_SECURE is always on for now)

## Editor & content
- [ ] Open/share non-image attachments (decrypt to a temp file + FileProvider); today only images can be viewed
- [ ] Downsample image thumbnails (today the full image is decoded)
- [ ] Inline images in Markdown (`![](attachment://<id>)`) rendered from encrypted blobs
- [ ] Streamed (chunked) encryption for large attachments; today a file is encrypted in memory (20 MB cap)
- [ ] Garbage-collect blobs no longer referenced by any revision
- [ ] Full-text search with SQLite FTS5 (today: `LIKE` on title and body)
- [ ] Recursive folder delete (today deleting a folder moves its contents up one level)
- [ ] Calendar view of entries
- [ ] Entry templates / daily prompt

## Engineering
- [x] Localization: English and Brazilian Portuguese, following the system (per-app language on
      Android 13+); core errors translated through typed exceptions (docs/architecture.md, "Languages")
- [ ] Localization leftovers: exceptions the UI doesn't know show their own (English or system)
      message; technical details stay English ("The backup is damaged: <detail>", the LAN security
      check's reason, Google Drive's own explanation when it refuses something, Google's error
      codes); default device names ("Unnamed device", "Computer") are stored and sent to other
      devices in English; file sizes use "1.5 MB" whatever the language; Android "Couldn't
      open/write <file>" when the system gives no stream
- [ ] UI tests (Compose) and an on-device test that the SQLCipher file holds no plaintext (the JVM
      driver already has one)
- [ ] CI (build + `allTests`)
- [x] Release signing from Gradle properties (docs/release.md); unsigned without them, for F-Droid
- [x] Release build shrunk with R8; keep rules for JNA/libsodium (docs/release.md). Tried on the phone:
      create, unlock (password, recovery key), write, search, export/import, pairing QR and scanner
- [ ] Release build: try LAN pairing and sync between a shrunk build and another device
- [x] Settings "About your data" reworded now that cloud backup and LAN sync exist (both opt-in)
- [ ] Randomized convergence test: several replicas editing and syncing in random orders must end identical
- [x] Database schema migrations: version-1 snapshot, `verifyMigrations`, upgrade test (see docs/architecture.md)
