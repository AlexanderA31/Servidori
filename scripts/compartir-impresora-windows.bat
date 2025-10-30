@echo off
REM ====================================================================
REM Script COMPLETO para Compartir Impresora USB/Local
REM Version 5.0 - Todo en uno
REM ====================================================================
REM Funciones:
REM   1. Compartir nueva impresora
REM   2. Limpiar configuracion anterior
REM   3. Verificar estado del cliente
REM   4. Desinstalar completamente
REM ====================================================================

REM NO cerrar la ventana automaticamente en caso de error
setlocal enabledelayedexpansion

REM Si se pasa parametro, ejecutar funcion especifica
if "%1"=="clean" goto :menu_clean
if "%1"=="status" goto :show_status
if "%1"=="restart" goto :restart_client
if "%1"=="uninstall" goto :uninstall_all

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
echo [%DATE% %TIME%] Iniciando script de comparticion de impresora > "%LOG_FILE%" 2>&1
if errorlevel 1 (
    echo [ERROR] No se pudo crear el archivo de log
    set "LOG_FILE=%TEMP%\compartir-impresora-debug.log"
    echo [%DATE% %TIME%] Iniciando script de comparticion de impresora > "%LOG_FILE%" 2>&1
)
echo [%DATE% %TIME%] Servidor: %SERVER_IP%:%SERVER_PORT% >> "%LOG_FILE%" 2>&1

cls
echo.
echo ====================================================================
echo   GESTOR DE IMPRESORAS USB COMPARTIDAS
echo ====================================================================
echo.
echo   Servidor: %SERVER_IP%:%SERVER_PORT%
echo   Log: %LOG_FILE%
echo.
echo ====================================================================
echo.

REM ====================================================================
REM Menu principal si hay configuracion previa
REM ====================================================================
if exist "%CONFIG_FILE%" (
    echo.
    echo [!] CONFIGURACION EXISTENTE DETECTADA
    echo.
    
    REM Leer impresora actual
    set "CURRENT_PRINTER=Desconocida"
    for /f "tokens=1,2 delims==" %%a in (%CONFIG_FILE%) do (
        if "%%a"=="PRINTER_NAME" set "CURRENT_PRINTER=%%b"
    )
    
    echo Impresora actual: !CURRENT_PRINTER!
    echo.
    echo Que deseas hacer?
    echo.
    echo   [1] Compartir NUEVA impresora (elimina la anterior)
    echo   [2] Ver ESTADO del cliente actual
    echo   [3] REINICIAR cliente actual
    echo   [4] LIMPIAR configuracion y salir
    echo   [5] DESINSTALAR completamente
    echo   [6] SALIR sin cambios
    echo.
    set /p "MENU_OPTION=Selecciona una opcion (1-6): "
    
    if "!MENU_OPTION!"=="1" (
        echo.
        echo Preparando para compartir nueva impresora...
        call :cleanup_old_config
        echo.
        goto :start_sharing
    )
    if "!MENU_OPTION!"=="2" goto :show_status
    if "!MENU_OPTION!"=="3" goto :restart_client
    if "!MENU_OPTION!"=="4" goto :menu_clean
    if "!MENU_OPTION!"=="5" goto :uninstall_all
    if "!MENU_OPTION!"=="6" (
        echo.
        echo Saliendo sin cambios...
        pause
        exit /b 0
    )
    
    echo.
    echo [ERROR] Opcion invalida
    pause
    exit /b 1
)

:start_sharing
echo.
echo ====================================================================
echo   COMPARTIR NUEVA IMPRESORA USB
echo ====================================================================
echo.
echo Presiona cualquier tecla para continuar...
pause >nul
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
echo Creando script de deteccion...
(
    echo Get-Printer ^| Where-Object {$_.Type -eq 'Local' -or $_.PortName -like 'USB*' -or $_.PortName -like 'LPT*' -or $_.PortName -like 'COM*'} ^| ForEach-Object {Write-Output "$($_.Name)^|$($_.PortName)^|$($_.DriverName)"}
) > "%TEMP_PS_SCRIPT%" 2>&1

