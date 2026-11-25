@echo off
rem Starts a student agent (run build.bat first).
rem   run-agent.bat                        asks for the server IP and PC ID
rem   run-agent.bat 192.168.1.10 PC-05     connects to that server
rem   run-agent.bat localhost PC-09 --simulate   fake high-CPU data for demos
cd /d "%~dp0"
java -cp out labmonitor.LabAgent %*
