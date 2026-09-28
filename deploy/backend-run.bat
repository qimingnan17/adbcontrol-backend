@echo off
set APP_HOME=C:\adbcontrol
if not exist "%APP_HOME%\logs" mkdir "%APP_HOME%\logs"

set BACKEND_OPTS=-Xms128m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError

:loop
if exist "%APP_HOME%\logs\backend.log" (
    for %%F in ("%APP_HOME%\logs\backend.log") do (
        if %%~zF GTR 20971520 (
            move /y "%APP_HOME%\logs\backend.log" "%APP_HOME%\logs\backend.old.log" >nul 2>&1
        )
    )
)

cd /d "%APP_HOME%\app\bin"
call backend.bat >> "%APP_HOME%\logs\backend.log" 2>&1
echo [%date% %time%] backend exited with code %errorlevel%, restarting in 5s >> "%APP_HOME%\logs\backend.log"
ping 127.0.0.1 -n 6 >nul
goto loop
