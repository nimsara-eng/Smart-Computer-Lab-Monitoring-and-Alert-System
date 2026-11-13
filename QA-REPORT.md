# QA results — 13 November 2026

All seven automated suites passed locally after the fixes below. This is evidence
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

## Repeating the checks

Windows: run `test.bat` from the project directory. The batch runner was reviewed
but cannot itself be executed on this Linux environment.

Linux with JDK 9+ and Xvfb installed:

```bash
mkdir -p out
javac --release 8 -encoding UTF-8 -d out src/labmonitor/*/*.java tests/labmonitor/server/*.java
for suite in PcInfoHeartbeatTest AlertStabilityTest ServerConfigTest NetworkFlowsTest AgentFlowsTest DisconnectReconnectTest; do
    java -cp out labmonitor.server.$suite || exit 1
done
xvfb-run -a java -cp out labmonitor.server.SwingSmokeTest
```

On JDK 8, replace `--release 8` with `-source 8 -target 8`.
Configuration tests deliberately print warnings for the invalid inputs.

## Still requires verification

- Separate physical Windows PCs: firewall rules, LAN disconnects/cable removal,
  shutdown/frozen machines, Wi-Fi loss and real packet loss/reordering.
- Windows `tasklist`, actual Windows resource readings, `.bat` execution,
  `jpackage` output, bundled launchers, working directory/config resolution, and
  operation on a clean Windows PC without Java.
- Manual GUI interaction: modal messaging/broadcast/process dialogs, keyboard
  navigation, accessibility, screen sizes, display scaling and window-close flows.
  The automated UI suite checks component updates and filters rather than every
  button interaction.
- Long-duration/many-PC load, full/unwritable disks and log retention across
  restarts. History is limited to the current session's latest 1,000 alerts.

The existing protocol has no authentication or encryption and assumes a trusted
lab network. This QA pass does not establish suitability for untrusted networks.
