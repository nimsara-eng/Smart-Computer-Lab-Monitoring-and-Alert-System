# Alert History Viewer

The Instructor Dashboard now has a **View Alert History** button.

- Displays alerts from the **current running server session**, newest first.
- Search across timestamp, severity, PC ID, type, and message (case-insensitive).
- Filter by INFO, WARNING, or CRITICAL.
- Sort by clicking a table header.
- Click **Refresh** to load alerts raised since the viewer was opened.

**Scope:** This viewer reads the server's in-memory history (up to 1000 alerts). It does not reload previous sessions from `logs/alerts.csv`. The existing CSV logging is unchanged.

## Installation into an existing Git checkout

Copy these files into your project, preserving their paths:

- `src/labmonitor/server/AlertHistoryDialog.java` — **NEW**
- `src/labmonitor/server/ServerDashboard.java` — **REPLACE** (based on the PC Health updated version)
- `FEATURE-ALERT-HISTORY.md` — **NEW**, in project root

Do not replace `.git` or other project files. Build with `build.bat` and launch with `demo.bat`.
