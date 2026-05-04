@echo off
setlocal
cd /d "%~dp0.."
powershell -ExecutionPolicy Bypass -File ".\tools\export-main-sources.ps1"
endlocal

