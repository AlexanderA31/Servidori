@echo off
:: Script de Diagnóstico y Limpieza Profunda de PrinterShare
:: Encuentra y elimina TODAS las referencias
:: Ejecutar como Administrador

title Diagnostico y Limpieza PrinterShare
color 0A

echo ========================================
echo DIAGNOSTICO COMPLETO - PrinterShare
echo ========================================
echo.
echo Este script encontrara TODAS las ubicaciones
echo donde esta configurado PrinterShare para iniciarse.
echo.
pause

echo.
echo ========================================
echo PASO 1: BUSCANDO EN EL REGISTRO
echo ========================================
echo.

set "FOUND_ISSUES=0"
set "LOGFILE=%TEMP%\printershare-cleanup.log"
echo Registro de limpieza - %DATE% %TIME% > "%LOGFILE%"
echo. >> "%LOGFILE%"

:: Función para buscar en una clave del registro
echo Buscando en HKEY_CURRENT_USER\Run...
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" 2>nul | findstr /I /C:"PrinterShare" /C:"start-client" /C:"AppData\Roaming\PrinterShare" > "%TEMP%\found1.txt" 2>nul
if %errorlevel%==0 (
    echo [!] ENCONTRADO en HKCU\Run:
    type "%TEMP%\found1.txt"
    echo. >> "%LOGFILE%"
    echo ENCONTRADO en HKCU\Run: >> "%LOGFILE%"
    type "%TEMP%\found1.txt" >> "%LOGFILE%"
    set "FOUND_ISSUES=1"
    echo.
)

echo Buscando en HKEY_CURRENT_USER\RunOnce...
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\RunOnce" 2>nul | findstr /I /C:"PrinterShare" /C:"start-client" > "%TEMP%\found2.txt" 2>nul
if %errorlevel%==0 (
    echo [!] ENCONTRADO en HKCU\RunOnce:
    type "%TEMP%\found2.txt"
    echo. >> "%LOGFILE%"
    echo ENCONTRADO en HKCU\RunOnce: >> "%LOGFILE%"
    type "%TEMP%\found2.txt" >> "%LOGFILE%"
    set "FOUND_ISSUES=1"
    echo.
)

echo Buscando en HKEY_LOCAL_MACHINE\Run...
reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" 2>nul | findstr /I /C:"PrinterShare" /C:"start-client" > "%TEMP%\found3.txt" 2>nul
if %errorlevel%==0 (
    echo [!] ENCONTRADO en HKLM\Run:
    type "%TEMP%\found3.txt"
    echo. >> "%LOGFILE%"
    echo ENCONTRADO en HKLM\Run: >> "%LOGFILE%"
    type "%TEMP%\found3.txt" >> "%LOGFILE%"
    set "FOUND_ISSUES=1"
    echo.
)

echo Buscando en HKEY_LOCAL_MACHINE\RunOnce...
reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\RunOnce" 2>nul | findstr /I /C:"PrinterShare" /C:"start-client" > "%TEMP%\found4.txt" 2>nul
if %errorlevel%==0 (
    echo [!] ENCONTRADO en HKLM\RunOnce:
    type "%TEMP%\found4.txt"
    echo. >> "%LOGFILE%"
    echo ENCONTRADO en HKLM\RunOnce: >> "%LOGFILE%"
    type "%TEMP%\found4.txt" >> "%LOGFILE%"
    set "FOUND_ISSUES=1"
    echo.
)

echo.
echo ========================================
echo PASO 2: BUSCANDO EN CARPETAS DE INICIO
echo ========================================
echo.

echo Buscando en Startup del usuario actual...
if exist "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\" (
    dir /B "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\*" 2>nul | findstr /I /C:"PrinterShare" /C:"start-client" /C:"impresora" > "%TEMP%\found5.txt" 2>nul
    if %errorlevel%==0 (
        echo [!] ENCONTRADO en Startup (Usuario):
        echo Ubicacion: %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\
        type "%TEMP%\found5.txt"
        echo. >> "%LOGFILE%"
        echo ENCONTRADO en Startup Usuario: >> "%LOGFILE%"
        type "%TEMP%\found5.txt" >> "%LOGFILE%"
        set "FOUND_ISSUES=1"
        echo.
    )
)

echo Buscando en Startup de todos los usuarios...
if exist "%PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs\Startup\" (
    dir /B "%PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs\Startup\*" 2>nul | findstr /I /C:"PrinterShare" /C:"start-client" /C:"impresora" > "%TEMP%\found6.txt" 2>nul
    if %errorlevel%==0 (
        echo [!] ENCONTRADO en Startup (Todos los usuarios):
        echo Ubicacion: %PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs\Startup\
        type "%TEMP%\found6.txt"
        echo. >> "%LOGFILE%"
        echo ENCONTRADO en Startup Todos: >> "%LOGFILE%"
        type "%TEMP%\found6.txt" >> "%LOGFILE%"
        set "FOUND_ISSUES=1"
        echo.
    )
)

echo.
echo ========================================
echo PASO 3: BUSCANDO TAREAS PROGRAMADAS
echo ========================================
echo.

