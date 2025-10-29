@echo off
REM ====================================================================
REM Script para Compartir Impresora USB/Local con el Servidor Central
REM Version 4.2 - Con instalacion automatica de Java
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

REM ====================================================================
REM Verificar e instalar Java si es necesario
REM ====================================================================
echo.
echo Verificando Java...
echo.

java -version >nul 2>&1
if %errorLevel% neq 0 (
    echo [AVISO] Java no esta instalado
    echo.
    echo Java es necesario para ejecutar el servidor IPP en esta computadora.
    echo Este servidor permite recibir trabajos de impresion del servidor central.
    echo.
    set /p "INSTALL_JAVA=Deseas instalar Java automaticamente? (S/N): "
    
    if /i "!INSTALL_JAVA!"=="S" (
        echo.
        echo Descargando e instalando Java...
        echo Esto puede tomar varios minutos...
        echo.
        
        REM Descargar instalador de Java
        set "JAVA_URL=https://download.oracle.com/java/17/latest/jdk-17_windows-x64_bin.exe"
        set "JAVA_INSTALLER=%TEMP%\java_installer.exe"
        
        echo Descargando Java desde Oracle...
        powershell -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://download.oracle.com/java/17/latest/jdk-17_windows-x64_bin.exe' -OutFile '%JAVA_INSTALLER%' -TimeoutSec 300; exit 0 } catch { Write-Host 'Error descargando Java'; exit 1 }"
        
        if !errorlevel! neq 0 (
            echo.
            echo [ERROR] No se pudo descargar Java automaticamente
            echo.
            echo Opciones:
            echo   1. Descarga Java manualmente desde: https://www.java.com/es/download/
            echo   2. Ejecuta este script nuevamente despues de instalar Java
            echo   3. Continua sin Java (la impresora se registrara pero no recibira trabajos)
            echo.
            set /p "CONTINUE_WITHOUT_JAVA=Continuar sin Java? (S/N): "
            
            if /i "!CONTINUE_WITHOUT_JAVA!"=="N" (
                echo.
                echo Saliendo...
                pause
                exit /b 1
            )
            
            set "SKIP_CLIENT=true"
        ) else (
            echo.
            echo Java descargado exitosamente
            echo Instalando Java...
            echo.
            echo IMPORTANTE: Sigue las instrucciones del instalador
            echo.
            
            REM Instalar Java silenciosamente
            start /wait "" "!JAVA_INSTALLER!" /s ADDLOCAL="ToolsFeature,SourceFeature,PublicjreFeature"
            
            REM Limpiar instalador
            del "!JAVA_INSTALLER!" 2>nul
            
            echo.
            echo Verificando instalacion...
            
            REM Refrescar variables de entorno
            call refreshenv >nul 2>&1
            
            REM Buscar Java en ubicaciones comunes
            set "JAVA_FOUND=false"
            
            for /d %%i in ("C:\Program Files\Java\jdk-*") do (
                if exist "%%i\bin\java.exe" (
                    set "PATH=%%i\bin;!PATH!"
                    set "JAVA_HOME=%%i"
                    set "JAVA_FOUND=true"
                    echo [OK] Java encontrado en: %%i
                    goto :java_found
                )
            )
            
            for /d %%i in ("C:\Program Files\Java\jre*") do (
                if exist "%%i\bin\java.exe" (
                    set "PATH=%%i\bin;!PATH!"
                    set "JAVA_HOME=%%i"
                    set "JAVA_FOUND=true"
                    echo [OK] Java encontrado en: %%i
                    goto :java_found
                )
            )
            
            :java_found
            
            if "!JAVA_FOUND!"=="false" (
                echo [AVISO] Java se instalo pero no se encuentra en el PATH
                echo          Reinicia esta computadora y ejecuta el script nuevamente
                set "SKIP_CLIENT=true"
            ) else (
                echo [OK] Java instalado correctamente
                java -version
            )
        )
    ) else (
        echo.
        echo [INFO] Continuando sin Java
        echo        La impresora se registrara pero no podra recibir trabajos del servidor
        echo        Para habilitar la recepcion de trabajos, instala Java y ejecuta nuevamente
        echo.
        set "SKIP_CLIENT=true"
    )
) else (
    echo [OK] Java esta instalado
    java -version 2>&1 | findstr /i "version"
    echo.
)

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
echo URL: http://%SERVER_IP%:%SERVER_PORT%/api/register-shared-printer
echo.

