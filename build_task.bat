@echo off
setlocal
cd /d "%~dp0"

where flutter >nul 2>nul
if errorlevel 1 (
  echo Flutter SDK not found in PATH.
  exit /b 1
)

call flutter build apk --release --target-platform android-arm64
if errorlevel 1 (
  echo Build failed.
  exit /b 1
)

if not exist "dist" mkdir "dist"
copy /y "build\app\outputs\flutter-apk\app-release.apk" "dist\app-release-v1.4.0.apk" >nul
if errorlevel 1 (
  echo Copy failed.
  exit /b 1
)

echo Release APK ready at dist\app-release-v1.4.0.apk
exit /b 0
