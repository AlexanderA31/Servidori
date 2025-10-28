@echo off
REM ====================================================================
REM Script para Compartir Impresora USB/Local con el Servidor Central
REM Version 4.1 - Funcionalidad completa en batch (CORREGIDO)
REM ====================================================================

setlocal enabledelayedexpansion

REM Configuracion del servidor
set "SERVER_IP=10.1.16.31"
set "SERVER_PORT=8080"
set "LOG_FILE=%TEMP%\compartir-impresora.log"
set "CONFIG_DIR=%APPDATA%\PrinterShare"
set "CONFIG_FILE=%CONFIG_DIR%\config.txt"

REM Verificar permisos de administrador
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo.
    echo Solicitando permisos de administrador...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

title Compartir Impresora USB/Local - Servidor %SERVER_IP%

cls
echo.
echo ====================================================================
echo   COMPARTIR IMPRESORA USB/LOCAL CON EL SERVIDOR
echo ====================================================================
echo.
echo   Servidor: %SERVER_IP%:%SERVER_PORT%
echo.
echo ====================================================================
echo.

REM Obtener informacion de la computadora
set "HOSTNAME=%COMPUTERNAME%"
set "USERNAME=%USERNAME%"

REM Obtener IP local
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4" ^| findstr /v "169.254"') do (
    set "IP_ADDRESS=%%a"
    set "IP_ADDRESS=!IP_ADDRESS:~1!"
    goto :ip_found
)
:ip_found

if "!IP_ADDRESS!"=="" (
    echo ERROR: No se pudo obtener la direccion IP
    goto :error_exit
)

echo Computadora: %HOSTNAME%
echo Usuario: %USERNAME%
echo IP: !IP_ADDRESS!
echo.
echo.

REM Listar impresoras locales/USB
echo Buscando impresoras USB/Locales...
echo.

set "PRINTER_COUNT=0"
set "TEMP_PRINTERS=%TEMP%\printers_list.txt"
set "TEMP_PS_SCRIPT=%TEMP%\get_printers.ps1"

REM Limpiar archivos temporales si existen
if exist "%TEMP_PRINTERS%" del "%TEMP_PRINTERS%"
if exist "%TEMP_PS_SCRIPT%" del "%TEMP_PS_SCRIPT%"

REM Crear script PowerShell temporal
echo Get-Printer ^| Where-Object {$_.Type -eq 'Local' -or $_.PortName -like 'USB*' -or $_.PortName -like 'LPT*' -or $_.PortName -like 'COM*'} ^| ForEach-Object {Write-Output "$($_.Name)^|$($_.PortName)^|$($_.DriverName)"} > "%TEMP_PS_SCRIPT%"

REM Ejecutar PowerShell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%TEMP_PS_SCRIPT%" > "%TEMP_PRINTERS%" 2>&1

if not exist "%TEMP_PRINTERS%" (
    echo.
    echo ERROR: No se pudieron listar las impresoras
    echo El comando PowerShell fallo
    goto :error_exit
)

REM Verificar si el archivo tiene contenido
for %%A in ("%TEMP_PRINTERS%") do set "FILE_SIZE=%%~zA"
if %FILE_SIZE% EQU 0 (
    echo.
    echo ERROR: No se encontraron impresoras USB/Locales
    echo Asegurate de que haya una impresora USB conectada
    goto :error_exit
)

REM Contar y mostrar impresoras
echo ====================================================================
echo   IMPRESORAS USB/LOCALES DISPONIBLES
echo ====================================================================
echo.

for /f "usebackq tokens=1,2,3 delims=|" %%a in ("%TEMP_PRINTERS%") do (
    set /a PRINTER_COUNT+=1
    set "PRINTER_NAME[!PRINTER_COUNT!]=%%a"
    set "PRINTER_PORT[!PRINTER_COUNT!]=%%b"
    set "PRINTER_DRIVER[!PRINTER_COUNT!]=%%c"
    echo [!PRINTER_COUNT!] %%a
    echo     Puerto: %%b
    echo     Driver: %%c
    echo.
)