set "TEMP_RESPONSE=%TEMP%\printer_response.json"
set "TEMP_CURL_SCRIPT=%TEMP%\curl_request.ps1"

REM Crear script PowerShell para hacer la peticion HTTP
(
    echo $json = Get-Content '%TEMP_JSON%' -Raw
    echo try {
    echo     $response = Invoke-RestMethod -Uri 'http://%SERVER_IP%:%SERVER_PORT%/api/register-shared-printer' -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 15
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
    echo IPP_PORT=631
    echo REGISTERED_AT=%DATE% %TIME%
) > "%CONFIG_FILE%"

REM Descargar e iniciar cliente USB desde el servidor
if not "!SKIP_CLIENT!"=="true" (
    echo.
    echo Descargando cliente USB desde el servidor...
    echo.
    
    set "CLIENT_JAR=%CONFIG_DIR%\usb-client.jar"
    set "CLIENT_URL=http://%SERVER_IP%:%SERVER_PORT%/api/download/usb-client"
    
    REM Intentar descargar el cliente
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -Uri '%CLIENT_URL%' -OutFile '%CLIENT_JAR%' -TimeoutSec 60; exit 0 } catch { Write-Host 'Error descargando cliente'; exit 1 }"
    
    if errorlevel 1 (
        echo [AVISO] No se pudo descargar el cliente automaticamente
        echo          Puedes descargarlo manualmente desde:
        echo          %CLIENT_URL%
        echo.
        echo [INFO] Continuando sin cliente automatico...
        echo        La impresora esta registrada pero no escuchara en puerto 631
        echo.
    ) else (
        echo [OK] Cliente USB descargado: %CLIENT_JAR%
        echo.
        
        REM Crear script para iniciar el cliente
        set "START_SCRIPT=%CONFIG_DIR%\start-client.bat"
        (
            echo @echo off
            echo title Cliente USB - !SELECTED_PRINTER!
            echo cd /d "%CONFIG_DIR%"
            echo java -jar usb-client.jar --spring.profiles.active=usb-client --app.server.ip=%SERVER_IP% --app.server.port=%SERVER_PORT%
            echo pause
        ) > "!START_SCRIPT!"
        
        REM Crear tarea programada para inicio automatico
        echo Configurando inicio automatico...
        schtasks /create /tn "PrinterShareClient" /tr "\"!START_SCRIPT!\"" /sc onlogon /rl highest /f >nul 2>&1
        
        if !errorlevel! equ 0 (
            echo [OK] Cliente configurado para inicio automatico
        ) else (
            echo [AVISO] No se pudo configurar inicio automatico
        )
        
        echo.
        echo Iniciando cliente USB en segundo plano...
        start "Cliente USB - !SELECTED_PRINTER!" /MIN cmd /c "!START_SCRIPT!"
        
        timeout /t 3 /nobreak >nul
        
        echo [OK] Cliente USB iniciado
        echo      Puerto de escucha: 631
        echo.
    )
) else (
    echo.
    echo [INFO] Cliente USB no se iniciara (Java no disponible)
    echo        Instala Java y ejecuta este script nuevamente para habilitar
    echo        la recepcion de trabajos de impresion desde el servidor
    echo.
)

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
echo      - Puerto IPP del servidor: %IPP_PORT%
echo      - Puerto IPP local (cliente): 631
echo.
echo   Conexion directa SMB (Windows):
echo      \\%HOSTNAME%\!SHARE_NAME!
echo.
echo   Cliente USB:
echo      - Ejecutandose en: %CONFIG_DIR%
 echo      - Puerto de escucha: 631
echo      - Logs: %CONFIG_DIR%\logs
echo.
echo   IMPORTANTE:
echo      - Esta computadora debe estar ENCENDIDA
echo      - La impresora debe estar CONECTADA
echo      - El cliente USB debe estar ejecutandose
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
