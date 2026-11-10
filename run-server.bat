@echo off
rem Starts the instructor dashboard. Add --nogui for the text mode server.
cd /d "%~dp0"
if not exist out\labmonitor\server\LabServer.class call "%~dp0build.bat" || exit /b 1
java -cp out labmonitor.server.LabServer %*
