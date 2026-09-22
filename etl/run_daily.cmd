@echo off
rem Daily ingest - entry point called by Windows Task Scheduler.
rem
rem ASCII only on purpose: this runs under whatever console codepage the
rem scheduler happens to use, and non-ASCII comments get mis-parsed as commands.
rem Korean documentation lives in etl/README.md.
rem
rem Register:  etl\register_daily_task.cmd
rem Manual:    etl\run_daily.cmd
rem
rem Exit codes: 0 ok or skipped, 1 setup problem, 4 fetch failed (ingested old
rem files), other = ingest failure.

setlocal

rem Resolve repo root from this script's location.
rem The scheduler does not guarantee a working directory.
set "REPO=%~dp0.."
pushd "%REPO%" || exit /b 1

set "PY=%REPO%\.venv\Scripts\python.exe"
set "LOGDIR=%REPO%\etl\out\logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"

rem Date-stamped log. The DB table ingest_run is the record of truth;
rem this file is only for reading stdout after an unattended run.
for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd"') do set "TODAY=%%d"
set "LOG=%LOGDIR%\ingest-%TODAY%.log"

echo.>> "%LOG%"
echo ==== %DATE% %TIME% ====>> "%LOG%"

if not exist "%PY%" (
    echo venv not found: %PY%>> "%LOG%"
    echo   py -m venv .venv>> "%LOG%"
    echo   .venv\Scripts\python.exe -m pip install -r etl\requirements.txt>> "%LOG%"
    popd
    exit /b 1
)

rem Fetch the source CSVs first. Without this the ingest only re-hashes the
rem files already in csv/ and every day ends SKIPPED. A failed fetch leaves
rem the old files in place, so the ingest still runs; the fetch code is kept
rem and reported at the end.
"%PY%" -X utf8 -m etl.fetch --source all >> "%LOG%" 2>&1
set "FETCH=%ERRORLEVEL%"

rem --wait-db 180: a missed run starts the moment the lid opens, before Docker
rem and WSL are back. Without waiting, the first connect fails and the day's
rem run is lost looking exactly like "Docker was off".
"%PY%" -X utf8 -m etl.ingest --source all --wait-db 180 >> "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"

rem 3 = could not reach the database even after waiting. That means Docker
rem     was not running, which is not an ingest failure. Report success so the
rem     scheduler history does not fill with red rows, and so no FAILED run is
rem     recorded. The log line above says how many attempts and seconds it took.
if "%CODE%"=="3" (
    echo database not reachable - skipped>> "%LOG%"
    set "CODE=0"
)

rem 4 = the ingest was fine but ran on stale files. Surface it in the scheduler.
if "%CODE%"=="0" if not "%FETCH%"=="0" (
    >> "%LOG%" echo fetch failed with %FETCH% - ingested the existing files
    set "CODE=%FETCH%"
)

rem Redirect first: "echo ... %CODE%>> file" would read a trailing digit as a
rem stream handle (0>> redirects stdin), silently swallowing the value.
>> "%LOG%" echo exit code %CODE%
popd
exit /b %CODE%
