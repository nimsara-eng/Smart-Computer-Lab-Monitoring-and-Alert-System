@echo off
rem Starts the instructor dashboard (run build.bat first).
cd /d "%~dp0"
java -cp out labmonitor.LabServer
