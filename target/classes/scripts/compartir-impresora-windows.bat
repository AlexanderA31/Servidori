@echo off
REM ====================================================================
REM COMPARTIR IMPRESORA USB CON SERVIDOR - VERSION COMPLETA
REM Version 8.0 - SIN instalacion automatica de Java (solo instrucciones)
REM ====================================================================

REM Asegurar que la terminal NO se cierre en caso de error
if "%1"=="" (
    cmd /k "%~f0 run"
    exit /b
)

if "%1"=="run" (
    shift
)

setlocal enabledelayedexpansion

REM ====================================================================
REM AUTO-ELEVACION A ADMINISTRADOR
REM ====================================================================
net session >nul 2>&1
if %errorLevel% neq 0 (
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

REM ====================================================================
REM CONFIGURACION
REM ====================================================================
set "SERVER_IP=10.1.16.31"
set "SERVER_PORT=8080"
set "LOG_FILE=%TEMP%\compartir-impresora.log"
set "CONFIG_DIR=%APPDATA%\PrinterShare"
set "CONFIG_FILE=%CONFIG_DIR%\config.txt"

title Compartir Impresora USB - %SERVER_IP%

REM Iniciar log
echo [%DATE% %TIME%] Iniciando script > "%LOG_FILE%" 2>&1

cls
echo.
echo ====================================================================
echo   COMPARTIR IMPRESORA USB CON SERVIDOR
echo ====================================================================
echo.
echo   Servidor: %SERVER_IP%:%SERVER_PORT%
echo.
echo ====================================================================
echo.

REM ====================================================================
REM OBTENER INFORMACION DEL SISTEMA
REM ====================================================================
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
    pause
    exit /b 1
)

echo Computadora: %HOSTNAME%
echo Usuario: %USERNAME%
echo IP: !IP_ADDRESS!
echo.

REM ====================================================================
REM DETECTAR IMPRESORAS USB/LOCALES
REM ====================================================================
echo Buscando impresoras USB/Locales.
echo.

set "PRINTER_COUNT=0"
set "TEMP_PRINTERS=%TEMP%\printers_%RANDOM%.txt"
set "TEMP_PS=%TEMP%\getprinters_%RANDOM%.ps1"

REM Crear script PowerShell
(
    echo Get-Printer ^| Where-Object {$_.Type -eq 'Local' -or $_.PortName -like 'USB*'} ^| ForEach-Object {Write-Output "$($_.Name)^|$($_.DriverName)"}
) > "%TEMP_PS%"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%TEMP_PS%" > "%TEMP_PRINTERS%" 2>&1

if not exist "%TEMP_PRINTERS%" (
    echo ERROR: No se pudieron listar las impresoras
    del "%TEMP_PS%" 2>nul
    pause
    exit /b 1
)

REM Verificar contenido
for %%A in ("%TEMP_PRINTERS%") do set "FILE_SIZE=%%~zA"
if %FILE_SIZE% EQU 0 (
    echo ERROR: No se encontraron impresoras USB/Locales
    echo.
    echo Verifica que:
    echo   1. Hay una impresora USB conectada
    echo   2. Los drivers estan instalados
    echo   3. Aparece en: Panel de Control ^> Dispositivos e Impresoras
    echo.
    del "%TEMP_PS%" 2>nul
    del "%TEMP_PRINTERS%" 2>nul
    pause
    exit /b 1
)

REM Mostrar impresoras
echo ====================================================================
echo   IMPRESORAS DISPONIBLES
echo ====================================================================
echo.

for /f "usebackq tokens=1,2 delims=|" %%a in ("%TEMP_PRINTERS%") do (
    set /a PRINTER_COUNT+=1
    set "PRINTER_NAME[!PRINTER_COUNT!]=%%a"
    set "PRINTER_DRIVER[!PRINTER_COUNT!]=%%b"
    echo [!PRINTER_COUNT!] %%a
    echo     Driver: %%b
    echo.
)

if %PRINTER_COUNT% EQU 0 (
    echo ERROR: No se pudieron procesar las impresoras
    del "%TEMP_PS%" 2>nul
    del "%TEMP_PRINTERS%" 2>nul
    pause
    exit /b 1
)

REM Seleccionar impresora
set /p "SELECTION=Selecciona el numero (1-%PRINTER_COUNT%): "

if "!SELECTION!"=="" (
    echo ERROR: No se selecciono ninguna impresora
    del "%TEMP_PS%" 2>nul
    del "%TEMP_PRINTERS%" 2>nul
    pause
    exit /b 1
)

