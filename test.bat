@echo off
rem Build and run every dependency-free QA suite. The final suite briefly opens Swing windows.
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp out -d out tests\labmonitor\server\*.java
if errorlevel 1 exit /b 1
for %%T in (PcInfoHeartbeatTest AlertStabilityTest ServerConfigTest AlertLoggingTest NetworkFlowsTest AgentFlowsTest DisconnectReconnectTest SwingSmokeTest GuiInteractionTest WindowCloseTest) do (
    java -cp out labmonitor.server.%%T
    if errorlevel 1 exit /b 1
)
if "%~1"=="--load" (
    java -cp out labmonitor.server.LabLoadTest
    if errorlevel 1 exit /b 1
)
echo All QA suites passed.
