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
rem 22:00, not early morning. This is a laptop in Modern Standby (S0): it
rem ignores the task's wake timer, so a 04:30 run never fired (2026-09-22).
rem LOCALDATA publishes on a 2-day lag, so running late loses nothing.
set "AT=22:00"

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

rem schtasks /Create resets conditions to defaults, which include
rem "do not start on battery". Combined with a missed run, that meant the
rem catch-up was refused when the lid was opened unplugged. Set them here so
rem re-registering does not bring that back.
rem   StartWhenAvailable        run a missed schedule as soon as possible
rem   WakeToRun                 try to wake (ignored under Modern Standby)
rem   DisallowStartIfOnBatteries off - the catch-up often happens unplugged
powershell -NoProfile -Command "$t = Get-ScheduledTask -TaskName '%TASK%'; $s = $t.Settings; $s.StartWhenAvailable = $true; $s.WakeToRun = $true; $s.DisallowStartIfOnBatteries = $false; Set-ScheduledTask -TaskName '%TASK%' -Settings $s | Out-Null"
if errorlevel 1 (
    echo.
    echo registered, but setting the conditions failed
    exit /b 1
)

echo.
echo Registered. To run it once now:
echo   schtasks /Run /TN "%TASK%"