schtasks /Query /FO LIST 2>nul | findstr /I /C:"PrinterShare" /C:"start-client" > "%TEMP%\found7.txt" 2>nul
if %errorlevel%==0 (
    echo [!] ENCONTRADO en Tareas Programadas:
    type "%TEMP%\found7.txt"
    echo. >> "%LOGFILE%"
    echo ENCONTRADO en Tareas Programadas: >> "%LOGFILE%"
    type "%TEMP%\found7.txt" >> "%LOGFILE%"
    set "FOUND_ISSUES=1"
    echo.
)

echo.
echo ========================================
echo RESUMEN DEL DIAGNOSTICO
echo ========================================
echo.

if "%FOUND_ISSUES%"=="1" (
    echo [!] SE ENCONTRARON PROBLEMAS
    echo.
    echo Las ubicaciones encontradas estan guardadas en:
    echo %LOGFILE%
    echo.
    echo.
    echo ========================================
    echo DESEAS ELIMINAR TODAS LAS REFERENCIAS?
    echo ========================================
    echo.
    choice /C SN /M "Eliminar automaticamente (S=Si, N=No)"
    
    if errorlevel 2 goto :MANUAL_INSTRUCTIONS
    if errorlevel 1 goto :AUTO_CLEANUP
) else (
    echo [OK] No se encontraron problemas en el sistema.
    echo.
    echo Si el error persiste, puede deberse a:
    echo - Carpetas protegidas por permisos
    echo - Entradas en otros usuarios de Windows
    echo - Tareas programadas con nombres diferentes
    echo.
    goto :END
)

:AUTO_CLEANUP
echo.
echo ========================================
echo LIMPIEZA AUTOMATICA
echo ========================================
echo.

echo Deteniendo procesos...
taskkill /F /IM wscript.exe /T >nul 2>&1
taskkill /F /IM cscript.exe /T >nul 2>&1
timeout /t 2 /nobreak >nul

echo.
echo Eliminando entradas del registro...

:: Obtener TODOS los nombres de valores que contengan PrinterShare o start-client
for /f "tokens=1,2,*" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" 2^>nul ^| findstr /I /C:"PrinterShare" /C:"start-client" /C:"AppData\\Roaming\\PrinterShare"') do (
    if "%%b"=="REG_SZ" (
        echo Eliminando: %%a
        reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "%%a" /f >nul 2>&1
    )
)

for /f "tokens=1,2,*" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\RunOnce" 2^>nul ^| findstr /I /C:"PrinterShare" /C:"start-client"') do (
    if "%%b"=="REG_SZ" (
        echo Eliminando: %%a
        reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\RunOnce" /v "%%a" /f >nul 2>&1
    )
)

for /f "tokens=1,2,*" %%a in ('reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" 2^>nul ^| findstr /I /C:"PrinterShare" /C:"start-client"') do (
    if "%%b"=="REG_SZ" (
        echo Eliminando: %%a
        reg delete "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" /v "%%a" /f >nul 2>&1
    )
)

echo.
echo Eliminando accesos directos...
del /F /Q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\*PrinterShare*" 2>nul
del /F /Q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\start-client*" 2>nul
del /F /Q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\*impresora*" 2>nul
del /F /Q "%PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs\Startup\*PrinterShare*" 2>nul
del /F /Q "%PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs\Startup\start-client*" 2>nul
del /F /Q "%PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs\Startup\*impresora*" 2>nul

echo.
echo Eliminando carpetas de datos...
if exist "%APPDATA%\PrinterShare\" (
    echo Eliminando: %APPDATA%\PrinterShare\
    rmdir /S /Q "%APPDATA%\PrinterShare\" 2>nul
)
if exist "%LOCALAPPDATA%\PrinterShare\" (
    echo Eliminando: %LOCALAPPDATA%\PrinterShare\
    rmdir /S /Q "%LOCALAPPDATA%\PrinterShare\" 2>nul
)

echo.
echo Eliminando tareas programadas...
for /f "delims=" %%T in ('schtasks /Query /FO LIST 2^>nul ^| findstr /I /C:"PrinterShare" /C:"start-client"') do (
    echo Eliminando tarea: %%T
    schtasks /Delete /TN "%%T" /F >nul 2>&1
)

echo.
echo Limpiando historial de archivos recientes...
del /F /Q "%APPDATA%\Microsoft\Windows\Recent\*start-client*" 2>nul
del /F /Q "%APPDATA%\Microsoft\Windows\Recent\*PrinterShare*" 2>nul

echo.
echo ========================================
echo LIMPIEZA COMPLETADA
echo ========================================
echo.
echo Todas las referencias fueron eliminadas.
echo.
echo IMPORTANTE: REINICIA tu computadora AHORA.
echo.
goto :END

:MANUAL_INSTRUCTIONS
echo.
echo ========================================
echo INSTRUCCIONES MANUALES
echo ========================================
echo.
echo Para eliminar manualmente:
echo.
echo 1. Presiona Win + R
echo 2. Escribe: regedit
echo 3. Ve a: HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
echo 4. Busca cualquier entrada que contenga "PrinterShare" o "start-client"
echo 5. Click derecho - Eliminar
echo.
echo Las ubicaciones exactas estan en: %LOGFILE%
echo.
goto :END

:END
:: Limpiar archivos temporales
del "%TEMP%\found*.txt" 2>nul
echo.
pause
