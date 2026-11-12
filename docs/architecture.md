# secrt — Encrypted, local-first diary app: architecture & roadmap

> Design reference: decisions, formats and protocols. For a guided tour of how the app works,
> start with [docs/README.md](README.md).

## Context
Goal: a private diary whose data lives encrypted on the user's own devices, with **no mandatory
server cost**. Optional, user-triggered backup to the user's own cloud (Google Drive, OneDrive, home
server). Built with Kotlin/Compose Multiplatform for Android, iOS, Desktop; web client postponed.
The decisions and core architecture came first, then features, then UI/UX.

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
  - Password change = rewrap the DEK only; no re-encryption. Each key-slot change is stamped
    (`slotsChanged`: HLC + the device's name sealed with the archive key), and the most recent one
    travels to the user's other devices through LAN sync and cloud backup, after asking on the
    device that would change (see docs/security.md, "Changing the password").
- Device identity: an Ed25519 key pair per replica, its 32-byte seed kept in the encrypted DB (`meta`).
  Used to authenticate LAN connections; key exchange uses fresh X25519 keys per connection (see below).
- Desktop always asks for the password on open (per notes).
- **Biometric unlock (Android, opt-in, off by default).** Turning it on generates an AES-256-GCM key
  in the Android Keystore (StrongBox if the device has one, else the TEE) that requires a Class 3
  biometric for *every* use (`BiometricPrompt.CryptoObject`) and is invalidated when a fingerprint
  is enrolled or all are removed. The DEK, encrypted with it, sits in `files/biometric.key`; the
  plaintext DEK never touches disk. Unlocking decrypts it behind the prompt and opens the vault with
  `VaultManager.unlockWithDataKey`, which first checks the DEK against a key-check value in
  `vault.key` (a KDF subkey of the DEK; optional field, so format 1 still reads). An invalidated or
  mismatched key turns the feature off and the lock screen says why. Creating or restoring a diary
  deletes any leftover biometric key; changing the password doesn't affect it (same DEK). Only the
  platform-neutral `BiometricKeyStore` interface is common code; platforms without one (desktop)
  simply don't offer the toggle.
- Auto-lock after the app spends a user-chosen time in the background (default 1 minute; also
  "immediately" and "only on app restart"). A timer closes the database while the app is away and
  the elapsed time is checked again on return, in case the system froze the process. Settings that
  must work while locked live in a small plaintext `settings.json` next to `vault.key`; nothing in
  it is sensitive.

## Data model (`core:storage`, SQLDelight)
- `revision(rev_id, record_type, record_id, parent_rev_ids, device_id, seq, hlc, deleted, payload)`: **source of truth**, append-only.
- Materialized views of the current state, rebuilt from revisions:
  - `entry(id, folder_id, title, body_md, diary_date, created_at, updated_at, head_rev, conflicted)`
  - `folder(id, parent_id, name, head_rev)`
  - `label(id, name, color, head_rev)`
  - `entry_label(entry_id, label_id)`: the label set is part of the entry revision.
  - `attachment(blob_id, mime, size, filename)`: referenced from Markdown as `attachment://<blob_id>`.
  - FTS5 table over title/body.
- `peer(identity_key, name, device_id, paired_at, last_sync_at, addresses, version_vector)` (schema version 2):
  devices paired for LAN sync. Local to each replica, never synced. This device's id, shared-seq
  watermark and LAN identity seed live in `meta`.
- Attachments: encrypted files on disk (libsodium secretstream), **content-addressed** (`blob_id = HMAC(DEK, sha256(plain))`). They are immutable, so they never conflict and are deduplicated.
- Keeping full revision history is cheap for text and gives "entry history" for free.

### Changing the database schema
Diaries already exist on phones, so the schema only moves forward through migrations:
1. Edit the `CREATE TABLE`/`CREATE INDEX` statements in the `.sq` files as usual.
2. Add `core/storage/src/commonMain/sqldelight/migrations/<N>.sqm`, where `N` is the version being
   upgraded *from* (the first migration is `1.sqm`). It holds the `ALTER TABLE`/`CREATE ...`
   statements that turn version `N` into `N + 1`. SQLDelight derives `Schema.version` from it and
   the drivers run it when they open an older database.
3. Run `./gradlew :core:storage:generateCommonMainSecrtDatabaseSchema` to add the `<N + 1>.db`
   snapshot next to `databases/1.db`, and commit it. Never edit or delete existing snapshots: each
   one is a schema some installed version has.
4. `verifySqlDelightMigration` (run by `check` and `:core:storage:jvmTest`) fails if any snapshot
   plus the migrations after it doesn't end up identical to the `.sq` schema.
   `SchemaMigrationTest` opens every snapshot with the current code and reads version-1 data back.
Derived tables (`entry`, `folder`, ...) could instead be rebuilt from `revision` rather than
migrated column by column; the revision log itself must always be migrated in place.

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

**Cloud backup (implemented, `core:backup`; WebDAV target).** Same building blocks, laid out as
files under the target's root:
```
secrt/<vaultId>/
  manifest.json                # {"format":1,"vaultId":"…"}; plain. A newer format is refused.
  vault.key                    # key slots, as on the devices (no secrets in clear)
  vault.key.sealed             # the same, sealed with the archive subkey,
                               #   AAD = "secrt-archive:<vaultId>:vault.key.sealed"
  log/<deviceId>-<n>.seg       # revisions, sealed exactly like .secrt segments: archive subkey,
                               #   AAD = "secrt-archive:<vaultId>:log/<deviceId>-<n>.seg"
  blobs/<blobId>               # attachments, encrypted exactly as stored on the devices
```
- **Append-only, one writer per file**: a device only ever creates `log/<its id>-<n>.seg`, with `n`
  one above the highest of its files already listed, and never rewrites one. Two devices can't
  overwrite each other's data, so pushes need no locking or conditional writes; merging happens on
  the clients through the sync engine. A segment may hold other devices' revisions (e.g. learned by
  LAN sync): the backup must get everything the pushing device has.
