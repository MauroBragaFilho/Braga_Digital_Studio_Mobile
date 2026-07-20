call gradlew.bat --stop

@echo off
title Braga Design Studio Mobile - Validacao
color 09
setlocal EnableDelayedExpansion

set APP_NAME=BragaDesignStudioMobile
set APK_DIR=APK

for /f "tokens=2 delims== " %%A in ('findstr /C:"versionName" app\build.gradle.kts') do (
    set VERSION=%%~A
)

set VERSION=!VERSION:"=!
set VERSION=!VERSION: =!

echo Limpando projeto...
.\gradlew clean
rmdir /s /q .gradle 2>nul
rmdir /s /q .idea 2>nul
rmdir /s /q .kotlin 2>nul
rmdir /s /q captures 2>nul
rmdir /s /q reports 2>nul
rmdir /s /q generated 2>nul
rmdir /s /q tmp 2>nul
rmdir /s /q out 2>nul

for /d /r %%i in (build) do (
    if exist "%%i" (
        echo Removendo %%i
        rmdir /s /q "%%i"
    )
)

echo.
echo Projeto limpo.
if errorlevel 1 goto erro

echo Compilando APK Debug...
call gradlew.bat assembleDebug
if errorlevel 1 goto erro

echo Executando testes...
call gradlew.bat test
if errorlevel 1 goto erro

if not exist "%APK_DIR%" mkdir "%APK_DIR%"

copy /Y "app\build\outputs\apk\debug\app-debug.apk" "%APK_DIR%\%APP_NAME%-v%VERSION%-Debug.apk" >nul

echo.
echo ==========================================
echo VALIDACAO CONCLUIDA COM SUCESSO!
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
echo FALHA DURANTE A VALIDACAO
echo ==========================================
echo.
pause
exit /b 1
