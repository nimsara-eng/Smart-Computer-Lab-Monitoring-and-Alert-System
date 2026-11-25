@echo off
rem Demo on one PC: the server, one real agent and one SIMULATED high-CPU agent.
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1
start "Lab Server" java -cp out labmonitor.LabServer
rem wait about 3 seconds so the server is ready before the agents connect
ping -n 4 127.0.0.1 >nul
start "PC-01 real" javaw -cp out labmonitor.LabAgent localhost PC-01
start "PC-02 simulated" javaw -cp out labmonitor.LabAgent localhost PC-02 --simulate
echo Demo started. Close the windows to stop.
