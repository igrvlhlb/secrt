# Backlog

Pending work, grouped by area. The architecture and the reasoning behind it live in
[docs/architecture.md](docs/architecture.md).

Status: `[ ]` todo · `[~]` in progress · `[x]` done.

## Milestone 1 — local-only Android app (current)
- [~] Platform-independent core: model, crypto, vault, merge engine, storage, session
- [ ] Android specifics: SQLCipher driver, file locations, app wiring
- [ ] Compose UI: setup/unlock, journal, folders, labels, editor, settings

## Sync & devices
- [ ] LAN pairing via QR: **desktop generates the QR**, phone scans it (deferred on purpose)
- [ ] Numeric-code pairing with a PAKE (CPace on ristretto255)
- [ ] Authenticated, encrypted LAN channel (ephemeral X25519 + identity signatures, or Noise XX)
- [ ] mDNS discovery of already-paired devices (Android NsdManager, iOS Network.framework, desktop JmDNS)
- [ ] Sync protocol over the channel (exchange version vectors, then missing revisions)
- [ ] Peer table: paired devices, names, identity keys, last-seen version vectors
- [ ] Bump the "shared seq" watermark on every outbound transfer (sync, backup, export), so local
      revisions are no longer amended in place once they leave the device
- [ ] Conflict resolution UI: side-by-side versions, pick one or merge by hand (data layer is done)
- [ ] Entry history UI (revisions are already kept)

## Backup & export
- [ ] `.secrt` encrypted archive export/import (vault.key + revision segments + blobs)
- [ ] Plaintext Markdown export (zip, YAML front-matter, `_attachments/`); requires re-entering the password
- [ ] `BackupTarget` interface + WebDAV implementation (home servers, Nextcloud)
- [ ] Google Drive `appDataFolder` target
- [ ] OneDrive, Dropbox, S3-compatible targets
- [ ] Restore-from-backup flow on the first screen of a fresh install
- [ ] Markdown import (from other apps / previous plaintext exports)

## Platforms
- [ ] Desktop (JVM) app module: window, file picker, encrypted DB via `sqlite-jdbc-crypt`
- [ ] Desktop: generate the pairing QR code
- [ ] iOS app: SQLCipher-linked SQLDelight native driver, Keychain, photo picker
- [ ] Web client (in-browser, talks to WebDAV/Drive backups directly; no LAN sync)

## Security & privacy
- [ ] Biometric unlock (DEK wrapped by Android Keystore / iOS Keychain)
- [ ] Configurable auto-lock timeout (currently fixed)
- [ ] Review memory hygiene: passwords are `String`s today and cannot be wiped
- [ ] Screenshot protection toggle (FLAG_SECURE is always on for now)

## Editor & content
- [ ] Inline images in Markdown (`![](attachment://<id>)`) rendered from encrypted blobs
- [ ] Streamed (chunked) encryption for large attachments; today a file is encrypted in memory (20 MB cap)
- [ ] Garbage-collect blobs no longer referenced by any revision
- [ ] Full-text search with SQLite FTS5 (today: `LIKE` on title and body)
- [ ] Recursive folder delete (today deleting a folder moves its contents up one level)
- [ ] Calendar view of entries
- [ ] Entry templates / daily prompt

## Engineering
- [ ] CI (build + `allTests`)
- [ ] Release build: R8 rules for JNA/libsodium and SQLCipher, signing
- [ ] Database schema migrations policy (SQLDelight `.sqm` files) before the first public build
