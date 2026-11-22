@echo off
cd /d "%~dp0"
echo Checking Java...
java -version
where mvn >nul 2>&1
if errorlevel 1 (
  echo Maven is not installed or not in PATH.
  pause
  exit /b 1
)
call mvn -U clean package
if errorlevel 1 (
  echo BUILD FAILED. Copy the error output for troubleshooting.
) else (
  echo Plugin is in target\ProgressiveWeapons-1.0.1.jar
)
pause