if not exist "%TEMP_PS_SCRIPT%" (
    echo [ERROR] No se pudo crear el script de PowerShell
    echo Intentando con permisos elevados...
    pause
    goto :error_exit
)

echo [OK] Script de deteccion creado

REM Ejecutar PowerShell
echo Ejecutando PowerShell para detectar impresoras...
echo Esto puede tardar unos segundos...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%TEMP_PS_SCRIPT%" > "%TEMP_PRINTERS%" 2>&1

if errorlevel 1 (
    echo [AVISO] PowerShell retorno un error: %errorlevel%
    echo Continuando de todos modos...
)

if not exist "%TEMP_PRINTERS%" (
    echo.
    echo [ERROR] No se pudieron listar las impresoras
    echo El comando PowerShell fallo
    echo.
    echo [INFO] Intentando metodo alternativo con WMIC...
    wmic printer get name,portname,drivername /format:csv > "%TEMP_PRINTERS%" 2>&1
    
    if not exist "%TEMP_PRINTERS%" (
        echo [ERROR] Tampoco funciono el metodo alternativo
        echo.
        echo Posibles causas:
        echo   1. No hay impresoras instaladas
        echo   2. Los servicios de impresion de Windows no estan activos
        echo   3. Problema con PowerShell
        echo.
        echo Verifica:
        echo   - Panel de Control ^> Dispositivos e Impresoras
        echo   - Que haya al menos una impresora instalada
        echo.
        goto :error_exit
    ) else (
        echo [OK] Metodo alternativo funciono
    )
) else (
    echo [OK] Impresoras detectadas con PowerShell
)

REM Verificar si el archivo tiene contenido
echo Verificando contenido del archivo...
for %%A in ("%TEMP_PRINTERS%") do set "FILE_SIZE=%%~zA"
echo Tamano del archivo: %FILE_SIZE% bytes

if %FILE_SIZE% EQU 0 (
    echo.
    echo [ERROR] No se encontraron impresoras USB/Locales
    echo.
    echo Asegurate de que:
    echo   1. Haya una impresora USB conectada
    echo   2. Los drivers esten instalados
    echo   3. La impresora aparezca en "Dispositivos e Impresoras"
    echo.
    echo Abre "Dispositivos e Impresoras" y verifica:
    echo   Win + R  ^>  control printers
    echo.
    goto :error_exit
) else (
    echo [OK] Archivo con datos: %FILE_SIZE% bytes
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
    
    REM Log para debug
    echo [%DATE% %TIME%] Impresora detectada: %%a (Puerto: %%b) >> "%LOG_FILE%"
)

if %PRINTER_COUNT% EQU 0 (
    echo.
    echo [ERROR] No se pudieron procesar las impresoras
    echo.
    echo El archivo contiene datos pero no se pudieron parsear.
    echo Mostrando contenido del archivo:
    echo.
    type "%TEMP_PRINTERS%"
    echo.
    goto :error_exit
) else (
    echo.
    echo [OK] %PRINTER_COUNT% impresora(s) detectada(s)
    echo.
)

REM Seleccionar impresora
echo.
echo Presiona una tecla para continuar con la seleccion...
pause >nul
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

REM Verificar que se obtuvieron los valores
if "!SELECTED_PRINTER!"=="" (
    echo [ERROR] No se pudo obtener el nombre de la impresora
    goto :error_exit
)

echo.
echo ====================================================================
echo   IMPRESORA SELECCIONADA
echo ====================================================================
echo.
echo   Nombre: !SELECTED_PRINTER!
echo   Puerto: !SELECTED_PORT!
echo   Driver: !SELECTED_DRIVER!
echo.
echo [%DATE% %TIME%] Impresora seleccionada: !SELECTED_PRINTER! >> "%LOG_FILE%"
echo.

REM Crear nombre de alias para la impresora
set "PRINTER_ALIAS=!SELECTED_PRINTER!_%HOSTNAME%"

REM Verificar conectividad con el servidor
echo Verificando conectividad con el servidor...
echo URL: http://%SERVER_IP%:%SERVER_PORT%
echo.

