@echo off
chcp 65001 >nul
setlocal
set PKG=com.kidlock.app
set PORT=9090

echo ============================================================
echo  KidLock 诊断脚本（需先用 USB / ADB 网络连接盒子）
echo ============================================================
echo.

echo [1/5] 设备连接状态
adb devices
echo.

echo [2/5] 守护服务是否运行（应出现 isForeground=true）
adb shell dumpsys activity services %PKG% | findstr /C:"ServiceRecord" /C:"isForeground" /C:"startRequested"
echo.

echo [3/5] Web 端口 %PORT% 是否已被监听（应出现 LISTEN）
adb shell "ss -ltn 2>/dev/null | grep %PORT% || netstat -ltn 2>/dev/null | grep %PORT% || echo 未发现监听"
echo.

echo [4/5] 本机自测 Web 接口（应返回 JSON）
adb shell "echo -e 'GET /api/status HTTP/1.0\r\n\r\n' | nc 127.0.0.1 %PORT% 2>/dev/null | tail -n +5 || echo nc 不可用，请用电脑浏览器访问 http://盒子IP:%PORT%/api/status"
echo.

echo [5/5] 最近 300 条 KidLock 日志
adb shell logcat -d -t 300 -s KidLock KidLock.Cfg KidLock.Web KidLock.Lock
echo.

echo ============================================================
echo  自启验证步骤：重启盒子 -^> 等 60 秒 -^> 重新运行本脚本
echo  （重点看 [2] 服务是否自动起来、[3] 端口是否监听）
echo ============================================================
pause
