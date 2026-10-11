@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d "%~dp0"
call gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.quicknovel.BiqugeProviderTest#testSearch" --no-daemon
