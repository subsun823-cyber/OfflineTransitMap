@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare_offline_data.ps1"
if errorlevel 1 (
  echo Preparation failed. Read the message above; existing project settings were not changed.
  pause
  exit /b 1
)
pause
