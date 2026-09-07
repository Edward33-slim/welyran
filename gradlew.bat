@echo off
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo Gradle is not installed. Run this project in Android Studio, or provision Gradle before using gradlew.bat.
exit /b 1
