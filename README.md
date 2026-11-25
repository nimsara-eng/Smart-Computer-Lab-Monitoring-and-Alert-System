# Smart Computer Lab Monitoring and Alert System

EC5205 Operating Systems & Network Programming - Network Programming Project

The instructor runs a **server** with a Swing dashboard. Every lab PC runs a small **agent** that
registers over TCP and then sends its CPU, RAM and disk usage over UDP every 2 seconds.
The dashboard shows each PC as ONLINE, NOT RESPONDING or OFFLINE, raises alerts for high usage,
shows students' help requests and lets the instructor message one PC or the whole lab.

Plain Java only: sockets, datagrams, threads and Swing. No libraries, no Maven/Gradle.

## Files

```
src/labmonitor/
  LabServer.java      server: TCP acceptor, UDP receiver, timeout monitor, dashboard
  ClientHandler.java  one thread per connected PC (extends Thread) + that PC's data
  LabAgent.java       student agent: TCP connection, UDP heartbeats (implements Runnable), window
  SystemMetrics.java  real CPU / RAM / disk usage, or simulated values for demos
build.bat  build.sh   compile into out/
demo.bat   demo.sh    server + one real agent + one simulated high-CPU agent on one PC
run-server.bat        start the server
run-agent.bat         start an agent
package.bat           optional: build dist\LabMonitor with LabServer.exe and LabAgent.exe
VIVA_GUIDE.md         demo plan, code locations and viva questions
```

## Features

| Feature | How |
|---|---|
| Register a PC | Agent opens a TCP connection to port 5000 and sends `REGISTER` |
| Live usage | Agent sends a UDP heartbeat to port 5001 every 2 s with CPU, RAM and disk % |
| Many PCs at once | The server starts one `ClientHandler` thread per PC |
| NOT RESPONDING | No heartbeat for 8 s; goes back to ONLINE when heartbeats return |
| OFFLINE | TCP connection closed (`BYE`, agent killed, network lost) |
| High usage alert | CPU, RAM or disk at 90 % or more; one alert each time a PC becomes busy |
| Help request | Student types a question and presses "Ask for help" |
| Instructor messages | "Message selected PC" or "Broadcast to all" |
| Simulated agent | `--simulate` sends made-up data with 92-98 % CPU; labelled SIMULATED everywhere |

## Protocol

One line per message, fields separated by `|`.

| Direction | Transport | Message |
|---|---|---|
| agent -> server | TCP | `REGISTER\|PC-01\|REAL` or `REGISTER\|PC-01\|SIMULATED` |
| server -> agent | TCP | `WELCOME` or `ERROR\|reason` (for example the PC ID is already online) |
| agent -> server | UDP | `HB\|PC-01\|45.0\|65.0\|70.0` (CPU, RAM, disk %) |
| agent -> server | TCP | `HELP\|text` |
| agent -> server | TCP | `BYE` |
| server -> agent | TCP | `MSG\|text` |

## Requirements

A JDK with `javac` and `java` on the PATH. The build makes Java 8 class files, so it runs on
Java 8, 17, 21 or newer.

## Build and run

Windows:

```
build.bat
demo.bat
```

Linux / macOS:

```
sh build.sh
sh demo.sh
```

`demo` opens the dashboard, **PC-01** (this computer, real measurements) and
**PC-02** (SIMULATED, high CPU, so a high-usage alert appears).

### Using real lab PCs

1. On the instructor PC run `run-server.bat` (or `java -cp out labmonitor.LabServer`).
   The dashboard shows this PC's IP address at the top. You can also find it with `ipconfig`.
2. Copy the project (or just the `out` folder) to each lab PC and run:

   ```
   run-agent.bat 192.168.1.10 PC-05
   ```

   or `java -cp out labmonitor.LabAgent 192.168.1.10 PC-05`.
   With no arguments the agent asks for the IP and PC ID in dialogs.
   Add `--simulate` as a third argument for fake high-CPU data.
3. Every PC needs a different PC ID. All PCs must be on the same network, and the instructor PC's
   firewall must allow TCP 5000 and UDP 5001 (Command Prompt as administrator):

   ```
   netsh advfirewall firewall add rule name="LabMonitor TCP" dir=in action=allow protocol=TCP localport=5000
   netsh advfirewall firewall add rule name="LabMonitor UDP" dir=in action=allow protocol=UDP localport=5001
   ```

### Optional: .exe files that need no Java

`package.bat` (needs JDK 14+ for `jpackage`) creates `dist\LabMonitor` with `LabServer.exe`,
`LabAgent.exe` and a Java runtime inside. Copy that folder to any 64-bit Windows PC.

## Limitations

- Plain text over the network: no encryption or password. Meant for a trusted lab network.
- The agent does not reconnect by itself. If the server restarts, start the agents again.
- Data is not saved: closing the server clears the table and alerts.
- The high-usage limit (90 %) and ports (5000/5001) are constants in `LabServer.java`.
- Tested on one Windows PC with several agents (localhost). It uses normal IP addresses, so it
  works the same over a LAN if the firewall allows the two ports.