if %PRINTER_COUNT% EQU 0 (
    echo.
    echo ERROR: No se pudieron procesar las impresoras
    goto :error_exit
)

REM Seleccionar impresora
echo.
set /p "SELECTION=Selecciona el numero de la impresora (1-%PRINTER_COUNT%): "

if "!SELECTION!"=="" (
    echo ERROR: No se selecciono ninguna impresora
    goto :error_exit
)

REM Validar que sea un numero
echo !SELECTION!| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo ERROR: Debes ingresar un numero valido
    goto :error_exit
)

if !SELECTION! LSS 1 (
    echo ERROR: El numero debe ser mayor o igual a 1
    goto :error_exit
)
if !SELECTION! GTR %PRINTER_COUNT% (
    echo ERROR: El numero debe ser menor o igual a %PRINTER_COUNT%
    goto :error_exit
)

set "SELECTED_PRINTER=!PRINTER_NAME[%SELECTION%]!"
set "SELECTED_PORT=!PRINTER_PORT[%SELECTION%]!"
set "SELECTED_DRIVER=!PRINTER_DRIVER[%SELECTION%]!"

echo.
echo ====================================================================
echo   IMPRESORA SELECCIONADA
echo ====================================================================
echo.
echo   Nombre: !SELECTED_PRINTER!
echo   Puerto: !SELECTED_PORT!
echo   Driver: !SELECTED_DRIVER!
echo.

REM Crear nombre de alias para la impresora
set "PRINTER_ALIAS=!SELECTED_PRINTER!_%HOSTNAME%"

REM Crear JSON para registro
set "TEMP_JSON=%TEMP%\printer_register.json"
(
    echo {
    echo   "alias": "!PRINTER_ALIAS!",
    echo   "model": "!SELECTED_DRIVER!",
    echo   "ip": "!IP_ADDRESS!",
    echo   "location": "Compartida-USB - %HOSTNAME% - Usuario: %USERNAME%",
    echo   "protocol": "IPP",
    echo   "port": 631
    echo }
) > "%TEMP_JSON%"

REM Registrar en el servidor
echo Registrando impresora en el servidor...
echo URL: http://%SERVER_IP%:%SERVER_PORT%/admin/api/register-shared-printer
echo.

set "TEMP_RESPONSE=%TEMP%\printer_response.json"
set "TEMP_CURL_SCRIPT=%TEMP%\curl_request.ps1"

REM Crear script PowerShell para hacer la peticion HTTP
(
    echo $json = Get-Content '%TEMP_JSON%' -Raw
    echo try {
    echo     $response = Invoke-RestMethod -Uri 'http://%SERVER_IP%:%SERVER_PORT%/admin/api/register-shared-printer' -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 15
    echo     $response ^| ConvertTo-Json ^| Out-File '%TEMP_RESPONSE%' -Encoding UTF8
    echo     exit 0
    echo } catch {
    echo     Write-Host "Error:" $_.Exception.Message
    echo     exit 1
    echo }
) > "%TEMP_CURL_SCRIPT%"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%TEMP_CURL_SCRIPT%"

if errorlevel 1 (
    echo ERROR: No se pudo conectar al servidor
    echo.
    echo POSIBLES CAUSAS:
    echo   1. El servidor no esta accesible en %SERVER_IP%:%SERVER_PORT%
    echo   2. Firewall bloqueando la conexion
    echo   3. Servidor de impresoras no esta ejecutandose
    echo.
    goto :error_exit
)

REM Leer la respuesta
if not exist "%TEMP_RESPONSE%" (
    echo ERROR: No se recibio respuesta del servidor
    goto :error_exit
)

REM Extraer puerto IPP de la respuesta
set "IPP_PORT=631"
for /f "tokens=2 delims=:, " %%a in ('findstr /i "ippPort" "%TEMP_RESPONSE%"') do (
    set "IPP_PORT=%%a"
)

