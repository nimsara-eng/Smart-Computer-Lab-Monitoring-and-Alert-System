@echo off
rem Starts the agent on a lab PC.
rem   run-agent.bat --server 192.168.1.10 --id PC-01
cd /d "%~dp0"
if not exist out\labmonitor\client\LabAgent.class call "%~dp0build.bat" || exit /b 1
java -cp out labmonitor.client.LabAgent %*
