# Smart Computer Lab Monitoring and Alert System

EC5205 Operating Systems & Network Programming - Network Programming Project

A client-server application that lets a lab instructor watch every computer in a lab in real time.
A small **agent** runs on each lab PC and reports to a central **server** on the instructor's machine.
The server shows all PCs on a live dashboard and raises alerts when something is wrong.

Written in plain Java (sockets, datagrams, threads, Swing). No external libraries.

## Features

| Feature | How it works |
|---|---|
| Live status of every PC | Each agent sends CPU, memory and disk usage every 2 s in a UDP heartbeat |
| PC not responding | A watchdog thread raises a CRITICAL alert when heartbeats stop for 8 s |
| PC disconnected | The TCP connection drops (shutdown, cable unplugged, agent killed) |
| High CPU / memory / full disk | Compared with limits in `config/server.properties` |
| Blocked applications | Agents report their running processes; the server checks them against a block list and warns both the instructor and the student |
| Help requests | A student presses "Request help" and the instructor gets an alert |
| Instructor messages | Message one PC, or broadcast to the whole lab |
| Remote process list | Instructor can view the processes running on any PC |
| Packet loss | Heartbeats carry a sequence number; the server estimates missing datagrams per PC and ignores duplicate or out-of-order heartbeats |
| Alert log | Every alert is appended to `logs/alerts.csv` |
| Auto reconnect | Agents retry every 5 s when the server is unreachable |

## Architecture

```
        Lab PC (LabAgent)                          Instructor PC (LabServer)
 +---------------------------+              +--------------------------------------+
 | reader thread    <--TCP-->|--------------| tcp-acceptor thread (ServerSocket)   |
 |   REGISTER, PROCS, HELP   |   port 5000  |   -> one ClientHandler thread per PC |
 |   MSG, ALERT, GETPROCS    |              |                                      |
 | heartbeat thread  --UDP-->|--------------| udp-heartbeat thread (DatagramSocket)|
 |   HB every 2 s            |   port 5001  | watchdog thread (finds silent PCs)   |
 | processes thread          |              | AlertManager -> logs/alerts.csv      |
 | AgentWindow (Swing)       |              | ServerDashboard (Swing)              |
 +---------------------------+              +--------------------------------------+
```

Why two protocols:

- **TCP** for registration, alerts, messages and process lists. These must arrive, and in order.
- **UDP** for heartbeats. They are small and frequent, and a lost one does not need resending
  because the next one replaces it. Lost heartbeats are themselves useful information.

## Protocol

One message per line, fields separated by `|` (see `Protocol.java`).

| Direction | Transport | Message |
|---|---|---|
| agent -> server | TCP | `REGISTER\|pcId\|hostname\|user\|os` |
| server -> agent | TCP | `WELCOME\|udpPort\|heartbeatSec\|processReportSec` or `ERROR\|reason` |
| agent -> server | UDP | `HB\|pcId\|seq\|cpu\|memory\|disk\|uptimeSec` |
| agent -> server | TCP | `PROCS\|name1;name2;...` |
| agent -> server | TCP | `HELP\|text` |
| agent -> server | TCP | `BYE` |
| server -> agent | TCP | `MSG\|from\|text` |
| server -> agent | TCP | `ALERT\|severity\|text` |
| server -> agent | TCP | `GETPROCS` |

## Heartbeat ordering test (single PC)

This test uses plain Java, requires no network connection and checks that duplicate,
out-of-order and negative-sequence UDP heartbeats cannot overwrite newer data.

After `build.bat`, run from the project folder in PowerShell:

```powershell
javac -cp out -d out tests\labmonitor\server\PcInfoHeartbeatTest.java
java -cp out labmonitor.server.PcInfoHeartbeatTest
```

Expected result: `Heartbeat ordering tests passed`.

## Stable resource alerts (single PC)

CPU warnings start after `cpu.consecutive` high readings, whereas memory and disk
warnings start after one high reading. All three warnings recover only after
`alert.recovery.consecutive` readings at or below the lower recovery thresholds.
This hysteresis prevents rapid WARNING / INFO toggling near a limit. Configure
limits in `config/server.properties` (`cpu.recovery.threshold`,
`memory.recovery.threshold`, `disk.recovery.threshold`).

After `build.bat`, run the alert stability regression test:

```powershell
javac -cp out -d out tests\labmonitor\server\AlertStabilityTest.java
java -cp out labmonitor.server.AlertStabilityTest
```

Expected result: `Alert stability tests passed`.

## Disconnect/reconnect integration test (single PC)

This end-to-end test starts a real localhost TCP server and simulated Java agent,
waits for a UDP heartbeat, stops the server, verifies the PC goes OFFLINE,
then restarts the server and checks that the same agent reconnects automatically.
No second computer or external testing library is required.

Close any running demo first and, after `build.bat`, run from the project folder:

```powershell
javac -cp out -d out tests\labmonitor\server\DisconnectReconnectTest.java
java -cp out labmonitor.server.DisconnectReconnectTest
```

