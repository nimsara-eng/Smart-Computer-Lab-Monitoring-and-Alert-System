# PC Health Dashboard enhancement

The instructor dashboard now shows a **Health** column and a summary of healthy, warning and critical PCs. Row colors match the health level.

- **Healthy**: Connected, reporting, and below warning thresholds.
- **Warning**: CPU, RAM or disk is at least 80% of its configured alert threshold.
- **Critical**: A threshold is reached, an active alert exists, or heartbeat is missing.
- **Waiting**: Connected but the first metrics heartbeat has not arrived.
- **Offline**: Client is disconnected.

Critical thresholds come from `config/server.properties`. Warning thresholds are 80% of those values. This is a display-only enhancement; existing alert rules and network protocols are unchanged.

## Test on Windows

1. Install a JDK and verify `java -version` and `javac -version`.
2. Run `build.bat` then `demo.bat` from the project root.
3. Observe the new **Health** column and summary in the instructor dashboard.
4. Change thresholds in `config/server.properties` and restart to test warnings.

The original Git metadata is intentionally excluded from the distributable ZIP. Copy the changed source file and new files into your existing Git clone, then commit to `main` after testing.
