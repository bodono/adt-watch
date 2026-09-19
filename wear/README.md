# ADT Watch — Wear OS module

v0.21 shows one large alarm button and an **ADT Watch** Tile. Red means ADT last
reported Armed Stay/Away and offers **Disarm**; green means reported Disarmed and
offers **Arm Stay**. Grey disables alarm actions when state is unknown, stale,
unavailable or awaiting a new report. The observation date/time is shown. v0.21
expires cached coloured entries into a grey fallback and bounds progress to
30 seconds before showing an unconfirmed result.

A positive tap sends the displayed action with no second confirmation. A
coloured Tile tap opens the app and continues that same request. The action is
bound to the displayed state revision; stale, replayed or restored Tile launches
cannot choose a new action. Rendering, opening or resuming the app alone does
not execute an alarm command.

A watch PIN or pattern is optional. An existing system lock is respected, and
the app must remain visible and focused while sending. There are no automatic
alarm retries. **Refresh** reads phone status without operating the alarm; only
a newer accepted ADT report or explicit checked-state recovery on the phone can
resolve a pending result. A phone check is labelled **Checked** and expires
after five minutes. App/Tile entry and
completed attempts start a read-only recovery period of up to 30 seconds,
retrying failed or incomplete status checks without repeating the alarm action.
Tile rendering also checks stale or missing status automatically, without
depending on delayed Tile-entry events. The original renderer request awaits
the phone's reply (checked every 100ms), with an 8-second ceiling below the
platform's 10-second deadline. It returns the useful state in the first response.
The v0.19 immediate loading preview was removed after physical traces showed
prompt phone replies followed by delayed renderer refreshes. Intermediate
progress does not request extra redraws; completed recovery publishes one update.
Passive starts have a 60-second cooldown after failure to prevent redraw loops;
Refresh and entry events can still request a new bounded recovery period.
While the app stays visible, it refreshes before the 60-second link cache expires.
Trusted ADT state-change hints can advance a pending status retry, limited to
one new query per 250ms; ordinary failure retries remain two seconds apart.

The paired phone must remain powered, locked and connected with ADT signed in,
one-time helper setup complete, and **ADT's battery usage set to Unrestricted**.
Both actions and their Tile colour updates have worked on a personal setup with
the phone locked. v0.17 overnight use exposed missed status recovery; v0.18
added recovery and v0.20 improves automatic Tile refresh. Overnight reliability
still needs verification. Wear OS schedules refreshes; entry callbacks are
batched on modern watches and are not a guaranteed per-swipe trigger.

The phone and watch APKs share application ID `dev.personal.adtprobe` and must
use the same signer. Build both from the repository root using
`./phone-probe/build.sh --check`. Native view tests use inert callbacks and
generate local previews under `build/reports/watch-previews/` in this module.

See [BUILDING.md](../BUILDING.md), [installation and recovery](../docs/RECOVERY.md)
and [architecture](../docs/ARCHITECTURE.md).
