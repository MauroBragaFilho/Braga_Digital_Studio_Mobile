call gradlew.bat --stop

@echo off
title Braga Design Studio Mobile - Build Release
color 0B
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
echo           Gerando APK Release...
echo ==========================================
echo.

call gradlew.bat assembleRelease
if errorlevel 1 goto erro

if not exist "%APK_DIR%" mkdir "%APK_DIR%"

copy /Y "app\build\outputs\apk\release\app-release-unsigned.apk" "%APK_DIR%\%APP_NAME%-v%VERSION%-Release.apk" >nul

echo.
echo ==========================================
echo        BUILD FINALIZADA COM SUCESSO!
echo ==========================================
echo.
echo APK:
echo %APK_DIR%\%APP_NAME%-v%VERSION%-Release.apk
echo.

pause
exit

:erro
color 4F
echo.
echo ==========================================
echo             ERRO NA COMPILACAO
echo ==========================================
echo.
pause
exit /b 1