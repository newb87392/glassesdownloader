@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup-Gradle.ps1"
if errorlevel 1 (
  echo.
  echo Setup FAILED. Please send the error shown above.
) else (
  echo.
  echo Setup complete. Open the GlassesDownloader folder in Android Studio.
)
echo.
pause
