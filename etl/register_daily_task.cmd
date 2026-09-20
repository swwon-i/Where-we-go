@echo off
rem Register the daily ingest with Windows Task Scheduler.
rem ASCII only - see the note in run_daily.cmd.
rem
rem   register : etl\register_daily_task.cmd
rem   remove   : schtasks /Delete /TN "WhereWeGo-DailyIngest" /F
rem   inspect  : schtasks /Query  /TN "WhereWeGo-DailyIngest" /V /FO LIST
rem   run now  : schtasks /Run    /TN "WhereWeGo-DailyIngest"
rem
rem No administrator rights needed - registers under the current user.

setlocal

set "TASK=WhereWeGo-DailyIngest"
set "SCRIPT=%~dp0run_daily.cmd"
rem LOCALDATA publishes on a 2-day lag; an early run picks up that day's file.
set "AT=04:30"

echo task   : %TASK%
echo script : %SCRIPT%
echo when   : daily at %AT%
echo.

rem The path has no spaces issue here, but keep cmd /c so the scheduler
rem reports the batch file's exit code rather than the shell's.
schtasks /Create /TN "%TASK%" /TR "cmd /c \"%SCRIPT%\"" /SC DAILY /ST %AT% /F
if errorlevel 1 (
    echo.
    echo registration failed
    exit /b 1
)

echo.
echo Registered. To run it once now:
echo   schtasks /Run /TN "%TASK%"
