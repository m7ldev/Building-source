@echo off
setlocal
cd /d "%~dp0"
set LOG=%~dp0build.log
echo ==== Building source build started %DATE% %TIME% ==== > "%LOG%"

if not exist "BuildingSource" (
  echo Extracting... >> "%LOG%"
  tar -xf BuildingSource-project.zip >> "%LOG%" 2>&1
)

if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
echo JAVA_HOME=%JAVA_HOME% >> "%LOG%"

cd BuildingSource
echo ==== fetching proot ==== >> "%LOG%"
powershell -NoProfile -ExecutionPolicy Bypass -File fetch-proot.ps1 >> "%LOG%" 2>&1
echo PROOT_EXIT=%ERRORLEVEL% >> "%LOG%"

set "SDKFWD=%ANDROID_HOME:\=/%"
echo sdk.dir=%SDKFWD%> local.properties

echo ==== gradlew assembleDebug ==== >> "%LOG%"
call gradlew.bat assembleDebug --no-daemon --stacktrace >> "%LOG%" 2>&1
echo GRADLE_EXIT=%ERRORLEVEL% >> "%LOG%"

if not exist "%~dp0APK" mkdir "%~dp0APK"
copy /y "app\build\outputs\apk\debug\*.apk" "%~dp0APK\" >> "%LOG%" 2>&1
dir /b "%~dp0APK" >> "%LOG%" 2>&1
echo ==== DONE ==== >> "%LOG%"
