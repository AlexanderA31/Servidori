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
    echo ====================================================================
    echo   PERMISOS DE ADMINISTRADOR REQUERIDOS
    echo ====================================================================
    echo.
    echo Este script necesita permisos de administrador para:
    echo   - Configurar comparticion de impresoras
    echo   - Crear reglas de firewall
    echo   - Registrar tareas programadas
    echo.
    echo Por favor, ejecuta este script como Administrador:
    echo   1. Click derecho en el archivo .bat
    echo   2. Selecciona "Ejecutar como administrador"
    echo.
    echo ====================================================================
    echo.
    pause
    exit /b 1
)

title Compartir Impresora USB/Local - Servidor %SERVER_IP%

REM Inicializar log
echo [%DATE% %TIME%] Iniciando script de comparticion de impresora > "%LOG_FILE%"
echo [%DATE% %TIME%] Servidor: %SERVER_IP%:%SERVER_PORT% >> "%LOG_FILE%"

cls
echo.
echo ====================================================================
echo   COMPARTIR IMPRESORA USB/LOCAL CON EL SERVIDOR
echo ====================================================================
echo.
echo   Servidor: %SERVER_IP%:%SERVER_PORT%
echo   Log: %LOG_FILE%
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
(echo Get-Printer ^| Where-Object {$_.Type -eq 'Local' -or $_.PortName -like 'USB*' -or $_.PortName -like 'LPT*' -or $_.PortName -like 'COM*'} ^| ForEach-Object {Write-Output "$($_.Name)^|$($_.PortName)^|$($_.DriverName)"}) > "%TEMP_PS_SCRIPT%"

REM Ejecutar PowerShell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%TEMP_PS_SCRIPT%" > "%TEMP_PRINTERS%" 2>&1