if !SELECTION! LSS 1 (
    echo ERROR: Numero invalido
    del "%TEMP_PS%" 2>nul
    del "%TEMP_PRINTERS%" 2>nul
    pause
    exit /b 1
)
if !SELECTION! GTR %PRINTER_COUNT% (
    echo ERROR: Numero invalido
    del "%TEMP_PS%" 2>nul
    del "%TEMP_PRINTERS%" 2>nul
    pause
    exit /b 1
)

set "SELECTED_PRINTER=!PRINTER_NAME[%SELECTION%]!"
set "SELECTED_DRIVER=!PRINTER_DRIVER[%SELECTION%]!"

echo.
echo ====================================================================
echo   IMPRESORA SELECCIONADA
echo ====================================================================
echo.
echo   Nombre: !SELECTED_PRINTER!
echo   Driver: !SELECTED_DRIVER!
echo.

REM Limpiar archivos temporales
del "%TEMP_PS%" 2>nul
del "%TEMP_PRINTERS%" 2>nul

REM ====================================================================
REM REGISTRAR EN EL SERVIDOR
REM ====================================================================
echo Registrando en el servidor.
echo URL: http://%SERVER_IP%:%SERVER_PORT%/api/register-shared-printer
echo.

set "PRINTER_ALIAS=!SELECTED_PRINTER!_%HOSTNAME%"
set "TEMP_JSON=%TEMP%\printer_%RANDOM%.json"
set "TEMP_RESPONSE=%TEMP%\response_%RANDOM%.json"

REM Crear JSON
(
    echo {
    echo   "alias": "!PRINTER_ALIAS!",
    echo   "model": "!SELECTED_DRIVER!",
    echo   "ip": "!IP_ADDRESS!",
    echo   "location": "Compartida-USB - %HOSTNAME% - %USERNAME%",
    echo   "protocol": "IPP",
    echo   "port": 631
    echo }
) > "%TEMP_JSON%"

REM Enviar POST al servidor
powershell -NoProfile -ExecutionPolicy Bypass -Command "$json = Get-Content '%TEMP_JSON%' -Raw; try { $response = Invoke-RestMethod -Uri 'http://%SERVER_IP%:%SERVER_PORT%/api/register-shared-printer' -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 15; $response | ConvertTo-Json | Out-File '%TEMP_RESPONSE%'; if ($response.success -eq $true) { Write-Host '[OK] Impresora registrada exitosamente'; exit 0 } elseif ($response.error -like '*Ya existe*') { Write-Host '[INFO] Impresora ya estaba registrada'; exit 2 } else { Write-Host '[ERROR]' $response.error; exit 1 } } catch { Write-Host '[ERROR]' $_.Exception.Message; exit 1 }"

if errorlevel 2 (
    echo.
    echo [INFO] La impresora ya estaba registrada en el servidor
    echo        Se usara la configuracion existente
    echo.
) else if errorlevel 1 (
    echo.
    echo [AVISO] No se pudo registrar en el servidor
    echo          Verifica que el servidor este accesible en %SERVER_IP%:%SERVER_PORT%
    echo.
) else (
    echo [OK] Impresora registrada en el servidor
    echo.
)

REM Limpiar archivos temporales
del "%TEMP_JSON%" 2>nul
del "%TEMP_RESPONSE%" 2>nul

REM ====================================================================
REM COMPARTIR IMPRESORA VIA SMB (WINDOWS)
REM ====================================================================
echo Compartiendo impresora via SMB.

REM Crear nombre compartido limpio
set "SHARE_NAME=!SELECTED_PRINTER!"
set "SHARE_NAME=!SHARE_NAME: =_!"
set "SHARE_NAME=!SHARE_NAME:-=_!"
set "SHARE_NAME=!SHARE_NAME:(=!"
set "SHARE_NAME=!SHARE_NAME:)=!"

REM Habilitar comparticion en firewall
netsh advfirewall firewall set rule group="Compartir archivos e impresoras" new enable=Yes >nul 2>&1

REM Compartir impresora
powershell.exe -Command "Set-Printer -Name '!SELECTED_PRINTER!' -Shared $true -ShareName '!SHARE_NAME!' -ErrorAction SilentlyContinue" >nul 2>&1

if errorlevel 1 (
    echo [AVISO] No se pudo compartir via SMB
) else (
    echo [OK] Compartida via SMB: \\%HOSTNAME%\!SHARE_NAME!
)
echo.

REM ====================================================================
REM CONFIGURAR FIREWALL PARA IPP
REM ====================================================================
echo Configurando firewall para puerto 631.

