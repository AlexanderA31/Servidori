@echo off
REM ====================================================================
REM DESCOMPARTIR IMPRESORA Y LIMPIAR CONFIGURACION
REM ====================================================================

setlocal enabledelayedexpansion

title Descompartir Impresora USB

REM Auto-elevacion a Administrador
net session >nul 2>&1
if %errorLevel% neq 0 (
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

cls
echo.
echo ====================================================================
echo   DESCOMPARTIR IMPRESORA USB
echo ====================================================================
echo.
echo Este script:
echo   1. Eliminara la tarea programada de inicio automatico
echo   2. Detendra el cliente USB si esta ejecutandose
echo   3. Limpiara los archivos de configuracion
echo.
echo ====================================================================
echo.
pause
echo.

REM ====================================================================
REM 1. ELIMINAR TAREA PROGRAMADA
REM ====================================================================
echo [1/4] Eliminando tarea programada de inicio automatico...

schtasks /query /tn "PrinterShareClient" >nul 2>&1
if errorlevel 1 (
    echo       [INFO] No existe tarea programada configurada
) else (
    schtasks /delete /tn "PrinterShareClient" /f >nul 2>&1
    if errorlevel 1 (
        echo       [ERROR] No se pudo eliminar la tarea programada
    ) else (
        echo       [OK] Tarea programada eliminada
    )
)
echo.

REM ====================================================================
REM 2. DETENER CLIENTE USB
REM ====================================================================
echo [2/4] Deteniendo cliente USB...

taskkill /F /FI "WINDOWTITLE eq Cliente USB*" >nul 2>&1
taskkill /F /FI "COMMANDLINE eq *usb-client.jar*" >nul 2>&1

timeout /t 2 /nobreak >nul

echo       [OK] Cliente detenido
echo.

REM ====================================================================
REM 3. DESCOMPARTIR IMPRESORAS
REM ====================================================================
echo [3/4] Descompartiendo impresoras...

set "CONFIG_FILE=%APPDATA%\PrinterShare\config.txt"

if exist "%CONFIG_FILE%" (
    for /f "tokens=1,2 delims==" %%a in ('type "%CONFIG_FILE%" ^| findstr /i "PRINTER_NAME"') do (
        set "PRINTER_NAME=%%b"
        
        powershell.exe -Command "Set-Printer -Name '!PRINTER_NAME!' -Shared $false -ErrorAction SilentlyContinue" >nul 2>&1
        
        if errorlevel 1 (
            echo       [INFO] No se pudo descompartir la impresora
        ) else (
            echo       [OK] Impresora descompartida: !PRINTER_NAME!
        )
    )
) else (
    echo       [INFO] No se encontro configuracion de impresora
)
echo.

REM ====================================================================
REM 4. LIMPIAR ARCHIVOS Y CONFIGURACION
REM ====================================================================
echo [4/4] Limpiando archivos de configuracion...

set "CONFIG_DIR=%APPDATA%\PrinterShare"

if exist "%CONFIG_DIR%" (
    echo       Eliminando directorio: %CONFIG_DIR%
    
    REM Eliminar archivos individuales primero
    del /Q "%CONFIG_DIR%\*.jar" 2>nul
    del /Q "%CONFIG_DIR%\*.bat" 2>nul
    del /Q "%CONFIG_DIR%\*.vbs" 2>nul
    del /Q "%CONFIG_DIR%\*.txt" 2>nul
    del /Q "%CONFIG_DIR%\*.log" 2>nul
    
    REM Eliminar subdirectorios
    if exist "%CONFIG_DIR%\logs" rd /S /Q "%CONFIG_DIR%\logs" 2>nul
    
    REM Intentar eliminar directorio principal
    rd /S /Q "%CONFIG_DIR%" 2>nul
    
    if exist "%CONFIG_DIR%" (
        echo       [AVISO] No se pudieron eliminar todos los archivos
        echo               Directorio: %CONFIG_DIR%
        echo               Eliminalos manualmente si es necesario
    ) else (
        echo       [OK] Archivos eliminados
    )
) else (
    echo       [INFO] No existe directorio de configuracion
)
echo.

REM ====================================================================
REM 5. LIMPIAR REGLAS DE FIREWALL
REM ====================================================================
echo [5/5] Limpiando reglas de firewall (opcional)...

netsh advfirewall firewall delete rule name="PrinterShare_IPP_631" >nul 2>&1

if errorlevel 1 (
    echo       [INFO] No habia reglas de firewall configuradas
) else (
    echo       [OK] Reglas de firewall eliminadas
)
echo.

REM ====================================================================
REM RESUMEN
REM ====================================================================
cls
echo.
echo ====================================================================
echo   LIMPIEZA COMPLETADA
echo ====================================================================
echo.
echo Se han eliminado:
echo   [X] Tarea programada de inicio automatico
echo   [X] Proceso del cliente USB
echo   [X] Archivos de configuracion
echo   [X] Comparticion de impresoras
echo   [X] Reglas de firewall
echo.
echo ====================================================================
echo   RESULTADO
echo ====================================================================
echo.
echo [OK] El error al iniciar Windows ya no aparecera
echo.
echo Si deseas volver a compartir la impresora:
echo   - Ejecuta: compartir-impresora-windows.bat
echo.
echo ====================================================================
echo.
echo Presiona cualquier tecla para salir...
pause >nul
exit /b 0