if not exist "%TEMP_PRINTERS%" (
    echo.
    echo ERROR: No se pudieron listar las impresoras
    echo El comando PowerShell fallo
    echo.
    echo Intentando metodo alternativo...
    wmic printer get name,portname,drivername /format:csv > "%TEMP_PRINTERS%" 2>&1
    
    if not exist "%TEMP_PRINTERS%" (
        echo ERROR: Tampoco funciono el metodo alternativo
        goto :error_exit
    )
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

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%TEMP_CURL_SCRIPT%" 2>&1

set "SERVER_REGISTERED=false"
if errorlevel 1 (
    echo.
    echo [AVISO] No se pudo conectar al servidor
    echo.
    echo POSIBLES CAUSAS:
    echo   1. El servidor no esta accesible en %SERVER_IP%:%SERVER_PORT%
    echo   2. Firewall bloqueando la conexion
    echo   3. Servidor de impresoras no esta ejecutandose
    echo.
    echo Verifica que el servidor este ejecutandose y accesible.
    echo.
    set /p "CONTINUE_ANYWAY=Deseas continuar con la configuracion local? (S/N): "
    
    if /i "!CONTINUE_ANYWAY!"=="N" (
        goto :error_exit
    )
    
    echo.
    echo [INFO] Continuando con configuracion local solamente...
    echo [INFO] La impresora no sera registrada en el servidor.
    echo.
    set "IPP_PORT=631"
) else (
    set "SERVER_REGISTERED=true"
    
    REM Leer la respuesta del servidor
    if exist "%TEMP_RESPONSE%" (
        echo.
        echo [OK] Respuesta del servidor recibida
        
        REM Verificar si el registro fue exitoso
        findstr /i "success" "%TEMP_RESPONSE%" >nul 2>&1
        if !errorlevel! equ 0 (
            echo [OK] Impresora registrada exitosamente en el servidor
            
            REM Extraer puerto IPP asignado
            set "IPP_PORT=631"
            for /f "tokens=2 delims=:, " %%a in ('findstr /i "ippPort" "%TEMP_RESPONSE%" 2^>nul') do (
                set "IPP_PORT=%%a"
            )
            echo [OK] Puerto IPP asignado: !IPP_PORT!
        ) else (
            echo [ERROR] El servidor rechazo el registro
            echo.
            type "%TEMP_RESPONSE%"
            echo.
            set "SERVER_REGISTERED=false"
            set "IPP_PORT=631"
        )
    ) else (
        echo [AVISO] No se recibio respuesta del servidor
        set "SERVER_REGISTERED=false"
        set "IPP_PORT=631"
    )
)
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

REM ====================================================================
REM Compartir impresora via SMB (para acceso directo desde otras PCs)
REM ====================================================================
echo.
echo Configurando comparticion de impresora via SMB...
echo.

REM Habilitar comparticion de archivos e impresoras en el firewall
netsh advfirewall firewall set rule group="Compartir archivos e impresoras" new enable=Yes >nul 2>&1

REM Compartir la impresora usando PowerShell
echo Compartiendo impresora: !SELECTED_PRINTER!
echo Nombre compartido: !SHARE_NAME!
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { Set-Printer -Name '!SELECTED_PRINTER!' -Shared $true -ShareName '!SHARE_NAME!' -ErrorAction Stop; Write-Host '[OK] Impresora compartida exitosamente'; exit 0 } catch { Write-Host '[ERROR]' $_.Exception.Message; exit 1 }"

if %errorlevel% equ 0 (
    echo [OK] Impresora compartida via SMB: \\%HOSTNAME%\!SHARE_NAME!
    echo.
    
    REM Verificar que la comparticion funciona
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-Printer -Name '!SELECTED_PRINTER!' -ErrorAction SilentlyContinue; if ($p -and $p.Shared) { Write-Host '[OK] Comparticion verificada correctamente'; exit 0 } else { Write-Host '[AVISO] Comparticion no verificada'; exit 1 }"
) else (
    echo [AVISO] No se pudo compartir la impresora via SMB
    echo          La impresora aun puede funcionar via el servidor IPP
    echo          Para compartir manualmente:
    echo            1. Panel de Control ^> Dispositivos e Impresoras
    echo            2. Click derecho en "!SELECTED_PRINTER!"
    echo            3. Propiedades ^> Compartir ^> Compartir esta impresora
    echo            4. Nombre: !SHARE_NAME!
)

echo.

echo.
echo.
REM ====================================================================
REM Configurar firewall para permitir conexiones IPP
REM ====================================================================
echo.
echo Configurando firewall para permitir conexiones IPP...
echo Puerto: %IPP_PORT%
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

REM ====================================================================
REM Resumen final y instrucciones
REM ====================================================================
cls
echo.
echo ====================================================================
echo   CONFIGURACION COMPLETADA EXITOSAMENTE
echo ====================================================================
echo.
echo Impresora: !SELECTED_PRINTER!
echo Computadora: %HOSTNAME%
echo IP: !IP_ADDRESS!
echo.
if "!SERVER_REGISTERED!"=="true" (
    echo [OK] Impresora registrada en el servidor central
    echo      URL: http://%SERVER_IP%:%SERVER_PORT%
) else (
    echo [INFO] Impresora NO registrada en servidor (solo local)
)
echo.
echo ====================================================================
echo   COMO USAR ESTA IMPRESORA DESDE OTRAS COMPUTADORAS
echo ====================================================================
echo.
if "!SERVER_REGISTERED!"=="true" (
    echo METODO 1 - A traves del Servidor de Impresoras (RECOMENDADO):
    echo.
    echo   1. Abre un navegador en la otra PC
    echo   2. Ve a: http://%SERVER_IP%:%SERVER_PORT%
    echo   3. La impresora "!PRINTER_ALIAS!" aparecera en la lista
    echo   4. Sigue las instrucciones para instalarla
    echo.
    echo   Panel de administracion:
    echo      http://%SERVER_IP%:%SERVER_PORT%/admin/printers
    echo.
)
echo METODO 2 - Conexion directa SMB (solo Windows):
echo.
echo   1. En la otra PC, abre "Ejecutar" (Win + R)
echo   2. Escribe: \\%HOSTNAME%\!SHARE_NAME!
echo   3. Click derecho ^> "Conectar" o "Instalar"
echo.
echo ====================================================================
echo   IMPORTANTE - MANTENER ACTIVO
echo ====================================================================
echo.
echo Para que otras computadoras puedan imprimir:
echo.
echo   [!] Esta computadora debe estar ENCENDIDA
echo   [!] La impresora debe estar CONECTADA y ENCENDIDA
if not "!SKIP_CLIENT!"=="true" (
    echo   [!] El cliente USB debe estar ejecutandose
    echo.
    echo       Si el cliente se detiene, reinicialo con:
    echo       %CONFIG_DIR%\start-client.bat
)
echo   [!] NO suspender esta computadora
echo.
echo ====================================================================
echo.
echo Archivos guardados en:
echo   Configuracion: %CONFIG_FILE%
if not "!SKIP_CLIENT!"=="true" (
    echo   Cliente USB: %CONFIG_DIR%\usb-client.jar
    echo   Script inicio: %CONFIG_DIR%\start-client.bat
)
echo   Log: %LOG_FILE%
echo.
echo ====================================================================
echo.
if "!SERVER_REGISTERED!"=="true" (
    echo Tu impresora ya esta disponible en:
    echo    http://%SERVER_IP%:%SERVER_PORT%
    echo.
)
echo Presiona cualquier tecla para cerrar...
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
echo Log guardado en: %LOG_FILE%
echo.
echo Si el problema persiste:
echo   1. Verifica que tengas permisos de administrador
echo   2. Asegurate de que haya una impresora USB conectada
echo   3. Verifica que el servidor este accesible en %SERVER_IP%:%SERVER_PORT%
echo   4. Revisa el firewall de Windows
echo.
echo ====================================================================
echo.
pause
exit /b 1