netsh advfirewall firewall delete rule name="PrinterShare_IPP_631" >nul 2>&1
netsh advfirewall firewall add rule name="PrinterShare_IPP_631" dir=in action=allow protocol=TCP localport=631 profile=any >nul 2>&1

if errorlevel 1 (
    echo [AVISO] No se pudo configurar firewall
) else (
    echo [OK] Firewall configurado para puerto 631
)
echo.

REM ====================================================================
REM VERIFICAR JAVA
REM ====================================================================
echo ====================================================================
echo   VERIFICANDO JAVA
echo ====================================================================
echo.

java -version >nul 2>&1
if errorlevel 1 (
    echo [X] Java NO esta instalado
    echo.
    echo Java es OBLIGATORIO para que esta PC reciba trabajos de impresion
    echo del servidor y los envie a la impresora USB.
    echo.
    echo ====================================================================
    echo   COMO INSTALAR JAVA (MANUAL)
    echo ====================================================================
    echo.
    echo OPCION 1: Instalacion Rapida con Winget ^(Recomendado^)
    echo   1. Abre PowerShell como Administrador
    echo   2. Ejecuta:
    echo      winget install EclipseAdoptium.Temurin.17.JRE
    echo.
    echo OPCION 2: Descarga Manual
    echo   1. Ve a: https://www.java.com/es/download/
    echo   2. Descarga e instala Java
    echo   3. Reinicia esta PC
    echo.
    echo OPCION 3: Descarga Directa Microsoft JDK
    echo   1. Ve a: https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.msi
    echo   2. Descarga e instala el archivo MSI
    echo   3. Reinicia esta PC
    echo.
    echo ====================================================================
    echo.
    echo El script continuara y descargara el cliente USB.
    echo Pero NO funcionara hasta que instales Java.
    echo.
    echo Despues de instalar Java, ejecuta:
    echo   %APPDATA%\PrinterShare\start-client.bat
    echo.
    set "SKIP_CLIENT=true"
    pause
) else (
    echo [OK] Java ya esta instalado
    java -version 2>&1 | findstr /i "version"
    echo.
)

REM ====================================================================
REM CREAR DIRECTORIO Y SCRIPT DE INICIO
REM ====================================================================
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%"

set "START_SCRIPT=%CONFIG_DIR%\start-client.bat"

echo Creando script de inicio del cliente USB.

(
    echo @echo off
    echo title Cliente USB - !SELECTED_PRINTER!
    echo.
    echo REM Verificar Java
    echo java -version ^>nul 2^>^&1
    echo if errorlevel 1 (
    echo     echo [ERROR] Java no esta instalado
    echo     echo Instala Java desde: https://www.java.com/es/download/
    echo     pause
    echo     exit /b 1
    echo ^)
    echo.
    echo cd /d "%CONFIG_DIR%"
    echo.
    echo echo ====================================================================
    echo echo   CLIENTE USB - COMPARTIR IMPRESORA
    echo echo ====================================================================
    echo echo.
    echo echo Impresora: !SELECTED_PRINTER!
    echo echo Servidor: %SERVER_IP%:%SERVER_PORT%
    echo echo Puerto: 631
    echo echo.
    echo echo Escuchando trabajos de impresion.
    echo echo [INFO] NO cierres esta ventana - minimizala
    echo echo.
    echo.
    echo java -jar usb-client.jar --spring.profiles.active=usb-client --app.server.ip=%SERVER_IP% --app.server.port=%SERVER_PORT% --app.mode=usb-client --server.port=631
    echo.
    echo echo.
    echo echo Cliente detenido.
    echo pause
) > "%START_SCRIPT%"

echo [OK] Script creado: %START_SCRIPT%
echo.

REM ====================================================================
REM DESCARGAR CLIENTE USB (JAR) - SIEMPRE
REM ====================================================================
echo ====================================================================
echo   DESCARGANDO CLIENTE USB
echo ====================================================================
echo.

set "CLIENT_JAR=%CONFIG_DIR%\usb-client.jar"
set "CLIENT_URL=http://%SERVER_IP%:%SERVER_PORT%/api/download/usb-client"

echo Descargando cliente USB desde el servidor.
echo URL: %CLIENT_URL%
echo Destino: %CLIENT_JAR%
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Write-Host 'Descargando...' -ForegroundColor Yellow; Invoke-WebRequest -Uri '%CLIENT_URL%' -OutFile '%CLIENT_JAR%' -TimeoutSec 60; Write-Host '[OK] Descarga completada' -ForegroundColor Green; exit 0 } catch { Write-Host '[ERROR]' $_.Exception.Message -ForegroundColor Red; exit 1 }"

