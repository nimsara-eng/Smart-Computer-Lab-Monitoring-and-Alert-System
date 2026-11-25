# Viva Guide - Smart Computer Lab Monitoring and Alert System

## 1. What the project is (say this first, about 30 seconds)

"Our system lets a lab instructor watch every computer in a lab from one screen.
Each lab PC runs an agent. It registers with the instructor's server over **TCP** and then sends its
CPU, RAM and disk usage every 2 seconds over **UDP**. The server uses **one thread per PC**, plus
separate threads for UDP and for detecting PCs that stop responding. The instructor sees every PC
as ONLINE, NOT RESPONDING or OFFLINE, gets alerts for high usage and help requests, and can send
messages to one PC or to all PCs."

## 2. Module concepts covered

| Concept | Where |
|---|---|
| TCP server socket | `LabServer.java` 45 `new ServerSocket(TCP_PORT)`, 64 `accept()` |
| TCP client socket | `LabAgent.java` 71 `new Socket(serverIp, TCP_PORT)` |
| Text streams over TCP | `ClientHandler.java` 34-35 (`BufferedReader` / `PrintWriter`), `LabAgent.java` 72-76 |
| UDP send | `LabAgent.java` 113 `new DatagramSocket()`, 121 `udp.send(...)` |
| UDP receive | `LabServer.java` 46 `new DatagramSocket(UDP_PORT)`, 78 `udpSocket.receive(packet)` |
| `extends Thread` | `ClientHandler.java` 11, its `run()` at 39, started at `LabServer.java` 65 |
| `implements Runnable` | `LabAgent.java` 19, its `run()` at 112, given to `new Thread(agent, ...)` at 64 |
| Runnable as a lambda | `LabServer.java` 49-51, `LabAgent.java` 63 |
| `start()` | `LabServer.java` 54-56 and 65, `LabAgent.java` 65-66 |
| `Thread.sleep()` | `LabServer.java` 126 (1 s), `LabAgent.java` 124 (2 s) |
| Daemon thread | `LabServer.java` 52 `timeoutThread.setDaemon(true)` |
| `synchronized` block | `LabServer.java` 137, 159, 166 (`synchronized (clients)`), `ClientHandler.java` 71 |
| `synchronized` methods | `ClientHandler.java` 87-138, `LabAgent.java` 180 |
| `volatile` | `LabAgent.java` 30 `running` |
| Swing EDT | every `SwingUtilities.invokeLater`: `LabServer.java` 34, 244, 261; `LabAgent.java` 60, 94, 104, 122, 186 |
| Heartbeat + timeout | `LabAgent.java` 112-128 (sender), `LabServer.java` 117-130 (checker), `ClientHandler.java` 103 |
| Real system metrics | `SystemMetrics.java` 26 (CPU), 34 (RAM), 43 (disk) |

Line numbers are for the current files. If you change the code, check them again
(VS Code: Ctrl+G and type the line number).

## 3. Files

| File | Lines | What it does |
|---|---|---|
| `LabServer.java` | 271 | `main`, starts the 3 server threads, handles heartbeats, timeouts, the dashboard, messages |
| `ClientHandler.java` | 155 | one thread per PC: reads `REGISTER`, `HELP`, `BYE`; stores that PC's latest data |
| `LabAgent.java` | 200 | connects, registers, TCP receiver thread, UDP heartbeat thread, student window |
| `SystemMetrics.java` | 53 | real CPU / RAM / disk %, or simulated values |

### Important methods

| Method | File:line | Thread that runs it |
|---|---|---|
| `startNetworkThreads()` | LabServer 44 | main |
| `acceptClients()` | LabServer 61 | tcp-acceptor |
| `receiveHeartbeats()` | LabServer 73 | udp-receiver |
| `handleHeartbeat()` | LabServer 88 | udp-receiver |
| `checkTimeouts()` | LabServer 117 | timeout-monitor (daemon) |
| `registerClient()` | LabServer 136 | a ClientHandler thread |
| `createWindow()` | LabServer 173 | EDT |
| `sendToSelectedPc()` / `broadcast()` | LabServer 205 / 226 | EDT (button click) |
| `updateRow()` / `addAlert()` | LabServer 242 / 258 | any thread; the Swing change is passed to the EDT |
| `ClientHandler.run()` | ClientHandler 39 | client-&lt;ip&gt; |
| `register()` | ClientHandler 65 | client-&lt;ip&gt; |
| `updateUsage()` | ClientHandler 92 | udp-receiver |
| `checkTimeout()` | ClientHandler 103 | timeout-monitor |
| `LabAgent.main()` | LabAgent 43 | main |
| `connect()` | LabAgent 70 | main |
| `receiveMessages()` | LabAgent 86 | tcp-receiver |
| `LabAgent.run()` | LabAgent 112 | udp-heartbeat |
| `shutdown()` | LabAgent 190 | EDT (window closed) |

