call gradlew.bat --stop

@echo off
title Braga Design Studio Mobile - Instalar no Android
color 0A

echo ==========================================
echo       Braga Design Studio Mobile
echo           Gerando APK Debug...
echo ==========================================
echo.

call gradlew.bat installDebug
if errorlevel 1 goto erro

echo.
echo ==========================================
echo        BUILD FINALIZADA COM SUCESSO!
echo ==========================================
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

