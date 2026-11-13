# Live Performance Graphs

The instructor dashboard now has a **Live Performance Graph** button.

1. Select a PC row in the instructor dashboard.
2. Click **Live Performance Graph**.
3. View CPU (blue), RAM (green), and Disk (orange) percentages for the most recent 120 received heartbeats.

Data is kept in memory while the server is running; it is not persisted to disk. Graphs begin collecting after the dashboard opens. Disconnected PCs show the last collected readings; a PC with no received heartbeat shows a waiting message.

## Changed files

- `src/labmonitor/server/ServerDashboard.java` — adds button and tracks metrics from PC updates.
- `src/labmonitor/server/PerformanceHistory.java` — new bounded sample storage.
- `src/labmonitor/server/PerformanceGraphDialog.java` — new Swing chart window.

## Build and test

Run `build.bat`, then `demo.bat`. Select a simulated or real PC and open its graph. No third-party chart library is required.

## Note for team repositories

This package was built on the previously supplied Alert History ZIP. If your teammates changed `ServerDashboard.java` on GitHub after that version, review and merge their edits rather than overwriting them. Run `git pull origin main` with a clean working tree before copying these files.