set "SERVER_ACCESSIBLE=false"
ping -n 1 -w 1000 %SERVER_IP% >nul 2>&1
if !errorlevel! equ 0 (
    echo [OK] Servidor accesible: %SERVER_IP%
    set "SERVER_ACCESSIBLE=true"
) else (
    echo [AVISO] No se puede hacer ping al servidor: %SERVER_IP%
    echo.
    echo POSIBLES CAUSAS:
    echo   1. El servidor no esta ejecutandose
    echo   2. Firewall bloqueando ICMP
    echo   3. Red no configurada correctamente
    echo.
    set /p "CONTINUE_ANYWAY=Deseas continuar de todos modos? (S/N): "
    
    if /i "!CONTINUE_ANYWAY!"=="N" (
        goto :error_exit
    )
    
    echo.
    echo [INFO] Continuando sin verificacion de servidor...
)

echo.
echo [INFO] El cliente USB se registrara automaticamente al iniciar
echo [INFO] No es necesario registro manual previo
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

REM Puerto fijo para cliente USB
set "IPP_PORT=631"
echo Puerto: %IPP_PORT%
echo.

set "RULE_NAME=PrinterShare_IPP_631"

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
            echo echo.
            echo echo ====================================================================
            echo echo   CLIENTE USB - COMPARTIR IMPRESORA
            echo echo ====================================================================
            echo echo.
            echo echo Impresora: !SELECTED_PRINTER!
            echo echo Servidor: %SERVER_IP%:%SERVER_PORT%
            echo echo.
            echo echo Escuchando en puerto 631...
            echo echo.
            echo cd /d "%CONFIG_DIR%"
            echo java -jar usb-client.jar --spring.profiles.active=usb-client --app.server.ip=%SERVER_IP% --app.server.port=%SERVER_PORT%
            echo echo.
            echo echo Cliente USB detenido.
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
del "%TEMP_PS_SCRIPT%" 2>nul

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
if "!SERVER_ACCESSIBLE!"=="true" (
    echo [INFO] El cliente USB se registrara automaticamente en el servidor
    echo      URL: http://%SERVER_IP%:%SERVER_PORT%
) else (
    echo [AVISO] Servidor no accesible - verifica la conectividad
)
echo.
echo ====================================================================
echo   COMO USAR ESTA IMPRESORA DESDE OTRAS COMPUTADORAS
echo ====================================================================
echo.
if "!SERVER_ACCESSIBLE!"=="true" (
    echo METODO 1 - A traves del Servidor de Impresoras (RECOMENDADO):
    echo.
    echo   1. Espera 10 segundos a que el cliente USB se registre
    echo   2. Abre un navegador en otra PC
    echo   3. Ve a: http://%SERVER_IP%:%SERVER_PORT%
    echo   4. La impresora "!PRINTER_ALIAS!" aparecera en la lista
    echo   5. Sigue las instrucciones para instalarla
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
if "!SERVER_ACCESSIBLE!"=="true" (
    echo Verifica que la impresora aparezca en:
    echo    http://%SERVER_IP%:%SERVER_PORT%/admin/printers
    echo.
    echo Si no aparece despues de 30 segundos:
    echo    1. Revisa la ventana del Cliente USB
    echo    2. Verifica que no haya errores
    echo    3. Reinicia el cliente si es necesario
    echo.
)
echo.
echo ====================================================================
echo   OPCIONES ADICIONALES
echo ====================================================================
echo.
echo Para gestionar esta impresora en el futuro:
echo.
echo   Ver estado:     %~nx0 status
echo   Reiniciar:      %~nx0 restart
echo   Limpiar:        %~nx0 clean
echo   Desinstalar:    %~nx0 uninstall
echo.
echo Log completo guardado en:
echo    %LOG_FILE%
echo.
echo Para ver el log:
echo    notepad "%LOG_FILE%"
echo.
echo ====================================================================
echo.
echo Presiona cualquier tecla para cerrar...
pause >nul

REM Log final
echo [%DATE% %TIME%] Script completado exitosamente >> "%LOG_FILE%"

exit /b 0

REM ====================================================================
REM FUNCIONES AUXILIARES
REM ====================================================================

:cleanup_old_config
REM Funcion para limpiar configuracion anterior
echo.
echo ====================================================================
echo   LIMPIANDO CONFIGURACION ANTERIOR
echo ====================================================================
echo.
echo   Deteniendo cliente USB anterior...

REM 1. Detener tarea programada
schtasks /end /tn "PrinterShareClient" >nul 2>&1
schtasks /delete /tn "PrinterShareClient" /f >nul 2>&1
if !errorlevel! equ 0 (
    echo   [OK] Tarea programada eliminada
) else (
    echo   [INFO] No habia tarea programada
)

REM 2. Cerrar proceso Java del cliente USB si esta corriendo
taskkill /FI "WINDOWTITLE eq Cliente USB*" /F >nul 2>&1
if !errorlevel! equ 0 (
    echo   [OK] Cliente USB detenido
) else (
    echo   [INFO] Cliente USB no estaba ejecutandose
)

REM 3. Esperar un momento para que se liberen los archivos
timeout /t 2 /nobreak >nul

REM 4. Leer configuracion anterior para descompartir impresora
if exist "%CONFIG_FILE%" (
    for /f "tokens=1,2 delims==" %%a in (%CONFIG_FILE%) do (
        if "%%a"=="PRINTER_NAME" set "OLD_PRINTER=%%b"
    )
    
    if not "!OLD_PRINTER!"=="" (
        echo   [INFO] Descompartiendo impresora anterior: !OLD_PRINTER!
        powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { Set-Printer -Name '!OLD_PRINTER!' -Shared $false -ErrorAction SilentlyContinue } catch { }" >nul 2>&1
        echo   [OK] Impresora descompartida
    )
)

REM 5. Eliminar archivos de configuracion
if exist "%CONFIG_DIR%" (
    echo   [INFO] Eliminando directorio: %CONFIG_DIR%
    rmdir /s /q "%CONFIG_DIR%" >nul 2>&1
    if exist "%CONFIG_DIR%" (
        echo   [AVISO] No se pudo eliminar completamente el directorio
        echo            Algunos archivos pueden estar en uso
        del /f /q "%CONFIG_DIR%\*.*" >nul 2>&1
    ) else (
        echo   [OK] Directorio eliminado
    )
)

REM 6. Eliminar reglas de firewall antiguas
for /l %%i in (631,1,640) do (
    netsh advfirewall firewall delete rule name="PrinterShare_IPP_%%i" >nul 2>&1
)
echo   [OK] Reglas de firewall antiguas eliminadas

echo.
echo   [OK] Limpieza completada
echo.

REM Recrear directorio limpio
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%" >nul 2>&1
goto :eof

:show_status
REM Mostrar estado del cliente actual
cls
echo.
echo ====================================================================
echo   ESTADO DEL CLIENTE USB
echo ====================================================================
echo.

if not exist "%CONFIG_FILE%" (
    echo [INFO] No hay configuracion instalada
    echo.
    echo Para instalar, ejecuta el script sin parametros
    echo.
    pause
    exit /b 0
)

echo [OK] Configuracion encontrada
echo.
echo Detalles:
echo ----------------------------------------
type "%CONFIG_FILE%"
echo ----------------------------------------
echo.

REM Verificar tarea programada
echo Verificando tarea programada...
schtasks /query /tn "PrinterShareClient" >nul 2>&1
if !errorlevel! equ 0 (
    echo [OK] Tarea programada: ACTIVA
) else (
    echo [X] Tarea programada: NO ENCONTRADA
)

REM Verificar proceso Java
echo.
echo Verificando proceso del cliente...
tasklist /FI "WINDOWTITLE eq Cliente USB*" 2>nul | find "java" >nul
if !errorlevel! equ 0 (
    echo [OK] Cliente USB: EJECUTANDOSE
) else (
    echo [X] Cliente USB: NO ESTA CORRIENDO
    echo.
    echo Para iniciarlo manualmente:
    echo    %CONFIG_DIR%\start-client.bat
)

REM Verificar firewall
echo.
echo Verificando firewall...
netsh advfirewall firewall show rule name="PrinterShare_IPP_631" >nul 2>&1
if !errorlevel! equ 0 (
    echo [OK] Firewall: REGLA ACTIVA (puerto 631)
) else (
    echo [X] Firewall: REGLA NO ENCONTRADA
)

REM Verificar archivos
echo.
echo Verificando archivos...
if exist "%CONFIG_DIR%\usb-client.jar" (
    echo [OK] Cliente JAR: PRESENTE
) else (
    echo [X] Cliente JAR: NO ENCONTRADO
)

if exist "%CONFIG_DIR%\start-client.bat" (
    echo [OK] Script de inicio: PRESENTE
) else (
    echo [X] Script de inicio: NO ENCONTRADO
)

echo.
echo ====================================================================
echo.
pause
exit /b 0

:restart_client
REM Reiniciar el cliente USB
echo.
echo ====================================================================
echo   REINICIANDO CLIENTE USB
echo ====================================================================
echo.

echo Deteniendo cliente...
taskkill /FI "WINDOWTITLE eq Cliente USB*" /F >nul 2>&1
timeout /t 2 /nobreak >nul

if not exist "%CONFIG_DIR%\start-client.bat" (
    echo [ERROR] No se encontro el script de inicio
    echo.
    pause
    exit /b 1
)

echo Iniciando cliente...
start "Cliente USB" /MIN cmd /c "%CONFIG_DIR%\start-client.bat"

timeout /t 2 /nobreak >nul

echo.
echo [OK] Cliente reiniciado
echo.
echo Verifica que aparezca una ventana minimizada
echo.
pause
exit /b 0

:menu_clean
REM Menu de limpieza
cls
echo.
echo ====================================================================
echo   LIMPIAR CONFIGURACION
echo ====================================================================
echo.
echo Esta opcion eliminara:
echo   - Cliente USB en ejecucion
echo   - Tarea programada de inicio automatico
echo   - Archivos de configuracion
echo   - Reglas de firewall
echo   - Comparticion de la impresora
echo.
echo La impresora NO sera desinstalada de Windows
echo.
set /p "CONFIRM_CLEAN=Estas seguro? (S/N): "

if /i "!CONFIRM_CLEAN!"=="S" (
    call :cleanup_old_config
    echo.
    echo [OK] Configuracion limpiada exitosamente
    echo.
    echo Para volver a compartir una impresora, ejecuta el script de nuevo
    echo.
) else (
    echo.
    echo Operacion cancelada
    echo.
)
pause
exit /b 0

:uninstall_all
REM Desinstalacion completa
cls
echo.
echo ====================================================================
echo   DESINSTALACION COMPLETA
echo ====================================================================
echo.
echo [!] ADVERTENCIA: Esto eliminara TODA la configuracion
echo.
echo Se eliminara:
echo   - Cliente USB
echo   - Tarea programada
echo   - Archivos de configuracion
echo   - Reglas de firewall
echo   - Comparticion de impresoras
echo   - Logs y archivos temporales
echo.
set /p "CONFIRM_UNINSTALL=Estas COMPLETAMENTE seguro? (S/N): "

if /i "!CONFIRM_UNINSTALL!"=="S" (
    echo.
    call :cleanup_old_config
    
    REM Eliminar logs
    del "%LOG_FILE%" 2>nul
    del "%TEMP%\compartir-impresora-debug.log" 2>nul
    
    echo.
    echo ====================================================================
    echo   DESINSTALACION COMPLETADA
    echo ====================================================================
    echo.
    echo El sistema ha sido limpiado completamente
    echo.
    echo Para volver a usar el sistema, ejecuta el script de nuevo
    echo.
) else (
    echo.
    echo Operacion cancelada
    echo.
)
pause
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
echo SOLUCION: Intenta limpiar la configuracion antigua:
echo   1. Ejecuta este script
echo   2. Elige opcion "1" para eliminar configuracion anterior
echo   3. Intenta de nuevo
echo.
echo O manualmente:
echo   1. Presiona Win + R
echo   2. Escribe: %%APPDATA%%\PrinterShare
echo   3. Elimina toda la carpeta
echo   4. Ejecuta el script de nuevo
echo.
echo ====================================================================
echo.
pause
exit /b 1
