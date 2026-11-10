@echo off
rem One-machine demo: the dashboard plus four lab PCs.
rem   PC-01  this real computer (real CPU, memory, disk and process list)
rem   PC-02  simulated, normal behaviour
rem   PC-03  simulated, CPU stuck at 96 percent      -> HIGH_CPU alert
rem   PC-04  simulated, running a blocked application -> BLOCKED_APP alert
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1
start "Lab Server" java -cp out labmonitor.server.LabServer
timeout /t 2 /nobreak >nul
start "PC-01" javaw -cp out labmonitor.client.LabAgent --server localhost --id PC-01
start "PC-02" javaw -cp out labmonitor.client.LabAgent --server localhost --id PC-02 --user Kasun --simulate
start "PC-03" javaw -cp out labmonitor.client.LabAgent --server localhost --id PC-03 --user Nimali --sim-cpu 96
start "PC-04" javaw -cp out labmonitor.client.LabAgent --server localhost --id PC-04 --user Sahan --simulate --fake-proc vlc.exe
