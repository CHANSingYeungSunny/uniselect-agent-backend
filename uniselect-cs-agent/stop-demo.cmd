@echo off
rem ============================================================
rem  UniSelect CS Agent - stop any running backend instance.
rem  Use this BEFORE pressing the IDE Run button when a previous
rem  instance (started via start-demo.cmd or `java -jar`) may
rem  still be holding port 8080.
rem  Only java.exe processes are targeted.
rem ============================================================
chcp 65001 >nul
set "KILLED="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
    tasklist /FI "PID eq %%a" 2>nul | findstr /I "java.exe" >nul && (
        echo Terminating UniSelect instance PID %%a ...
        taskkill /PID %%a /F >nul 2>&1
        set "KILLED=1"
    )
)
if defined KILLED (
    echo.
    echo Port 8080 released. You can start the backend again.
) else (
    echo.
    echo Port 8080 is already free - nothing to stop.
)
pause
