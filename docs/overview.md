# Overview

## The idea in one paragraph

Every device holds a **complete, encrypted copy** of the diary and works fully offline. No device
is "the main one". Every change is stored as an immutable **revision**, and the screen shows what
those revisions add up to. Syncing two copies (over Wi-Fi, through a cloud backup, or by importing a
file) just means giving each side the revisions it lacks. Each device then works out the same
result on its own, merging concurrent edits with the same deterministic rules everywhere. Because
of that, every transport is "dumb": a WebDAV server stores opaque encrypted files and never needs
to understand them.

```mermaid
flowchart LR
    phone["📱 Phone<br/>full encrypted copy"]
    desktop["💻 Computer<br/>full encrypted copy"]
    phone2["📱 Second phone<br/>full encrypted copy"]
    cloud[("☁️ WebDAV backup<br/>encrypted files only")]
    file[/"📄 .secrt export file"/]

    phone <-- "LAN sync<br/>(paired, encrypted)" --> desktop
    phone <-- "LAN sync" --> phone2
    phone <-- "push / pull" --> cloud
    desktop <-- "push / pull" --> cloud
    phone -- "export" --> file
    file -- "restore on a new device" --> desktop
```

## Modules

Kotlin Multiplatform. Everything under `core/` is common code (Android, desktop JVM, later iOS)
except a few small platform pieces: the SQLCipher driver, network addresses, biometrics and HTTP
engines.

```mermaid
flowchart BT
    model["core:model<br/><small>revisions, payloads, HLC clock, ids</small>"]
    crypto["core:crypto<br/><small>libsodium wrapper: Argon2id, XChaCha20-Poly1305,<br/>BLAKE2b, Ed25519, X25519</small>"]
    vault["core:vault<br/><small>vault.key slots, password / recovery key,<br/>key derivation</small>"]
    sync["core:sync<br/><small>merge engine: revision graph,<br/>three-way merges, folder tree</small>"]
    storage["core:storage<br/><small>SQLCipher database (SQLDelight),<br/>encrypted attachments, DiaryStore</small>"]
    archive["core:archive<br/><small>.secrt file format,<br/>encrypted segments</small>"]
    session["core:session<br/><small>AppSession: create / unlock / lock,<br/>export / restore, biometrics, settings</small>"]
    backup["core:backup<br/><small>cloud backup engine,<br/>WebDAV target</small>"]
    lan["core:lan<br/><small>QR pairing, encrypted channel,<br/>LAN sync protocol</small>"]
    ui["ui<br/><small>Compose Multiplatform screens</small>"]
    android["app:android"]
    desktop["app:desktop"]

    vault --> crypto & model
    sync --> model
    storage --> sync & vault
    archive --> vault
    session --> archive & storage
    backup --> session
    lan --> session
    ui --> backup & lan & session
    android --> ui
    desktop --> ui
```

Arrows point at dependencies. Some rules that keep this tidy:

- **`core:sync` is pure logic.** It has no I/O and no clock, so the merge rules are tested with
  in-memory revisions.
- **`DiaryStore` (`core:storage`) is the only writer.** It turns user actions into revisions and
  re-derives the visible tables from them. Other modules read revisions from it and hand
  received ones back through `applyRemote`.
- **Every transport goes through the same two calls:** `revisionsSince(versionVector)` to send and
  `applyRemote(revisions)` to receive. LAN sync, cloud backup and `.secrt` import differ only in
  how the bytes travel.
- **`AppSession` (`core:session`) owns the keys.** It unlocks the vault, opens the database, and
  wipes keys from memory on lock. Transports borrow the open diary through it and never see the
  password.

## What a session looks like

```mermaid
stateDiagram-v2
    [*] --> NoVault: first run (no vault.key)
    [*] --> Locked: vault.key exists
    NoVault --> Unlocked: create diary
    NoVault --> Unlocked: restore from .secrt file / cloud backup / another device
    Locked --> Unlocked: password, recovery key or fingerprint
    Unlocked --> Locked: lock button, auto-lock timeout,<br/>app restart
    Unlocked --> Unlocked: changes, sync, backup
```

On unlock, `AppSession`:
1. reads `vault.key` and uses the password (or recovery key, or the biometric-wrapped key) to get
   the **data key**;
2. derives the database, attachment and archive keys from it (see [security.md](security.md));
3. opens the SQLCipher database and runs any pending schema migrations;
4. hands the UI a `DiaryStore`.

On lock it closes the database and overwrites every key in memory with zeros. The auto-lock
timeout is set in Settings.

## What is stored where

On Android, files are in the app's private storage (`files/vault/…`, plus the database in
`databases/`). Auto-backup is off. On the desktop, everything is in one data directory:
`~/Library/Application Support/secrt` on macOS, or wherever `SECRT_DATA_DIR` or
`-Psecrt.dataDir` points.

| File | Contents | Encrypted? |
|---|---|---|
| `vault/vault.key` | Diary id, key slots (the data key wrapped by the password and by the recovery key), Argon2 parameters, key-check value | The data key inside is wrapped; the rest is plain and holds no secrets |
| `secrt.db` | SQLCipher database: revisions, derived entry/folder/label tables, paired devices, this device's identity, backup settings and credentials | Whole file (SQLCipher, AES-256) |
| `vault/blobs/<id>` | Attachments, one file each | XChaCha20-Poly1305 |
| `vault/settings.json` | Auto-lock preferences, readable while locked | Plain, no diary content |
| `files/biometric.key` (Android) | The data key wrapped by a hardware-backed Keystore key that needs a fingerprint | Yes (Keystore AES-GCM) |

## Where to look in the code

| You want to understand… | Start at |
|---|---|
| How a save becomes a revision | `DiaryStore.saveEntry` → `commit` (`core/storage`) |
| How revisions become what you see | `DiaryStore.materialize` → `RecordResolver.resolve` (`core/sync`) |
| Merge rules | `PayloadMergers.kt`, `TextMerge.kt`, `FolderTree.kt` (`core/sync`) |
| Unlocking and keys | `VaultManager`, `VaultKeys` (`core/vault`), `AppSession` (`core/session`) |
| Export / restore | `AppSession.exportTo` / `restore`, `SecrtArchive` (`core/archive`) |
| Cloud backup | `CloudBackup` (`core/backup`) |
| LAN pairing and sync | `LanSync`, `LanHost`, `Handshake`, `SyncExchange` (`core/lan`) |
| App entry points | `SecrtApp.kt` (`ui`), `SecrtApplication` (Android), `Main.kt` (desktop) |
