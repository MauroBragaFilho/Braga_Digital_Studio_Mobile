@echo off
title Braga Design Studio Mobile
set "ROOT=%~dp0"
set "SCRIPTS=%ROOT%scripts"

:menu
cls
color 0A

echo ===============================
echo   Braga Design Studio Mobile
echo ===============================
echo.
echo 1 - Build Debug
echo 2 - Build Release
echo 3 - Instalar no Celular
echo 4 - Limpar Projeto
echo 5 - Validar Projeto
echo 6 - Sair
echo.

set /p op=Escolha uma opcao: 

if "%op%"=="1" call "%SCRIPTS%\build-debug.bat"
if "%op%"=="2" call "%SCRIPTS%\build-release.bat"
if "%op%"=="3" call "%SCRIPTS%\install-debug.bat"
if "%op%"=="4" call "%SCRIPTS%\clean-project.bat"
if "%op%"=="5" call "%SCRIPTS%\validation.bat"
if "%op%"=="6" exit /b

if not "%op%"=="1" if not "%op%"=="2" if not "%op%"=="3" if not "%op%"=="4" if not "%op%"=="5" if not "%op%"=="6" (
    color 4F
    echo.
    echo Opcao invalida.
    timeout /t 2 >nul
)

goto menu