- **Push** (manual): `manifest.json` if missing; `vault.key` if missing, or if this device's
  key-slot stamp is newer than the sealed copy's (so a device that never saw another's password
  change can't put the old password back), first in clear, then sealed (if the push stops in
  between, the sealed copy is still the older one and the next push uploads both again; on a backup
  without a sealed copy, made before it existed, the plain one's stamp is compared, or failing
  stamps, the old rule applies: only if it changed on this device); then the blobs the new
  revisions reference that aren't listed under
  `blobs/`; then the revisions as segments of at most 2000. The revisions come from
  `DiaryStore.revisionsSince(remote)`, so this device's revisions are marked shared and never
  amended again. **Pull**: list `log/`, download the segments not seen yet, decrypt and check all of
  them and the blobs they need (blob ids are keyed hashes, checked on arrival), and only then
  `applyRemote`. Any damaged, tampered or renamed file stops the pull with nothing merged. A blob
  missing on the server (its writer never had it) is counted and skipped. **Sync** = pull, then push.
- **Key slots on pull and sync**: if the sealed `vault.key` (opened with the archive key, checked
  with `VaultManager.verifyExternal`: same diary id, key-check value of this data key) has a newer
  stamp than this device's, the `confirmKeySlots` callback asks the user first, *outside* the
  session lock (the user may take a while; the diary must still be able to lock). No: nothing is
  downloaded or uploaded (`KeySlotsChangeDeclinedException`). Yes: the operation runs, checks that
  the sealed copy is still the one accepted ("the backup changed meanwhile" otherwise), and installs
  it after merging. The plain `vault.key` is never taken on: the server can replace it.
