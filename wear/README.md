# ADT Watch — Wear OS module

v0.18 shows one large alarm button and an **ADT Watch** Tile. Red means ADT last
reported Armed Stay/Away and offers **Disarm**; green means reported Disarmed and
offers **Arm Stay**. Grey disables alarm actions when state is unknown, stale,
unavailable or awaiting a new report. State age is shown.

A positive tap sends the displayed action with no second confirmation. A
coloured Tile tap opens the app and continues that same request. The action is
bound to the displayed state revision; stale, replayed or restored Tile launches
cannot choose a new action. Rendering, opening or resuming the app alone does
not execute an alarm command.

A watch PIN or pattern is optional. An existing system lock is respected, and
the app must remain visible and focused while sending. There are no automatic
alarm retries. **Refresh** reads phone status without operating the alarm; only
a newer accepted ADT report can resolve a pending result. App/Tile entry and
completed attempts start a read-only recovery period of up to 30 seconds,
retrying failed or incomplete status checks without repeating the alarm action.

The paired phone must remain powered, locked and connected with ADT signed in,
one-time helper setup complete, and **ADT's battery usage set to Unrestricted**.
Both actions and their Tile colour updates have worked on a personal setup with
the phone locked. v0.17 overnight use exposed missed status recovery; v0.18
addresses that code path, but overnight reliability still needs verification.

The phone and watch APKs share application ID `dev.personal.adtprobe` and must
use the same signer. Build both from the repository root using
`./phone-probe/build.sh --check`. Native view tests use inert callbacks and
generate local previews under `build/reports/watch-previews/` in this module.

See [BUILDING.md](../BUILDING.md), [installation and recovery](../docs/RECOVERY.md)
and [architecture](../docs/ARCHITECTURE.md).