Expected result: `Disconnect/reconnect integration test passed`.
The agent retries every 5 seconds, so the test may take a few seconds.
If the test fails, do not commit; share its error output for investigation.

## Requirements

A JDK (version 8 or newer) with `javac` and `java` on the PATH. Scripts are for Windows.

## Running

### Quick demo on one computer

```
demo.bat
```

This builds the project and opens the dashboard with four lab PCs:

| PC | What it shows |
|---|---|
| PC-01 | This real computer: real CPU, memory, disk and process list |
| PC-02 | Simulated PC behaving normally (occasional CPU bursts) |
| PC-03 | Simulated PC with CPU fixed at 96 % -> HIGH_CPU alert after 3 heartbeats |
| PC-04 | Simulated PC running `vlc.exe` -> BLOCKED_APP alert |

Things to try during a demonstration:

1. Close an agent window -> the PC goes OFFLINE (grey) with a DISCONNECTED entry.
2. Kill an agent from Task Manager -> WARNING "Connection lost".
3. Type a problem in an agent window and press **Request help**.
4. Select a PC, press **Message selected PC**, or use **Broadcast to all**.
5. Select PC-01 and press **View processes**.
6. Close the server and start it again -> the agents reconnect by themselves.

### In a real lab

On the instructor's PC:

```
run-server.bat
```

On each lab PC (use the instructor PC's IP address):

```
run-agent.bat --server 192.168.1.10 --id PC-01
```

Windows Firewall on the instructor's PC must allow inbound **TCP 5000** and **UDP 5001**.

### On PCs without Java (packaged version)

```
package.bat
```

This uses the JDK's `jpackage` tool (JDK 14 or newer) to build `dist\LabMonitor` and
`dist\LabMonitor.zip`. The folder contains `LabServer.exe`, `LabAgent.exe` and its own Java
runtime, so it runs on any 64-bit Windows 10/11 PC with nothing installed:

1. Copy the `LabMonitor` folder (or unzip `LabMonitor.zip`) on each PC.
2. Instructor PC: double-click `LabServer.exe`.
3. Lab PCs: double-click `LabAgent.exe`, then type the instructor PC's IP address and a
   unique name for the PC.

`HOW-TO-RUN.txt` inside the folder repeats these steps. Run `package.bat` again after
changing the code.

### Options

Server: `--nogui` (text mode), `--tcp <port>`, `--udp <port>`, `--config <file>`

Agent (asks for the server and PC name in a dialog when `--server` is not given):
`--server <host>`, `--port <tcp port>`, `--id <name>`, `--user <name>`, `--nogui`,
`--simulate` (generated values), `--sim-cpu <percent>` (fixed CPU value),
`--fake-proc <name.exe>` (adds a process name to the report, for demonstrating the block list)

Text mode server commands: `list`, `alerts`, `msg <pcId> <text>`, `all <text>`, `procs <pcId>`, `quit`

## Source files

```
src/labmonitor/
  common/Protocol.java          message names and helpers shared by both sides
  server/LabServer.java         main class: sockets, threads, alert rules
  server/ClientHandler.java     one thread per connected PC (TCP)
  server/HeartbeatListener.java receives all UDP heartbeats
  server/Watchdog.java          detects PCs that stopped sending heartbeats
  server/PcInfo.java            thread-safe state of one PC
  server/Alert.java             one alert
  server/AlertManager.java      alert history and CSV log
  server/ServerConfig.java      reads config/server.properties
  server/ServerDashboard.java   Swing dashboard
  server/ServerConsole.java     text mode interface
  server/ServerListener.java    events from the server to the user interface
  client/LabAgent.java          main class: connection, heartbeat and process threads
  client/SystemMetrics.java     reads CPU, memory, disk and process list (or simulates them)
  client/AgentWindow.java       Swing window on the lab PC
  client/AgentListener.java     events from the agent to the user interface
```

## Course concepts used

- **TCP socket programming**: `ServerSocket`, `Socket`, `BufferedReader`, `PrintWriter`
- **UDP socket programming**: `DatagramSocket`, `DatagramPacket`
- **Threads**: extending `Thread` (`ClientHandler`) and implementing `Runnable`
  (`HeartbeatListener`, `Watchdog`); one thread per client so many PCs are served concurrently
- **Synchronization**: `PcInfo` and `AlertManager` are shared between threads and use
  `synchronized`; the PC table is a `ConcurrentHashMap`
- **Application-layer protocol design**: a custom text protocol on top of TCP and UDP

## Limitations

- Messages are plain text with no encryption or authentication. Heartbeats are only accepted
  from the IP address the PC registered with, but the system is meant for a trusted lab network.
- A student can close the agent window; the instructor sees this as a DISCONNECTED entry.
- The process list uses `tasklist` on Windows and `ps` on Linux/macOS.
- "Uptime" is the time since the agent started, not since the PC booted.
