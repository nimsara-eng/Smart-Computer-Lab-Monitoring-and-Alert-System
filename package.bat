@echo off
rem Builds dist\LabMonitor: LabServer.exe and LabAgent.exe with their own Java runtime inside.
rem The folder (or dist\LabMonitor.zip) can be copied to any 64-bit Windows PC, with or without Java.
rem Needs JDK 14 or newer on this PC (for the jpackage tool).
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1

where jpackage >nul 2>nul
if errorlevel 1 (
    echo jpackage was not found. Install JDK 14 or newer and put its bin folder on the PATH.
    exit /b 1
)

if exist build\pkg rmdir /s /q build\pkg
if exist dist rmdir /s /q dist
mkdir build\pkg

jar --create --file build\pkg\labmonitor.jar -C out . || exit /b 1

rem Only the Java modules the program uses are included, which keeps the folder small.
jpackage --type app-image --name LabServer --dest dist ^
    --input build\pkg --main-jar labmonitor.jar --main-class labmonitor.server.LabServer ^
    --add-launcher LabAgent=packaging\agent.properties ^
    --add-modules java.desktop,java.management,jdk.management ^
    --app-version 1.0
if errorlevel 1 (
    echo PACKAGING FAILED
    exit /b 1
)

ren dist\LabServer LabMonitor
xcopy /e /i /q config dist\LabMonitor\config >nul
copy /y packaging\HOW-TO-RUN.txt dist\LabMonitor >nul
tar -a -c -f dist\LabMonitor.zip -C dist LabMonitor

echo.
echo Package ready:  dist\LabMonitor   and   dist\LabMonitor.zip
