@echo off
setlocal enabledelayedexpansion

REM One-click launcher for the teacher Othello server.
REM Double-click this file to start the server.

REM Always run relative to this script directory (so the jar can be found).
cd /d "%~dp0"

REM ==== Config (edit these if you want) ====
set "PORT=25033"
set "MONITOR=1"    REM 1=enable GUI monitor (-monitor), 0=disable
set "DEBUG=0"      REM 1=enable (-debug), 0=disable
set "TIMEOUT=0"    REM seconds; 0=disable (-timeout)
set "SCORE=0"      REM 1=enable score output (-score), 0=disable

REM Optionally override PORT from first argument: LunchServer.bat 25033
if not "%~1"=="" set "PORT=%~1"

if not exist "OthelloServer.jar" (
  echo [ERROR] OthelloServer.jar not found in: %cd%
  echo         Expected: %cd%\OthelloServer.jar
  pause
  exit /b 1
)

set "ARGS=-port %PORT%"
if "%MONITOR%"=="1" set "ARGS=%ARGS% -monitor"
if "%DEBUG%"=="1" set "ARGS=%ARGS% -debug"
if "%SCORE%"=="1" set "ARGS=%ARGS% -score"
if not "%TIMEOUT%"=="0" set "ARGS=%ARGS% -timeout %TIMEOUT%"

echo Starting server: java -jar OthelloServer.jar %ARGS%
echo.

java -jar "OthelloServer.jar" %ARGS%

echo.
echo Server exited.
pause
