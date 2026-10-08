@echo off
rem Convenience wrapper for cmd.exe: runs commitgap.ps1 with the same arguments.
rem The execution policy override applies to this one PowerShell process only.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0commitgap.ps1" %*
exit /b %ERRORLEVEL%
