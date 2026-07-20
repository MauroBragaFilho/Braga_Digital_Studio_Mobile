call gradlew.bat --stop

@echo off
title Braga Design Studio Mobile - Limpeza
color 0E

echo Removendo caches...

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
pause 

exit /b 1