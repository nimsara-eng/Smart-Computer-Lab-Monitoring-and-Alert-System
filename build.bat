@echo off
rem Compiles the 4 Java files into the "out" folder.
rem Java 8 class files are produced, so the program runs on Java 8, 17, 21 or newer.
cd /d "%~dp0"
if not exist out mkdir out
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d out src\labmonitor\*.java
if errorlevel 1 (
    echo BUILD FAILED
    exit /b 1
)
echo Build OK