## 4. Threads in the running system

**Server process**

| Thread | Created at | Job | Usually in state |
|---|---|---|---|
| main | JVM | starts the others, then ends | TERMINATED |
| AWT-EventQueue (EDT) | Swing | draws the dashboard, handles buttons | WAITING for events |
| tcp-acceptor | LabServer 49 | `accept()` new PCs | RUNNABLE (blocked in native accept) |
| udp-receiver | LabServer 50 | `receive()` heartbeats | RUNNABLE (blocked in native receive) |
| timeout-monitor | LabServer 51, daemon | every 1 s finds silent PCs | TIMED_WAITING (`sleep`) |
| client-&lt;ip&gt; (one per PC) | LabServer 65 | `readLine()` from that PC | RUNNABLE (blocked in socket read) |

**Agent process**: main (connects, starts threads, ends), EDT (window), tcp-receiver (waits for `MSG`),
udp-heartbeat (sends every 2 s, mostly TIMED_WAITING).

## 5. Four-minute demo plan

| Time | Do | Say |
|---|---|---|
| 0:00 | Run `demo.bat` | "This builds the project and starts the server, a real agent PC-01 and a simulated agent PC-02." |
| 0:30 | Point at the table | "Both PCs registered over TCP. The numbers change every 2 seconds: those are UDP heartbeats. PC-01 is this laptop's real usage. PC-02 says SIMULATED: it sends fake high CPU." |
| 1:00 | Point at the alerts area | "PC-02 crossed 90 % CPU, so the server raised one WARNING. It does not repeat every heartbeat; it alerts again only after the PC goes back to normal and becomes busy again." (If PC-01's RAM is above 90 %, a real alert for PC-01 also appears. That is correct behaviour.) |
| 1:30 | In PC-01's window type "Need help" and press **Ask for help** | "The agent sent `HELP` over its TCP connection; the ClientHandler thread for PC-01 received it." |
| 2:00 | Select PC-01, press **Message selected PC**, type a message | "The server writes `MSG` to that PC's socket; the agent's TCP receiver thread shows it." Then **Broadcast to all**: "Same message to every online PC." |
| 2:45 | Close PC-02's window | "The agent sends `BYE`; the row becomes OFFLINE immediately." |
| 3:15 | Optional: run `run-agent.bat localhost PC-03`, then open Resource Monitor (`resmon`), CPU tab, right-click its `java.exe` (if there are two, the one using more memory) > **Suspend Process**; after 10 s right-click > **Resume Process** | "When heartbeats stop for 8 seconds the timeout thread marks it NOT RESPONDING. When they come back it returns to ONLINE." |
| 3:45 | Summary | "TCP for things that must arrive, UDP for frequent status, one thread per PC, synchronized shared data, and Swing updates on the EDT." |

Before the viva, run `build.bat` and check that ports 5000 and 5001 are free (only one server at a time).

## 6. Explanations to know

**TCP vs UDP.** TCP is connection-oriented: a connection is set up first, data arrives complete and
in order, lost segments are resent. UDP sends independent datagrams: no connection, no resending,
no ordering, but very little overhead.

**Why both?** Registration, help requests, instructor messages and BYE must not be lost, so they use
TCP. Heartbeats are small and sent every 2 s; if one is lost the next one replaces it, so UDP is
enough and cheaper. Also, if *several* heartbeats are missing, that itself tells us the PC is not
responding.

**Socket.** An endpoint of communication: IP address + port. `ServerSocket` waits for connections;
`accept()` returns a new `Socket` for each client. `DatagramSocket` sends and receives
`DatagramPacket`s, each carrying its own destination address.

**Process vs thread.** A process is a running program with its own memory (the server and each
agent are separate processes, even on different PCs). Threads run inside one process and share its
memory, which is why shared data needs synchronization.

**Why threads?** `accept()`, `readLine()` and `receive()` block. With one thread, the server would
wait on one PC and ignore the others, and the window would freeze. With one thread per blocking
task, every PC is served at the same time and the GUI stays responsive.

**Concurrency vs parallelism.** Concurrency: many tasks in progress at the same time (our threads
take turns). Parallelism: tasks actually running at the same instant on different CPU cores. Our
threads are concurrent; on a multi-core PC the OS may also run them in parallel.

**Thread states.** NEW (created, not started) -> RUNNABLE (after `start()`) -> BLOCKED (waiting for a
`synchronized` lock) / WAITING / TIMED_WAITING (`sleep(1000)`) -> TERMINATED (when `run()` returns,
for example a ClientHandler after its PC disconnects).

**`start()` vs `run()`.** `start()` creates a new thread that calls `run()`. Calling `run()` directly
would just run it in the current thread.

**Synchronization.** Several threads touch the same data: the UDP thread writes a PC's usage, the
timeout thread reads `lastHeartbeat`, the GUI reads the table values. `synchronized` lets only one
thread at a time into those methods for the same object, so no thread sees half-updated data. The
`clients` map is locked with `synchronized (clients)`; `getAllClients()` copies it so the lock is
held only briefly. `sendLine()` is synchronized so two threads cannot mix their lines on one socket.

**Daemon thread.** The JVM does not wait for daemon threads before exiting. The timeout monitor is a
background helper, so it is a daemon.

**Heartbeat.** A small "I am alive" message sent regularly. Here it also carries the usage numbers.
2 s interval, 8 s timeout = 4 missed heartbeats before NOT RESPONDING, so one lost UDP packet does
not cause a false alarm.

**Swing EDT.** Swing is not thread-safe. Only the Event Dispatch Thread may change components, so
network threads use `SwingUtilities.invokeLater(...)` to pass the change to the EDT.

## 7. Likely viva questions

**Where is the TCP server?** `LabServer.java` line 45 (`new ServerSocket`) and line 64 (`accept()`).

**Where is the TCP client?** `LabAgent.java` line 71 (`new Socket`).

**Where is UDP?** Sending: `LabAgent.java` 113 and 121. Receiving: `LabServer.java` 46 and 78.

**Where did you use threads?** `ClientHandler extends Thread` (ClientHandler 11), started for each
PC at LabServer 65. `LabAgent implements Runnable` (LabAgent 19), run by the thread at LabAgent 64.
Lambda Runnables at LabServer 49-51 and LabAgent 63.

**How many threads does the server use for 30 PCs?** 30 ClientHandler threads + tcp-acceptor +
udp-receiver + timeout-monitor + the Swing EDT (and JVM housekeeping threads).

**Why not one UDP thread per PC?** One `DatagramSocket` receives datagrams from every PC; the PC ID
inside the message says whom it is from. There is no per-PC connection to wait on.

**What happens if two PCs use the same ID?** The second gets `ERROR|PC ID ... is already in use` and
is disconnected (`LabServer.registerClient`, line 136). After the first one goes offline the ID can
be used again.

**How do you detect a crashed or frozen PC?** If the agent process is killed, the TCP connection
closes and `readLine()` returns null or throws: OFFLINE ("connection lost"). If the PC freezes or
the network silently drops packets, heartbeats stop and after 8 s the timeout thread marks it
NOT RESPONDING.

**Difference between NOT RESPONDING and OFFLINE?** OFFLINE: the TCP connection is closed.
NOT RESPONDING: the TCP connection still exists but no UDP heartbeat came for 8 seconds.

**How do you avoid an alert on every heartbeat?** `setHighUsage()` (ClientHandler 112) returns true
only when the state changes, and the alert is raised only when it changes to high (LabServer 106).

**What would happen without `synchronized`?** For example the timeout thread could read
`lastHeartbeat` while the UDP thread is updating it, or two threads could change the map at the same
time and corrupt it (`HashMap` is not thread-safe).

**Why `volatile` on `running` in the agent?** It is set by the TCP receiver or the window and read by
the heartbeat thread. `volatile` makes sure the heartbeat thread sees the new value.

**How are real CPU, RAM and disk measured?** `com.sun.management.OperatingSystemMXBean`:
`getSystemCpuLoad()`, total and free physical memory. Disk: total and usable space of the drive
holding the user's home folder (`SystemMetrics.java`).

**What is simulated mode?** `--simulate` makes `SystemMetrics` return random values with 92-98 % CPU,
to demonstrate alerts without overloading a PC. The agent window, the registration message and the
dashboard all show SIMULATED.

**Does it work over a LAN?** Yes. The agent connects to the server's IP address instead of
localhost. The firewall on the instructor PC must allow TCP 5000 and UDP 5001.

## 8. Limitations (be honest)

- No encryption or authentication; anyone on the network who knows the protocol could send data.
- No automatic reconnect: if the server restarts, the agents must be started again.
- Nothing is saved; alerts and the table are lost when the server closes.
- Thresholds and ports are constants in the code, not a settings file.
- A student can close the agent; the instructor sees it go OFFLINE.
- One thread per PC is simple and fine for a lab (tens of PCs), but would not scale to thousands.
- We tested on one PC with real and simulated agents over localhost; a real multi-PC LAN test
  depends on the lab network and firewall.
