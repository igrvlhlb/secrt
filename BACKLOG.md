# Backlog

Pending work, grouped by area. The architecture and the reasoning behind it live in
[docs/architecture.md](docs/architecture.md).

Status: `[ ]` todo · `[~]` in progress · `[x]` done.

## Milestone 1 — local-only Android app (current)
- [x] Platform-independent core: model, crypto, vault, merge engine, storage, session
- [x] Android specifics: SQLCipher driver, file locations, app wiring
- [x] Compose UI: setup/unlock, journal, folders, labels, editor, settings

## Sync & devices
- [x] LAN pairing via QR: the computer shows a one-time code, the phone scans it (CameraX + ZXing)
- [x] Set up a computer from the phone: it receives the encrypted diary and opens it with the password
- [ ] Numeric-code pairing with a PAKE (CPace on ristretto255)
- [x] Authenticated, encrypted LAN channel (ephemeral X25519 via crypto_kx + Ed25519 identity signatures, SIGMA-I style)
- [ ] mDNS discovery of already-paired devices (Android NsdManager, iOS Network.framework, desktop JmDNS);
      today the phone remembers the computer's address and fixed port, and a new pairing code updates them
- [x] Sync protocol over the channel (exchange version vectors, then missing revisions and attachments)
- [ ] Start a sync from the computer (today only the phone connects; the computer listens on its Devices screen)
- [ ] Sync on its own while both apps are open (today: "Sync now")
- [ ] Phone ↔ phone and computer ↔ computer pairing (a device that can both scan and show codes)
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
- [ ] `BackupTarget` interface + WebDAV implementation (home servers, Nextcloud)
- [ ] Google Drive `appDataFolder` target
- [ ] OneDrive, Dropbox, S3-compatible targets
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
- [ ] Desktop: idle auto-lock while the window stays open; today it locks after being minimized for a
      minute, on system sleep or user switch, and on quit, but not when merely left in the background
- [ ] Desktop: keyboard shortcuts (new entry, search, lock) and a menu bar
- [ ] Desktop: a two-pane layout (list + editor) for wide windows; today the phone layout is centered
- [ ] iOS app: SQLCipher-linked SQLDelight native driver, Keychain, photo picker
- [ ] Web client (in-browser, talks to WebDAV/Drive backups directly; no LAN sync)

## Security & privacy
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
- [ ] Localization (strings are hard-coded in English)
- [ ] UI tests (Compose) and an on-device test that the SQLCipher file holds no plaintext (the JVM
      driver already has one)
- [ ] CI (build + `allTests`)
- [ ] Release build: R8 rules for JNA/libsodium and SQLCipher, signing
- [x] Database schema migrations: version-1 snapshot, `verifyMigrations`, upgrade test (see docs/architecture.md)