echo Respuesta del servidor recibida
echo Puerto IPP asignado: %IPP_PORT%
echo.

REM Compartir impresora via SMB
echo Configurando comparticion de impresora...
echo.

REM Crear nombre compartido limpio (sin caracteres especiales)
set "SHARE_NAME=!SELECTED_PRINTER!"
set "SHARE_NAME=!SHARE_NAME: =_!"
set "SHARE_NAME=!SHARE_NAME:-=_!"
set "SHARE_NAME=!SHARE_NAME:(=!"
set "SHARE_NAME=!SHARE_NAME:)=!"

REM Compartir la impresora usando PowerShell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Set-Printer -Name '!SELECTED_PRINTER!' -Shared $true -ShareName '!SHARE_NAME!' -ErrorAction SilentlyContinue"

if %errorlevel% equ 0 (
    echo [OK] Impresora compartida via SMB: \\%HOSTNAME%\!SHARE_NAME!
) else (
    echo [AVISO] No se pudo compartir la impresora via SMB
)

echo.

REM Configurar regla de firewall
echo Configurando firewall para puerto %IPP_PORT%...
echo.

set "RULE_NAME=PrinterShare_IPP_%IPP_PORT%"

REM Eliminar regla existente si existe
netsh advfirewall firewall delete rule name="%RULE_NAME%" >nul 2>&1

REM Crear nueva regla
netsh advfirewall firewall add rule name="%RULE_NAME%" dir=in action=allow protocol=TCP localport=%IPP_PORT% profile=any description="Permite acceso IPP a impresora compartida" >nul 2>&1

if %errorlevel% equ 0 (
    echo [OK] Regla de firewall creada para puerto %IPP_PORT%
) else (
    echo [AVISO] No se pudo crear regla de firewall
)

echo.

REM Guardar configuracion
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%"

(
    echo PRINTER_NAME=!SELECTED_PRINTER!
    echo SERVER_IP=%SERVER_IP%
    echo SERVER_PORT=%SERVER_PORT%
    echo IPP_PORT=%IPP_PORT%
    echo REGISTERED_AT=%DATE% %TIME%
) > "%CONFIG_FILE%"

REM Limpiar archivos temporales
del "%TEMP_PRINTERS%" 2>nul
del "%TEMP_JSON%" 2>nul
del "%TEMP_RESPONSE%" 2>nul
del "%TEMP_PS_SCRIPT%" 2>nul
del "%TEMP_CURL_SCRIPT%" 2>nul

REM Resumen final
echo ====================================================================
echo   INFORMACION DE CONEXION
echo ====================================================================
echo.
echo   Otras computadoras pueden conectarse usando:
echo.
echo   Desde el Servidor de Impresoras:
echo      - La impresora aparecera automaticamente en la tabla
echo      - Puerto IPP asignado: %IPP_PORT%
echo.
echo   Conexion directa SMB (Windows):
echo      \\%HOSTNAME%\!SHARE_NAME!
echo.
echo   IMPORTANTE:
echo      - Esta computadora debe estar ENCENDIDA
echo      - La impresora debe estar CONECTADA
echo      - No suspender la computadora
echo.
echo ====================================================================
echo.
echo ====================================================================
echo   CONFIGURACION COMPLETADA EXITOSAMENTE
echo ====================================================================
echo.
echo La impresora USB ha sido compartida y registrada en el servidor.
echo Otras computadoras pueden conectarse a traves del servidor.
echo.
echo Para ver el estado, accede al panel de administracion:
echo    http://%SERVER_IP%:%SERVER_PORT%/admin/printers
echo.
echo ====================================================================
echo.
echo Presiona cualquier tecla para cerrar esta ventana...
pause >nul
exit /b 0

:error_exit
echo.
echo ====================================================================
echo   ERROR EN LA CONFIGURACION
echo ====================================================================
echo.
echo Revisa los mensajes anteriores para mas detalles.
echo.
echo Presiona cualquier tecla para cerrar esta ventana...
pause >nul
exit /b 1