if errorlevel 1 (
    echo.
    echo [ERROR] No se pudo descargar el cliente JAR
    echo.
    echo POSIBLES CAUSAS:
    echo   1. El servidor no esta ejecutandose
    echo   2. No hay conexion de red
    echo   3. El archivo no existe en el servidor
    echo.
    echo SOLUCION:
    echo   Descarga manualmente desde: %CLIENT_URL%
    echo   Y guardalo en: %CLIENT_JAR%
    echo.
    set "CLIENT_DOWNLOADED=false"
) else (
    echo [OK] Cliente JAR descargado exitosamente
    echo.
    set "CLIENT_DOWNLOADED=true"
)

REM ====================================================================
REM CONFIGURAR INICIO AUTOMATICO
REM ====================================================================
if "!CLIENT_DOWNLOADED!"=="true" (
    echo Configurando inicio automatico.
    schtasks /create /tn "PrinterShareClient" /tr "\"%START_SCRIPT%\"" /sc onlogon /rl highest /f >nul 2>&1
    
    if errorlevel 1 (
        echo [AVISO] No se pudo configurar inicio automatico
        echo          Ejecuta manualmente: %START_SCRIPT%
    ) else (
        echo [OK] Inicio automatico configurado
    )
    echo.
)

REM ====================================================================
REM INICIAR CLIENTE USB (si Java esta disponible y JAR descargado)
REM ====================================================================
if "!CLIENT_DOWNLOADED!"=="true" (
    if "!SKIP_CLIENT!"=="true" (
        echo [INFO] Cliente JAR descargado pero Java no esta instalado
        echo        Instala Java y ejecuta: %START_SCRIPT%
        echo.
    ) else (
        echo Iniciando cliente USB.
        echo.
        
        start "Cliente USB - !SELECTED_PRINTER!" /MIN cmd /c "%START_SCRIPT%"
        
        timeout /t 3 /nobreak >nul
        
        echo [OK] Cliente USB iniciado (ventana minimizada)
        echo      Busca la ventana en la barra de tareas
        echo.
        
        REM Verificar que el proceso arranco
        timeout /t 2 /nobreak >nul
        tasklist /FI "IMAGENAME eq java.exe" 2>nul | find /I "java.exe" >nul
        if errorlevel 1 (
            echo [AVISO] No se detecto el proceso Java
            echo          Verifica que Java este instalado correctamente
            echo          Ejecuta manualmente: %START_SCRIPT%
            echo.
        ) else (
            echo [OK] Proceso Java detectado - Cliente funcionando
            echo.
        )
    )
)

REM ====================================================================
REM GUARDAR CONFIGURACION
REM ====================================================================
(
    echo PRINTER_NAME=!SELECTED_PRINTER!
    echo SERVER_IP=%SERVER_IP%
    echo SERVER_PORT=%SERVER_PORT%
    echo IPP_PORT=631
    echo REGISTERED_AT=%DATE% %TIME%
) > "%CONFIG_FILE%"

REM ====================================================================
REM RESUMEN FINAL
REM ====================================================================
cls
echo.
echo ====================================================================
echo   CONFIGURACION COMPLETADA
echo ====================================================================
echo.
echo Impresora: !SELECTED_PRINTER!
echo Computadora: %HOSTNAME%
echo IP: !IP_ADDRESS!
echo.
echo ====================================================================
echo   IMPORTANTE
echo ====================================================================
echo.
echo Para que otras computadoras puedan imprimir:
echo.
echo   [!] Esta PC debe estar ENCENDIDA
echo   [!] La impresora debe estar CONECTADA
echo   [!] El cliente USB debe estar ejecutandose
echo.
if "!SKIP_CLIENT!"=="true" (
    echo   [PENDIENTE] Instala Java y ejecuta:
    echo               %START_SCRIPT%
) else (
    echo   [OK] Cliente USB ejecutandose (ventana minimizada)
    echo.
    echo   Si se cierra, reinicialo con:
    echo   %START_SCRIPT%
)
echo.
echo ====================================================================
echo   COMO INSTALAR EN OTRAS COMPUTADORAS
echo ====================================================================
echo.
echo 1. Ve a: http://%SERVER_IP%:%SERVER_PORT%
echo 2. Busca la impresora "!PRINTER_ALIAS!"
echo 3. Descarga el instalador y ejecutalo
echo.
echo ====================================================================
echo.
echo Archivos: %CONFIG_DIR%
echo Log: %LOG_FILE%
echo.
echo ====================================================================
echo.
pause

exit /b 0
