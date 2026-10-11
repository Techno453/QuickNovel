@echo off
setlocal

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%

set KOTLINC="%LOCALAPPDATA%\Programs\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
if not exist %KOTLINC% (
    set KOTLINC="C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat"
)

echo Running BiqugeProvider integration test...
echo.

%KOTLINC% -script "%~dp0test_biquge.main.kts"

echo.
pause
