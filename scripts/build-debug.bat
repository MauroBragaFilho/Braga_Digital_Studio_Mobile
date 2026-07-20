call gradlew.bat --stop

@echo off
title Braga Design Studio Mobile - Build Debug
color 02
setlocal EnableDelayedExpansion

set APP_NAME=BragaDesignStudioMobile
set APK_DIR=APK

for /f "tokens=2 delims== " %%A in ('findstr /C:"versionName" app\build.gradle.kts') do (
    set VERSION=%%~A
)

set VERSION=!VERSION:"=!
set VERSION=!VERSION: =!

echo ==========================================
echo       Braga Design Studio Mobile
echo           Gerando APK Debug...
echo ==========================================
echo.

call gradlew.bat assembleDebug
if errorlevel 1 goto erro

if not exist "%APK_DIR%" mkdir "%APK_DIR%"

copy /Y "app\build\outputs\apk\debug\app-debug.apk" "%APK_DIR%\%APP_NAME%-v%VERSION%-Debug.apk" >nul

echo.
echo ==========================================
echo        BUILD FINALIZADA COM SUCESSO!
echo ==========================================
echo.
echo APK:
echo %APK_DIR%\%APP_NAME%-v%VERSION%-Debug.apk
echo.

pause
exit /b 1

:erro
color 4F
echo.
echo ==========================================
echo             ERRO NA COMPILACAO
echo ==========================================
echo.
pause
exit /b 1
