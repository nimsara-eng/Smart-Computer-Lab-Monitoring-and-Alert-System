@echo off
rem Compiles the whole project into the "out" folder.
rem Java 8 class files are produced so the program runs on any Java version from 8 upwards.
cd /d "%~dp0"
if not exist out mkdir out
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d out -sourcepath src ^
    src\labmonitor\server\LabServer.java src\labmonitor\client\LabAgent.java
if errorlevel 1 (
    echo BUILD FAILED
    exit /b 1
)
echo Build OK
