# secrt — Encrypted, local-first diary app: architecture & roadmap

## Context
Greenfield project (repo holds only `idea.txt` + `AGENTS.md`). Goal: a private diary whose data lives
encrypted on the user's own devices, with **no mandatory server cost**. Optional, user-triggered
backup to the user's own cloud (Google Drive, OneDrive, home server). Built with Kotlin/Compose
Multiplatform for Android, iOS, Desktop; web client postponed. Per `idea.txt`, settle the
decisions and core architecture first, then features, then UI/UX.

## Decisions (agreed)
| Topic | Decision |
|---|---|
| Platforms | KMP + Compose Multiplatform: Android, iOS, Desktop (JVM). Web later. |
| Device model | **Equal replicas.** Every device has a full encrypted copy and works offline. The backup is just another participant. No "main" device. |
| Sync | **Revision history + merge.** Union of revisions; auto-merge when possible; "keep both, user chooses" otherwise. |
| Encryption at rest | **Whole database** (SQLCipher), so titles, labels and folder names are all protected. Attachments are separate encrypted files. |
| Indexing | No separate index file. SQLCipher decrypts pages on demand, so normal SQL indexes and FTS5 search work inside the encrypted DB. |
| Folders | `folder(id, parent_id, name)` tree instead of a `path` string. Rename/move changes one record, not N entries, which also means fewer sync conflicts. |
| Content | Markdown body + attachments (images/files). |
| Password recovery | Optional recovery key at setup (on by default, skippable). |
| Export | Both: encrypted archive (`.secrt`) and plaintext Markdown zip (re-enter password + warning). |
| Backups | Manual only (no scheduling). |
| Custom storage | No custom protocol. Support **WebDAV** (Nextcloud, NAS, home servers), S3-compatible later. Backup data is opaque ciphertext, so the server needs no logic or trust. |

## Threat model (what we protect against)
- Lost/stolen device (data at rest), cloud provider reading backups, LAN eavesdroppers, unpaired devices on the LAN.
- Out of scope: malware on an unlocked device.

## Cryptography (`core:crypto`, `core:vault`)
- Library: libsodium via a KMP binding (candidate: `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings`; verify in spike).
- **Data key (DEK):** random 256-bit; encrypts the DB (SQLCipher raw key), attachments, and backup segments.
- **Key slots** (LUKS-style) in a small `vault.key` header:
  - Password slot: Argon2id(password, salt, params stored in header) → KEK → XChaCha20-Poly1305(DEK).
  - Recovery slot (optional): random 128+ bit key shown as words/groups → wraps the same DEK.
  - Password change = rewrap the DEK only; no re-encryption.
- Device identity: Ed25519/X25519 keypair per device, stored inside the encrypted DB. Used for pairing and the LAN channel.
- Desktop always asks for the password on open (per notes). Mobile biometric unlock (DEK wrapped by Keystore/Keychain) comes later. Auto-lock on idle.

## Data model (`core:storage`, SQLDelight)
- `revision(rev_id, record_type, record_id, parent_rev_ids, device_id, seq, hlc, deleted, payload)`: **source of truth**, append-only.
- Materialized views of the current state, rebuilt from revisions:
  - `entry(id, folder_id, title, body_md, diary_date, created_at, updated_at, head_rev, conflicted)`
  - `folder(id, parent_id, name, head_rev)`
  - `label(id, name, color, head_rev)`
  - `entry_label(entry_id, label_id)`: the label set is part of the entry revision.
  - `attachment(blob_id, mime, size, filename)`: referenced from Markdown as `attachment://<blob_id>`.
  - FTS5 table over title/body.
- `peer(device_id, name, identity_pubkey, version_vector)` and `self(device_id, keys, next_seq)`.
- Attachments: encrypted files on disk (libsodium secretstream), **content-addressed** (`blob_id = HMAC(DEK, sha256(plain))`). They are immutable, so they never conflict and are deduplicated.
- Keeping full revision history is cheap for text and gives "entry history" for free.

