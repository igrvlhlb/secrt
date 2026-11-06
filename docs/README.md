# secrt documentation

secrt is a private diary that keeps everything encrypted on the user's own devices. It needs no
server: devices sync directly over the local network, and backups go to a file or to storage the
user already owns (WebDAV today).

Read in this order:

| Document | What it explains |
|---|---|
| [overview.md](overview.md) | The big picture: modules, how an app session works, what lives where on disk. |
| [history-and-sync.md](history-and-sync.md) | How every change is recorded, how devices exchange changes, and how concurrent edits are merged (with and without conflicts). |
| [security.md](security.md) | Keys, passwords, the recovery key, biometric unlock, what is encrypted where, and the threat model. |
| [architecture.md](architecture.md) | The design reference: the decisions taken, file and wire formats (`.secrt`, cloud layout, LAN protocol), schema migrations, roadmap. |

Pending work is tracked in [../BACKLOG.md](../BACKLOG.md).

Diagrams are [Mermaid](https://mermaid.js.org/): GitHub, GitLab, IntelliJ and VS Code (with a
Mermaid extension) render them in place.
