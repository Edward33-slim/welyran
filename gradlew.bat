@echo off
setlocal
set ROOT_DIR=%~dp0
set DIST_DIR=%ROOT_DIR%.gradle-dist
set GRADLE_VERSION=8.9
set DIST_HOME=%DIST_DIR%\gradle-%GRADLE_VERSION%
if not exist "%DIST_HOME%\bin\gradle.bat" (
  if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
  if not exist "%DIST_DIR%\gradle-%GRADLE_VERSION%-bin.zip" powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%DIST_DIR%\gradle-%GRADLE_VERSION%-bin.zip'"
  powershell -NoProfile -Command "Expand-Archive -Force '%DIST_DIR%\gradle-%GRADLE_VERSION%-bin.zip' '%DIST_DIR%'"
)
call "%DIST_HOME%\bin\gradle.bat" %*
endlocal
