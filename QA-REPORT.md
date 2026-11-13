# QA results — 13 November 2026

All twelve automated suites (ten standard, plus load and packaged-launcher tests)
passed locally after the fixes below. This is evidence
for the tested flows, not a guarantee that every environment or failure mode works.

Environment: Linux, OpenJDK 27, compilation with `--release 8`, real localhost
TCP/UDP sockets, and Swing windows in an isolated Xvfb display. No external Java
dependencies. Runtime execution on Java 8 itself was not available.

| Suite | Result | Coverage |
|---|---|---|
| `PcInfoHeartbeatTest` | PASS | Duplicate, delayed, negative sequence numbers; packet loss; reconnect counter reset |
| `AlertStabilityTest` | PASS | Independent CPU/memory/disk warning and recovery streaks, hysteresis, reconnect reset |
| `ServerConfigTest` | PASS, 7 checks | Invalid ports/thresholds, defaults, recovery limits, positive timing/streaks, Turkish locale block list |
| `NetworkFlowsTest` | PASS, 41 checks | Required/duplicate registration; direct messages and broadcast; framing; help; GETPROCS; blocked app warning/recovery/deduplication; malformed UDP; telemetry bounds; resource alerts/recovery; timeout/restoration; health states; graceful/abrupt disconnect; reconnect; shutdown with incomplete registration; CSV escaping |
| `AgentFlowsTest` | PASS | Real agent identity/heartbeats, messages, broadcast, help, process response, blocked app/CPU alerts, graceful stop, invalid WELCOME port, silent registration timeout, real Linux system metrics/process list |
| `DisconnectReconnectTest` | PASS | Real agent automatically reconnects and resumes heartbeats after server restart |
| `SwingSmokeTest` | PASS, 12 checks | Dashboard/agent visibility, status, three meters, message/alert logs, dashboard alerts, history loading/search/severity filtering |
| `AlertLoggingTest` | PASS | Latest 1,000 alerts retained; independent history copies; 400 concurrent appends; one CSV header across restart; in-memory alerts preserved and errors reported for invalid destinations and Linux `/dev/full` |
| `GuiInteractionTest` | PASS | Actual selection guard, message/broadcast dialogs and delivery, help button, native mouse focus and Enter-key help submission, fresh process dialog, history refresh, message cancellation |
| `WindowCloseTest` | PASS | Production agent window close sends graceful BYE and exits; instructor window close shuts down the client socket and exits, tested in separate JVMs |
| `LabLoadTest` | PASS | 40 real simulated agents; 400 broadcast deliveries; 40 help requests; 10 simultaneous reconnects; 60-second soak with all agents responsive and at least 50 heartbeats per final session |
| `PackagedLauncherTest` | PASS, Linux app image | Both launchers use bundled runtime with Java absent from PATH; real metrics and system process reporting; custom config from another working directory; direct/broadcast messages; alerts; disconnect; console quit |

The full source/test compilation succeeded. `-Xlint:all` additionally reports
nonfatal Swing serialization and constructor listener-registration warnings;
these were not treated as functional failures. `git diff --check` passed.
Four additional CLI checks passed: invalid server port, invalid agent port,
nonfinite simulated CPU, and blank PC ID all exit with status 2 and usage text.

## Fixes made

- Reject nonfinite and out-of-range UDP percentages rather than converting them
  into apparently valid readings; reject negative uptime. The new network suite
  fails against the original source with `invalid telemetry ignored` and passes
  against the fixed source.
- Normalize the agent's PC ID once so REGISTER and UDP heartbeats use the same
  value, including when an ID contains whitespace or a protocol separator. Reject
  blank IDs.
- Time out agents waiting indefinitely for WELCOME and reject invalid advertised
  UDP ports through the normal reconnect/error path.
- Close all accepted TCP connections during shutdown, including clients that
  have not completed registration. Allow stop before start safely.
- Validate configured port and percentage ranges; reject invalid command-line
  ports and nonfinite/over-100 simulated CPU values with usage errors.
- Normalize block-list names with `Locale.ROOT` so uppercase names match under
  Turkish locale settings.
- Detect `PrintWriter` write failures so full storage produces an error message
  instead of silently losing the CSV write. The full-device regression failed
  before the fix and passed after it; the alert remains available in memory.

## Repeating the checks

Windows: run `test.bat` from the project directory, or `test.bat --load` to include
the optional one-minute load check. The batch runner was reviewed
but cannot itself be executed on this Linux environment.

Linux with JDK 9+ and Xvfb installed:

```bash
mkdir -p out
javac --release 8 -encoding UTF-8 -d out src/labmonitor/*/*.java tests/labmonitor/server/*.java
for suite in PcInfoHeartbeatTest AlertStabilityTest ServerConfigTest AlertLoggingTest NetworkFlowsTest AgentFlowsTest DisconnectReconnectTest; do
    java -cp out labmonitor.server.$suite || exit 1
done
xvfb-run -a java -cp out labmonitor.server.SwingSmokeTest
xvfb-run -a java -cp out labmonitor.server.GuiInteractionTest
xvfb-run -a java -cp out labmonitor.server.WindowCloseTest
# Optional load test:
java -cp out labmonitor.server.LabLoadTest
# Optional: test an existing jpackage app image (directory, not launcher path):
java -cp out labmonitor.server.PackagedLauncherTest /absolute/path/to/LabServer
```

On JDK 8, replace `--release 8` with `-source 8 -target 8`.
Configuration tests deliberately print warnings for the invalid inputs.
On Windows, pass the `dist\LabMonitor` directory to `PackagedLauncherTest` after
running `package.bat`. The Linux full-device check is skipped on Windows.

The Linux image was built with the same entry points, extra launcher and module
list as `package.bat`. It is a Linux build, not a Windows executable validation.
The image tested locally is `/tmp/labmonitor-package-OkZlU4/dist/LabServer`;
rendered GUI evidence is `/tmp/labmonitor-qa/gui-interactions.png`.

## Still requires verification

- Separate physical Windows PCs: firewall rules, LAN disconnects/cable removal,
  shutdown/frozen machines, Wi-Fi loss and real packet loss/reordering.
- Windows `tasklist`, actual Windows resource readings, `.bat` execution,
  Windows `jpackage` output and bundled launchers, default config discovery from
  arbitrary working directories, and operation on a clean Windows PC without Java.
  No Windows runtime, VM, Wine or remote Windows target is available here.
- Full keyboard navigation, assistive technologies, small-screen layouts and
  display scaling. Automated interactions passed in a virtual display; this does
  not certify every desktop environment or accessibility requirement.
- Multi-hour/day load and substantially more than 40 PCs. The completed load
  check is a one-minute local soak, not a prolonged physical lab deployment.
  Log restart/retention behavior and full-device error reporting have now been
  checked; history remains limited to the current session's latest 1,000 alerts.

The existing protocol has no authentication or encryption and assumes a trusted
lab network. This QA pass does not establish suitability for untrusted networks.
