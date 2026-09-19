# ADT Watch — Wear OS module

v0.25 uses fresh ADT website observations supplied by the phone. Red offers
**Disarm** for Armed Stay/Away; green offers **Arm Stay** for Disarmed. Grey
disables alarm actions when status is unknown, stale, unavailable or busy. An
observation's age measures time since its query, not the alarm's last change.
Live-query integration is undergoing validation.

A positive tap sends the displayed action without a second confirmation. A
coloured **ADT Watch** Tile tap opens the app and continues the same request.
The action is fixed and bound to the displayed state revision. Expired,
replayed or restored Tile launches cannot choose a new action. Rendering,
opening or resuming the app alone never executes an alarm command.

A watch PIN or pattern is optional. Existing locks are respected, and the app
must remain visible and focused while sending. The paired phone must remain
powered, locked and connected, with one-time helper setup complete. Both the
native ADT app and the helper's separate website session must be signed in.
Set **ADT's app battery usage to Unrestricted**.

App/Tile loading and **Refresh** ask the phone for a read-only ADT status check.
Notifications are refresh hints, not state authority. The phone also queries
before executing a request and skips the native click if the selected action's
target is already satisfied. Neither device automatically retries an alarm
command. After 30 seconds without confirmation, progress ends; fresh steady
ADT status remains usable alongside the unconfirmed outcome.

Status checks can retry within a bounded 30-second recovery period. An uncertain
request gets one final status-only query at that boundary, with at most 10 seconds
to reply and no further automatic attempts in that recovery period. The Tile's
original request awaits a result for at most eight seconds and returns useful
status directly. Passive cooldowns prevent redraw loops. Visible app status is
refreshed before its 60-second cache expires. Wear OS schedules Tile work, so
every swipe is not guaranteed an immediate fetch. Expired visual entries cannot
bypass independent action checks or the phone's live preflight.

The phone and watch APKs share application ID `dev.personal.adtprobe` and must
use the same signer. Build both from the repository root with
`./phone-probe/build.sh --check`. Native view tests use inert callbacks and
generate local previews under `build/reports/watch-previews/` in this module.
Earlier native-action checks do not establish v0.25 live-query reliability or
session longevity; physical and overnight verification remain necessary.

See [BUILDING.md](../BUILDING.md), [installation and recovery](../docs/RECOVERY.md)
and [architecture](../docs/ARCHITECTURE.md).
