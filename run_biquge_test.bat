@echo off
setlocal

set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist %ADB% set ADB="C:\Users\Alex\AppData\Local\Android\Sdk\platform-tools\adb.exe"

echo ============================================================
echo  BiqugeProvider Instrumented Test  (runs on emulator/device)
echo ============================================================
echo.
echo Checking connected devices...
%ADB% devices
echo.

cd /d "%~dp0"

echo Building + running instrumented tests...
echo (This will install the debug APK on the emulator)
echo.

call gradlew.bat connectedDebugAndroidTest ^
  -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.quicknovel.BiqugeProviderTest ^
  --info 2>&1 | findstr /i "BiqugeTest\|PASSED\|FAILED\|SKIPPED\|Error\|Exception\|testMain\|testSearch\|testLoad\|Instrumentation\|BUILD"

echo.
echo --- Full logcat output (BiqugeTest tag) ---
%ADB% logcat -d -s BiqugeTest:* | more

echo.
pause