## Sync engine (`core:sync`, pure Kotlin, transport-agnostic)
- Each device numbers its own revisions (`seq`). A **version vector** `{deviceId → max seq seen}` says what a replica has.
- Sync = exchange vectors, then send the missing revisions both ways. Operations are idempotent and order-independent, so replicas converge.
- Hybrid Logical Clock (HLC) on each revision for ordering and last-writer-wins on metadata.
- Merge rules per record:
  - Different records (e.g. new entries on two devices): no conflict.
  - One revision descends from the other: fast-forward.
  - Concurrent edits, different fields: field-level merge (HLC LWW for title/date/folder/labels).
  - Concurrent body edits: three-way text merge against the common ancestor. If the edits overlap, keep both versions and set `conflicted = true`; the UI shows both, and the user picks or merges, which creates a new revision with both as parents.
  - Delete vs. edit: keep the edit and flag it.
- Same engine is used for LAN sync, backup pull/push, and encrypted import.

## Backup & archive format (`core:archive`, `core:backup`)
**`.secrt` export file (implemented).** A standard ZIP, entries *stored* (not compressed, since
they're already encrypted), timestamps fixed to 1980-01-01. `unzip -l` lists it. Entries, in order:
```
manifest.json          {"format":1,"vaultId":"…"}                      plain
vault.key              key slots, identical to the device's file       plain, no secrets in clear
log/<deviceId>.seg     JSON list of that device's revisions             XChaCha20-Poly1305, archive subkey,
                                                                        AAD = vault id + entry name
blobs/<blobId>         attachments, copied as stored on the device      encrypted with the blob subkey
```
- Restore (first run): read `vault.key`, unlock it with the backup's password or recovery key, then
  apply the revisions to a fresh database. `vault.key` is installed last, so a failed restore
  leaves the app in "first run" state.
- Import (merge): only backups of the same diary (same vault id); revisions go through the same
  merge engine as sync. Backups of another diary are refused for now, since their blob ids and
  keys differ.
- Exporting marks this device's revisions as shared, so they are never amended in place afterwards.

Cloud backup layout (planned; same building blocks):
```
secrt/<vaultId>/
  vault.key                    # key slots (no secrets in clear)
  log/<deviceId>-<n>.seg       # encrypted batches of revisions; each device only ever adds its own files
  blobs/<blob_id>              # encrypted attachments
```
- **Append-only, one writer per file**: two devices can never overwrite each other's backup data. Merging happens on the clients through the sync engine, so pushes need no locking.
- Push = upload new segments + missing blobs (incremental). Pull = download unknown segments/blobs, then merge.
- Desktop without the phone: pull backup, then edit, then push. The phone later pulls or LAN-syncs, and everything merges.
- `BackupTarget` interface: `list(prefix)`, `get(path)`, `put(path, bytes)`, `delete(path)`.
  1. WebDAV (first, easy to test locally; covers home servers and Nextcloud)
  2. Google Drive `appDataFolder` (scope `drive.appdata`, like WhatsApp)
  3. Later: OneDrive (Graph `approot`), Dropbox app folder, S3-compatible.
- OAuth: desktop loopback redirect + PKCE; Android Credential Manager/Authorization API; iOS ASWebAuthenticationSession.
- Plaintext export: zip of `<folder path>/<date>-<title>.md` with YAML front-matter (id, dates, labels), plus `_attachments/` linked relatively.

## LAN pairing & sync (`platform:lan`, `platform:qr`)
- Phone must explicitly enter **"Add device" mode**. Nothing is advertised otherwise, so desktops never see a list of devices.
- **Pairing A, QR (first):** desktop shows a QR containing `{ip:port, desktop identity pubkey, one-time secret}`. The phone scans it and connects directly; no discovery is needed. Both prove knowledge of the secret, exchange identity keys, and store each other as `peer`.
- **Pairing B, numeric code (later):** while in pairing mode the phone advertises over mDNS and shows a 6-digit code, which the user types on the desktop. Uses a PAKE (CPace on ristretto255) so the short code can't be brute-forced offline.
- After pairing: the desktop gets `vault.key` and the revisions, and the user types the password to unlock (as in your notes).
- Later syncs: mDNS `_secrt._tcp` advertised only while the app is open on a sync screen. Only paired identities are accepted. The channel uses an authenticated handshake (ephemeral X25519 + identity signatures, then secretstream; Noise XX if a vetted KMP implementation exists).
- Platform specifics (expect/actual):
  - Discovery: Android NsdManager, iOS Network.framework (needs `NSLocalNetworkUsageDescription`, `NSBonjourServices`), desktop JmDNS.
  - QR generation is shared (e.g. `qrose`). Scanning: Android CameraX + ML Kit/ZXing, iOS AVFoundation.
  - iOS only syncs in the foreground.

## Module layout
```
build-logic/        convention plugins
core/model          ids, HLC, entities, revision types
core/crypto         libsodium wrapper (KDF, AEAD, kx, sign, secretstream)
core/vault          key slots, unlock, password change, recovery key
core/storage        SQLDelight schema, repositories, expect DriverFactory (SQLCipher)
core/sync           version vectors, merge engine, protocol messages
core/archive        segment format, .secrt import/export, Markdown export
core/backup         BackupTarget + WebDAV, Google Drive
platform/lan        expect/actual sockets + mDNS
platform/qr         QR render (common) + scanner (android/ios)
feature/*           UI screens (later)
app/android, app/desktop, iosApp (Xcode)
```
Rule: `core/*` is `commonMain` only, apart from the `DriverFactory` and crypto actuals. Platform differences live in `platform/*` behind interfaces.

## Running the desktop app
- `./gradlew :app:desktop:run` opens the app; `./gradlew :app:desktop:packageDmg` builds an
  (unsigned) macOS installer in `app/desktop/build/compose/binaries/`.
- Data lives in `~/Library/Application Support/secrt` (macOS), `%APPDATA%\secrt` (Windows) or
  `$XDG_DATA_HOME/secrt`, defaulting to `~/.local/share/secrt` (Linux): `vault/` (key slots and
  attachments) and `secrt.db` (SQLCipher-encrypted via `sqlite-jdbc-crypt`).
- To use another directory, e.g. a throwaway diary: `-Psecrt.dataDir=/some/dir` on `run`, or
  `-Dsecrt.dataDir=…` / `SECRT_DATA_DIR=…` for the packaged app.
- To move a diary from the phone: export a backup in Settings, then pick "Restore from a backup file"
  on the desktop's first screen.

## Roadmap
0. **Spikes:**
   - SQLDelight + SQLCipher + FTS5 on all three targets (Android `net.zetetic:sqlcipher-android`, iOS SQLCipher linked into the native driver, JVM `sqlite-jdbc-crypt`).
   - libsodium bindings on all three targets (Argon2id, XChaCha20, X25519/Ed25519, ristretto255).
   - TCP server on iOS (ktor-network) and mDNS on each platform.
1. Gradle skeleton + `core/model`, `core/crypto`, `core/vault`, `core/storage`.
2. `core/sync` merge engine, tested heavily with in-memory replicas.
3. `core/archive`: encrypted export/import + Markdown export. Add a **minimal UI shell** (desktop + Android: unlock, list, edit) to try things out. File export/import already gives a manual sync path.
4. `core/backup`: WebDAV, then Google Drive.
5. LAN: QR pairing, encrypted channel, sync. Then numeric code + PAKE.
6. Full UI/UX: editor, folder tree, labels, attachments, search, conflict resolver, history, settings.
- **Later:** OneDrive/Dropbox/S3, biometric unlock, Markdown import, web client. The web client could run fully in the browser against the WebDAV/Drive backup with client-side crypto, so no hosting of user data is needed. Browsers can't do raw TCP or mDNS, so it wouldn't use LAN sync.

## Verification
- `./gradlew allTests` with `commonTest` for every core module, run on JVM, Android, and iOS simulator:
  - Crypto: known-answer tests; wrong-password and tampered-ciphertext rejection; recovery-key unlock; password change.
  - Sync: property-based tests. Random edits on N replicas, synced in random orders, must converge to identical state with no lost revisions. Specific tests for each merge rule and conflict.
  - Archive: `.secrt` round-trip (export, then import into an empty vault, gives identical state); Markdown export snapshot test.
- Backup: integration tests against a local WebDAV container (e.g. `bytemark/webdav` or Nextcloud in Docker).
- End-to-end manual:
  1. Android emulator + desktop: pair via QR, write on both offline, sync, and check the merge and conflict UI.
  2. Desktop pushes a backup; a fresh install restores from it with the password, then with the recovery key.
  3. Inspect the DB file and backup files to confirm no plaintext (`strings` / hexdump).
