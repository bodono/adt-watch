# How ADT Watch works

The phone and Wear modules share the package ID `dev.personal.adtprobe` and a
signing identity. Google Play Services carries bounded messages between them.
ADT credentials stay in the native ADT app; the helper has no direct ADT service
client.

| Part | Responsibility |
| --- | --- |
| `phone-probe` | Native widget setup, explicit companion/routine approval, brief request service and ADT notification parsing |
| `wear` | One alarm button, Tile, local state cache and user-initiated request flow |
| `shared` | Action enum, strict message formats and request/state validation |

## Setup and execution

The owner configures separate ADT widgets for `WATCH ARM STAY` and `WATCH DISARM`
through Android's widget consent and ADT's scene picker. Preview interaction is
blocked by default. Routine enablement records the reviewed widget revisions,
one native companion association and one explicitly identified connected Wear
node. The scene name does not prove its contents; the owner reviews those in ADT.

A positive watch tap selects the action associated with the exact displayed
state revision. A Tile supplies a short-lived, one-use launch token for that same
action; rendering, plain launches and restored launch history do not authorize
commands. The phone validates routine access, source, action, reported state and
lock state before a readiness request can start its bounded 30-second service.

The phone prepares the matching widget and returns a challenge. The watch's
still-valid tap authorizes one matching commit automatically. This protocol
exchange is not a second user confirmation. Both sides reject mismatched,
expired or replayed messages. Loss of watch focus, pause or lock cancels an
uncommitted attempt. A commit cannot cold-start the phone service, and process
death does not resume a command. No automatic alarm retry is implemented.

The service invokes the native widget's reviewed view listener at most once.
That means a request was handed to ADT, not that the panel changed state. ADT
performs its own authenticated work and may queue it. Its app battery setting
must allow background execution. The helper cannot recall a request handed off
to ADT.

## State and colour

User-enabled Android notification access feeds `PhoneAlarmState`. The parser
accepts exact, internally consistent English alarm reports from the supported
UK ADT package/version. It retains normalized state, event timing, a hashed
alarm scope, revisions and pending bookkeeping. Raw notification bodies and
account/home labels are not stored or sent to the watch.

The watch queries for a source-matched phone report and shows its age. Red is
reported Armed Stay/Away; green is reported Disarmed. Unknown, conflicting,
unsupported, disconnected, stale or pending state becomes grey. Reports older
than 24 hours and watch caches without recent phone contact cannot authorize a
coloured control.

Before native execution, the phone consumes the displayed state revision and
records a pending result. A newer ADT state report is required to clear it.
Neither a successful message send nor a widget invocation invents the opposite
alarm state. Refresh asks for the phone's accepted report without an alarm action.

## Verification boundaries

Pure and Robolectric tests cover protocols, inert widgets, lifecycle handling,
setup revocation, actual native view input and round-screen layout. They never
contact ADT or a physical device. One personal locked-phone Tile cycle has been
verified for both actions with ADT battery usage Unrestricted. Wider
compatibility and natural overnight idle are not established.
