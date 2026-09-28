@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM  KidLock 一键构建脚本（arm64-v8a Release APK）
REM
REM  前置条件：
REM    · 已安装 Flutter SDK 与 JDK 17+，并已加入 PATH
REM    · 若不在 PATH 中，可先设置环境变量再运行本脚本：
REM        set FLUTTER_BIN=D:\flutter\bin\flutter.bat
REM        set ANDROID_SDK_ROOT=D:\Android\Sdk
REM ============================================================

if "%ANDROID_SDK_ROOT%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "PUB_HOSTED_URL=https://pub.flutter-io.cn"
set "FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn"

cd /d "%~dp0"

if "%FLUTTER_BIN%"=="" (
  where flutter >nul 2>nul
  if errorlevel 1 (
    echo [错误] 未找到 flutter 命令。
    echo        请把 Flutter SDK 的 bin 目录加入 PATH，或设置 FLUTTER_BIN 后再运行。
    pause
    exit /b 1
  )
  set "FLUTTER_BIN=flutter"
)

echo === 开始构建 Release APK（仅 arm64-v8a）===
call "%FLUTTER_BIN%" build apk --release --target-platform android-arm64
if errorlevel 1 (
  echo.
  echo [失败] 构建未通过，请查看上方日志。
  pause
  exit /b 1
)

echo.
echo === 构建完成 ===
echo 产物：build\app\outputs\flutter-apk\app-release.apk
pause