- Bookkeeping per target, in the encrypted DB's `meta` table (`backup.state:<target id>`, JSON): the
  version vector the target is known to hold (read from or written to it), the highest segment
  number read per writing device, the `vault.key` last uploaded, and the last backup time. It is a
  cache: losing it only costs re-reading or re-uploading, since merging is idempotent. If the
  backup was deleted on the server (no `vault.key`, or none of this device's segments are left),
  the next push starts over. The target's settings and credentials (`backup.target`) live in the
  same table: backups only run while the diary is unlocked.
- **Restore on first run**: find `secrt/*/vault.key`, unlock it with the password or recovery key,
  download everything, apply it to a fresh database, remember the target, and install `vault.key`
  last, through `AppSession.restoreFrom`, which wipes everything on any failure (as for `.secrt`).
- Operations run inside `AppSession.withOpenDiary`, holding the session lock: the diary can't lock,
  and wipe the keys, halfway through a backup; a lock request waits for it to finish.
- Desktop without the phone: pull backup, then edit, then push. The phone later pulls or LAN-syncs, and everything merges.
- `BackupTarget` interface: `list(directory)` (direct children, sub-directories with a trailing
  `/`), `get(path)`, `put(path, bytes)` (atomic, creates parents), `delete(path)`; paths are limited
  to `[A-Za-z0-9._-]` segments.
  1. WebDAV (done): Ktor with the OkHttp engine (Android and desktop), Basic auth, `PROPFIND`
     depth 1 / `GET` / `PUT` / `MKCOL` / `DELETE`. Given a bare server address it also tries
     Nextcloud/ownCloud's `/remote.php/dav/files/<user>/`; connecting writes and deletes a probe
     file. Plain `http://` works but the UI warns that the server password is then unprotected.
  2. Google Drive (done, `GoogleDriveTarget`), the way WhatsApp backs up: Drive's hidden
     `appDataFolder`, which only secrt can open (the user sees its size, and can delete it, under
     Drive › Settings › Manage apps). Scopes: `drive.appdata` and `userinfo.email` (to show the
     account), both "non-sensitive", so Google needs no app review. Drive v3 REST over Ktor:
     - Drive has ids, not paths: each file is stored flat under its whole path as its name
       (`secrt/<vaultId>/log/<deviceId>-<n>.seg`), and folders are derived from names. The file list
       is read once per target instance (one per operation) and updated as it writes.
     - Drive allows two files with one name. Two devices creating a shared file at once
       (`vault.key`, `manifest.json`) leave two copies: reads take the newest, the next write
       deletes the others.
     - Uploads: multipart (create) or media (update) up to 5 MB, resumable above.
     - A refused token (401) is replaced once; 429, 5xx and rate-limit 403s are retried with growing
       waits; a full Drive and a disabled Drive API have their own messages.
  3. Later: OneDrive (Graph `approot`), Dropbox app folder, S3-compatible.
- **Google sign-in** (`GoogleSignIn`, one per platform, handed to `SecrtApp`; without one the app
  doesn't offer Google Drive):
  - Android (`AndroidGoogleSignIn`): Google Play services' `AuthorizationClient`. It shows the
    account picker and consent screen, keeps the grant, and hands out one-hour access tokens, so
    secrt stores only the account's email. Google recognises the app by package name and signing
    certificate (an "Android" OAuth client in Google Cloud), so no client ID ships in it. Auto-lock
    is paused while Google's screens are in front.
  - Desktop (`DesktopGoogleSignIn`): OAuth for installed apps with a loopback redirect and PKCE:
    the browser opens Google's consent page, which redirects to a one-off server on `127.0.0.1`. The
    refresh token is kept in the target's settings, in the encrypted database; access tokens only in
    memory. The "Desktop app" OAuth client's id and secret come from `local.properties`
    (`secrt.google.desktopClientId`, `secrt.google.desktopClientSecret`) at build time; that
    secret isn't confidential (it ships in every copy), PKCE is what protects the code.
  - The Google Cloud project must have the Drive API enabled and its OAuth consent screen
    published ("In production"): while "Testing", Google expires refresh tokens after 7 days, so
    the desktop would have to sign in again every week.
  - Later, iOS: ASWebAuthenticationSession.
- Plaintext export: zip of `<folder path>/<date>-<title>.md` with YAML front-matter (id, dates, labels), plus `_attachments/` linked relatively.

## LAN pairing & sync (`core:lan`, `ui/devices`)
Implemented for phone ↔ computer and phone ↔ phone. One device shows a code and listens, the other
scans it and connects; the listening side does so only while the user keeps a screen open for it.
Nothing is advertised on the network.

**Roles.** Every device can show codes (and listen); only devices with a camera can scan them
(`canScanCodes`: phones). The device that scanned is the one that connects, then and for later
syncs, since only it knows the other's address. So a computer never connects out, and two
computers can't pair yet.

**User flow**
- Showing a code: Settings → Devices. While that screen is open (and the diary unlocked) the device
  listens on TCP port 47820 (any free port if taken), and "Show a code" shows a QR code.
- Scanning: Settings → Devices → "Scan a code" (phones) opens the camera (permission asked then).
  Then, by what each side holds:
  - the same diary (same vault id): they store each other as peers and sync right away;
  - the shower has no diary (a computer on its first screen, "Set up from your phone", which listens
    the same way): once the scanner's user confirms (*Send a copy of your encrypted diary to
    "<name>"?*), it sends this one as an encrypted `.secrt` archive (the backup
    format, staged in a temp file on both sides), and the user types the diary's password or
    recovery key on the computer, which restores it with the backup code path and keeps the phone
    as a peer;
  - different diaries: both refuse, nothing is stored or sent.
- Setting up a phone from another device (a lost phone while the computer still has the diary, or
  a second phone): "Set up from another device" on the phone's first screen opens the scanner. It
  scans the code shown by a device that has the diary, which asks its user (*"<name>" wants a copy
  of your diary. Send it?*) and then sends it the same `.secrt` archive; the user types the diary's
  password or recovery key on the new phone, and both keep each other as peers. Showing the code
  isn't consent enough: someone could photograph it and scan it first. If the shower has no diary
  either, the scanner says so and nothing is sent.
- Later: "Sync now" next to a peer this device scanned. Both apps open and unlocked, the other one
  showing Devices. "Forget" (either side) unpairs: the other side is then rejected.

**Pairing code (QR).** `secrt-pair:` + base64url of `version | port | IPv4 addresses (≤ 4) |
showing device's identity public key (32) | pairing secret (32)`. The secret is 256 random bits,
valid for 5 minutes and for one successful pairing (failed attempts don't burn it: guessing 256 bits
isn't possible, and burning would let anyone on the network cancel pairings). "New code" replaces it.

**Handshake** (SIGMA-I style, all libsodium; `Handshake.kt`). I = scanner, R = shower, `eX` fresh
X25519 keys, `idX` Ed25519 identities, `S` the pairing secret, `H` BLAKE2b-256:
```
I -> R  "SECRTLAN" | version | mode (pair/sync) | eI                       plaintext, 42 bytes
R -> I  eR | frame{ idR | sig_R("…responder" | h1 | idR) [| MAC_S("…pair responder" | h1 | idR)] }
I -> R  frame{ idI | sig_I("…initiator" | h2 | idI) [| MAC_S("…pair initiator" | h2 | idI)] }
h1 = H(first message | eR)          h2 = H(h1 | idR | sig_R)          MAC = keyed BLAKE2b
```
- Session keys: libsodium `crypto_kx` over the ephemeral keys, then each direction's key =
  keyed BLAKE2b(kx key, label | h1), which binds them to the mode and both ephemerals. Ephemeral
  secrets are wiped after the exchange: forward secrecy.
- I accepts R only if `idR` equals the key it expects (from the QR code, or the stored peer)
  and the signature verifies; when pairing, R's MAC proves R saw the same code.
- R accepts I when pairing only if I's MAC verifies with the current, unexpired secret; when syncing,
  only if `idI` is a stored peer. Rejections after authentication send an encrypted reason, so I
  can say "pair again" or "code expired".
- A side without a diary has no database for its identity yet: it uses a provisional identity for
  the handshake and saves it once the received diary opens, so later syncs authenticate.
- Identities travel encrypted, so a passive observer can't tell which devices talk.

**Framing.** `length (4) | XChaCha20-Poly1305(key of that direction, nonce = frame counter)`.
Counters make frames impossible to drop, replay or reorder unnoticed; conversations end with
explicit messages (`done`, `archiveEnd`), so a cut connection is never taken as complete. Frames are
at most 24 MiB (one 20 MB attachment); reads time out (15 s handshake, 2 min per frame).

**After the handshake: `hello`** both ways (I first): name, device id, vault id, and `protocol`, what
that app understands (missing, so 1, from versions before it; they ignore the field). A `null`
vault id means "no diary yet, set me up" (defined that way from the start, so no version bump was
needed):
- I has none, R has one (pair mode only): R answers with its hello, asks its user, and sends the
  archive (`archive`, chunks, `archiveEnd`; I confirms with `received`). Both pair; R learns I's
  device id at the first sync (restoring gives a new one).
- I has one, R has none (R is a computer listening for "Set up from your phone"): the other way round
  (I asks its user after R's hello).
- **Asking the user** (`LanQuestion`; the connecting side passes a callback, the listening side
  publishes `LanHost.question`): the other side waits on its read, which times out after 2 minutes,
  so the question waits 90 s at most and the other side always hears back: `failure(SendDeclined)`
  for "no", `failure(NoAnswer)` on timeout, `failure(Locked)` if the diary locked meanwhile (the
  question goes away with it, and with the screen). Peers of protocol 1 can't read the first two
  and get `failure(Protocol)` instead ("rejected by the other device"). Protocol 2 is what asks;
  3 also agrees on key slots in every sync (below), and syncs need both sides at 3: the side that
  finds the other below sends `failure(Protocol)` and says "update secrt on <name>".
- Neither: R refuses with `failure(NoDiary)`. Same vault id: sync. Different: `failure(DifferentDiary)`.
- A host from before this answers a diary-less hello with `DifferentDiary`; the scanner reports it
  as "update secrt on the other device".

**Sync protocol** (`SyncExchange.kt`), strictly taking turns so neither side blocks on a full TCP
buffer: hellos as above; `keySlots` both ways (each side's `vault.key`, I first), checked against
this diary's data key; the side with the older stamp asks its user (`LanQuestion.AdoptKeySlots`)
before reading on, while the other waits (as for the questions above, the answer comes within
90 s, under the 2-minute read timeout), and a "no" is `failure(KeySlotsDeclined)` in place of its
version vector, so no revision moves either way; version vectors both ways; I sends the
revisions R lacks (`DiaryStore.revisionsSince`, which marks them shared) in batches; R asks for the
attachments those revisions reference that it lacks, receives them as stored (encrypted; verified
with `BlobStore.putEncrypted`), then applies the revisions with `applyRemote`; the same in the other
direction; `done` both ways; then the side that said yes installs the other's `vault.key` (after,
not before: an interrupted sync leaves the password unchanged and asks again next time).
Attachments are stored before the revisions that reference them, so an interrupted sync can't
leave entries pointing at attachments no later sync would send.

**Validation.** Nothing is read before the channel authenticates. After that, every message is still
checked: sizes, key slots (this diary's id and data key), revision shape (id = `deviceId:seq`,
payload parses as its record type, deletion iff no payload), version vectors, attachment ids (64
hex chars, so they can't be paths), and attachments only answer requests for blobs the sent
revisions reference.

**Finding the other device again: remembered address, not mDNS.** The scanner stores the address
that worked (plus the others from the code) and the port; the shower always tries the same port. On
"Sync now" the scanner tries them in order (4 s each). If the shower's IP changed (new network,
DHCP), scanning a new pairing code updates the stored addresses (pairing again keeps the history).
Two phones can each scan the other's code, and then either can start a sync.
Chosen over mDNS because it needs no multicast permissions, works on networks that block multicast,
and has no flaky platform discovery code; mDNS discovery of paired devices is in the backlog.

**Platform pieces.** Sockets: `ktor-network` in common code, server sockets included (the phone
listens too). QR drawing: ZXing (`qrModules`, both platforms). Scanning: CameraX preview + ZXing
decoding of the luminance plane (no Play services). Addresses in a code: site-local IPv4 of the
interfaces that are up; on Android, mobile-data interfaces (`rmnet*`, `ccmni*`, …) are left out.
Android permissions: `INTERNET`, `CAMERA`. Local network access (incoming and outgoing) needs nothing
more while `targetSdk` is 36: Android 16 only enforces it for apps that opt in, and below target 37
`INTERNET` implies it. Targeting 37 (Android 17) will need `ACCESS_LOCAL_NETWORK` (runtime, in the
Nearby devices group) declared and requested before pairing, syncing or showing a code; Google asks
not to request it earlier. Debug builds only: a pairing code can be fed through the intent extra
`io.github.igrvlhlb.secrt.debug.PAIRING_LINK` (`app/android/src/debug`), on the Devices screen or,
before there is a diary, on "Set up from another device"; with the app already open, pass
`--activity-single-top` to `am start` so the intent reaches it. Release builds compile a no-op, so the
hook doesn't exist there.

**Not done yet** (see BACKLOG.md): numeric-code pairing with a PAKE, mDNS discovery, the computer
starting a sync, computer ↔ computer, iOS.

## Languages
English (the default) and Brazilian Portuguese. The app follows the system language: on Android,
the device's or, from Android 13, the per-app one (`res/xml/locales_config.xml`); on the desktop,
the JVM's default locale (from the OS). Any other language falls back to English.

- **Texts** are Compose Multiplatform resources in the `ui` module:
  `ui/src/commonMain/composeResources/values/strings.xml` (English) and one `values-<language>`
  folder per translation (`values-pt-rBR`). Screens use `stringResource(Res.string.…)`; counts use
  `<plurals>` and `pluralStringResource`. `Res` is generated, internal to `ui`.
- **Messages from ViewModels** are `UiText` (`ui/text/UiText.kt`): a resource plus its arguments,
  resolved when shown (`string()` in composition, `resolve()` for snackbars), so they follow a
  language change.
- **Errors from the core modules**: `core/*` has no translations. Its exceptions are typed and carry
  what the message needs as fields (`LanException.Declined(reason, deviceName)`,
  `BackupServerException(problem, path, action, status)`, `BiometricException.Failed(reason)`,
  `GoogleAuthException(reason)`, `GoogleDriveException(problem, …)`, …); their `message` stays in
  English for logs and tests. `Throwable.toUiText()`
  (`ui/text/ErrorMessages.kt`) maps each type to its text. An exception it doesn't know shows its
  own message (often from the system). `StringsTest` checks that each mapped error reads the same
  in English as its message.
- **Dates** are `java.time` patterns kept as strings (`format_date_long`, …), so each language
  orders day, month and year its own way; month and weekday names come from the locale.
- **Outside Compose**: the system fingerprint prompt's texts are Android resources in `app/android`
  (`values/strings.xml`, `values-pt-rBR/strings.xml`), passed to `AndroidBiometricKeyStore`; the
  desktop's "already open" dialog and the browser page after Google sign-in use `DesktopTexts`.
  The name "secrt" is never translated.

To add a language: copy `values/strings.xml` to `values-<language>` (e.g. `values-es`, or
`values-pt-rBR` for a region) in both `ui/src/commonMain/composeResources` and
`app/android/src/main/res`, translate it (date patterns included), and add the language to
`locales_config.xml`. `./gradlew :ui:jvmTest` (`StringsTest`) fails if a text or placeholder is
missing.

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
core/lan            LAN pairing & sync: handshake, encrypted channel, sync protocol (ktor-network sockets)
ui/devices          Devices screen, QR code drawing and scanning (CameraX + ZXing on Android)
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
- To move a diary from the phone: pick "Set up from your phone" on the desktop's first screen and
  scan its code from the phone (Settings → Devices), or export a backup in Settings and pick
  "Restore from a backup file". Afterwards, Settings → Devices on both keeps them in sync.

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
- **Later:** OneDrive/Dropbox/S3, iOS biometric unlock, Markdown import, web client. The web client could run fully in the browser against the WebDAV/Drive backup with client-side crypto, so no hosting of user data is needed. Browsers can't do raw TCP or mDNS, so it wouldn't use LAN sync.

## Verification
- `./gradlew allTests` with `commonTest` for every core module, run on JVM, Android, and iOS simulator:
  - Crypto: known-answer tests; wrong-password and tampered-ciphertext rejection; recovery-key unlock; password change.
  - Sync: property-based tests. Random edits on N replicas, synced in random orders, must converge to identical state with no lost revisions. Specific tests for each merge rule and conflict.
  - Archive: `.secrt` round-trip (export, then import into an empty vault, gives identical state); Markdown export snapshot test.
- Backup: engine tests against an in-memory `BackupTarget`, WebDAV client tests against an
  in-process WebDAV server, and `RealWebDavServerTest`, which runs against a real server when
  `SECRT_WEBDAV_URL`/`_USER`/`_PASSWORD` are set (e.g. `wsgidav` in a venv, or Nextcloud in Docker).
- End-to-end manual:
  1. Android emulator + desktop: pair via QR, write on both offline, sync, and check the merge and conflict UI.
  2. Desktop pushes a backup; a fresh install restores from it with the password, then with the recovery key.
  3. Inspect the DB file and backup files to confirm no plaintext (`strings` / hexdump).
