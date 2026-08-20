@echo off
rem ============================================================
rem  UniSelect CS Agent - one-click backend starter (Windows)
rem  NOTE: avoids `mvn spring-boot:run`, which fails with
rem  "Could not build classpath" when the project path contains
rem  non-ASCII characters (known Spring Boot Maven Plugin issue).
rem  Strategy: mvn package -> java -jar.
rem  Extra: auto-kills a stale instance holding port 8080 before
rem  starting, so "Port already in use" never blocks the script.
rem  (Only java.exe is targeted, to avoid touching other services.)
rem ============================================================
chcp 65001 >nul
cd /d "%~dp0"
set "JAVA_HOME=C:\PROGRA~1\Java\jdk-21"

rem --- [0/3] Release port 8080 if a stale java process holds it ---
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
    tasklist /FI "PID eq %%a" 2>nul | findstr /I "java.exe" >nul && (
        echo [0/3] Port 8080 held by stale java PID %%a - terminating...
        taskkill /PID %%a /F >nul 2>&1
    )
)

echo [1/3] Building executable jar (skip tests)...
call "C:\Users\Asus\mvn_home\apache-maven-3.9.9\bin\mvn.cmd" -DskipTests -f pom.xml package
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Check the messages above. Make sure Maven and JDK are available.
    pause
    exit /b 1
)

echo [2/3] Starting backend on http://localhost:8080 ... ^(Ctrl+C to stop^)
"C:\PROGRA~1\Java\jdk-21\bin\java" -jar target\uniselect-cs-agent-0.1.0.jar

echo [3/3] Backend stopped.
pause
