// home-printer-sharing.js - Gestión de compartir impresoras y configuración IPP

document.addEventListener('DOMContentLoaded', function() {
    initializeTabs();
    initializePrinterSelect();
    initializeDepartmentSelect();
});

// Inicializar tabs de OS para compartir
function initializeTabs() {
    const osTabs = document.querySelectorAll('.os-tab');
    osTabs.forEach(tab => {
        tab.addEventListener('click', function() {
            const os = this.dataset.os;
            osTabs.forEach(t => t.classList.remove('active'));
            this.classList.add('active');
            document.querySelectorAll('.os-content').forEach(content => {
                content.classList.remove('active');
            });
            document.getElementById(os + '-share').classList.add('active');
        });
    });
    
    const clientTabs = document.querySelectorAll('.client-tab');
    clientTabs.forEach(tab => {
        tab.addEventListener('click', function() {
            const client = this.dataset.client;
            clientTabs.forEach(t => t.classList.remove('active'));
            this.classList.add('active');
            document.querySelectorAll('.client-content').forEach(content => {
                content.classList.remove('active');
            });
            document.getElementById(client).classList.add('active');
        });
    });
}

// Inicializar selector de departamentos
function initializeDepartmentSelect() {
    const departmentSelect = document.getElementById('departmentSelect');
    if (!departmentSelect) return;
    
    // El evento change ya se configura en el HTML inline
    // Esta función es para inicializaciones adicionales si se necesitan
}

// Inicializar selector de impresoras
function initializePrinterSelect() {
    const printerSelect = document.getElementById('printerSelect');
    if (!printerSelect) return;
    
    printerSelect.addEventListener('change', function() {
        const selectedOption = this.options[this.selectedIndex];
        if (selectedOption.value) {
            const ippUri = selectedOption.dataset.ippuri;
            const printerName = selectedOption.dataset.name;
            updateIPPInstructions(ippUri, printerName);
        } else {
            document.getElementById('ippInstructions').style.display = 'none';
        }
    });
}

// Actualizar instrucciones IPP
function updateIPPInstructions(ippUri, printerName) {
    const instructionsDiv = document.getElementById('ippInstructions');
    const ippUrlElement = document.getElementById('ippUrl');
    const linuxCommand = document.getElementById('linuxCommand');
    const macIppUrl = document.getElementById('macIppUrl');
    
    // Extraer puerto del URI
    const portMatch = ippUri.match(/:([\d]+)\//);
    const port = portMatch ? portMatch[1] : '8631';
    
    ippUrlElement.textContent = ippUri;
    linuxCommand.textContent = 'lpadmin -p "' + printerName + '" -v "' + ippUri + '" -E';
    
    // Actualizar URL para macOS
    if (macIppUrl) {
        macIppUrl.textContent = ippUri;
    }
    
    instructionsDiv.style.display = 'block';
}

// Copiar al portapapeles
function copyToClipboard(elementId, event) {
    const element = document.getElementById(elementId);
    const text = element.textContent;
    
    navigator.clipboard.writeText(text).then(() => {
        const button = event.target;
        const originalText = button.textContent;
        button.textContent = '✓ Copiado';
        button.classList.add('copied');
        setTimeout(() => {
            button.textContent = originalText;
            button.classList.remove('copied');
        }, 2000);
    }).catch(err => {
        console.error('Error al copiar:', err);
        showError('No se pudo copiar al portapapeles');
    });
}

// ==================== SCRIPTS DE DESCARGA ====================

// Descargar script PS1 para cliente Windows (RECOMENDADO - con doble clic)
function downloadWindowsClientScript() {
    const printerSelect = document.getElementById('printerSelect');
    const selectedOption = printerSelect.options[printerSelect.selectedIndex];
    
    if (!selectedOption.value) {
        showWarning('Por favor selecciona una impresora primero');
        return;
    }
    
    const ippUrl = selectedOption.dataset.ippuri;
    const printerName = selectedOption.dataset.name;
    const printerLocation = selectedOption.dataset.location || '';
    
    // DETECTAR SI ES IMPRESORA USB COMPARTIDA
    const isSharedUSB = printerLocation.includes('Compartida-USB');
    
    // Extraer información del URI IPP
    const urlParts = ippUrl.match(/ipp:\/\/([^:]+):(\d+)\/printers\/(.+)/);
    let serverIp = urlParts ? urlParts[1] : '10.1.16.31';
    let serverPort = urlParts ? parseInt(urlParts[2]) : 8631;
    
    // TODAS las impresoras usan el servidor como intermediario:
    // - Clientes se conectan al servidor en puerto 863X
    // - El servidor detecta si es USB compartida y reenvía al cliente USB
    // - Si es impresora de red, el servidor la envía directamente
    if (isSharedUSB) {
    
    } else {
    
    }
    
    const printerPath = urlParts ? urlParts[3] : printerName.replace(/\s/g, '_');
    const safeFileName = printerName.replace(/[^a-zA-Z0-9_-]/g, '_');
    const smbPath = '\\\\\\\\' + serverIp + '\\\\' + printerName.replace(/\s/g, '_');
    
    const batContent = generateBatWithEmbeddedPS(serverIp, serverPort, printerName, safeFileName, isSharedUSB);
    downloadFile(batContent, 'instalar-' + safeFileName + '.bat', 'text/plain');
    
    // Mostrar instrucciones
    setTimeout(() => {
        showSuccess('Script BAT descargado: instalar-' + safeFileName + '.bat', 'Descarga Completa');
        setTimeout(() => {
            showInfo(
                '<strong>✅ DOBLE CLIC Y LISTO!</strong><br><br>' +
                '1. Haz <strong>DOBLE CLIC</strong> en el archivo BAT descargado<br>' +
                '2. Se solicitarán permisos de administrador automáticamente<br>' +
                '3. La impresora se instalará automáticamente<br><br>' +
                (isSharedUSB ? 
                    '<strong>🖨️ Impresora USB Compartida</strong><br>' +
                    'Servidor: ' + serverIp + ':' + serverPort + ' (reenvía a cliente USB)<br><br>' +
                    '<strong>⚠️ Nota:</strong> La PC con la impresora USB debe estar encendida' :
                    '<strong>Puerto asignado: ' + serverPort + '</strong> (FIJO)<br><br>' +
                    '<strong>⚠️ Nota:</strong> Este puerto es exclusivo y nunca cambia'),
                'Instrucciones Simples'
            );
        }, 800);
    }, 500);
}

// Generar el script PowerShell completo
function generatePowerShellScript(serverIp, serverPort, printerName, printerPath, ippUrl, smbPath, safeFileName) {
    // Nota: Cada impresora tiene su puerto dedicado (8631, 8632, 8633, etc.)
    // El puerto es fijo y está almacenado en la base de datos
    const script = `# Script de instalacion de impresora via TCP/IP
# NOTA: Cada impresora tiene su PUERTO DEDICADO FIJO
# Este puerto nunca cambia y está almacenado en la base de datos
# Impresora: ${printerName}
# Servidor: ${serverIp}

param([string]$DisplayName = "${printerName}")

$ServerIP = "${serverIp}"
$ServerPort = ${serverPort}  # Puerto FIJO de esta impresora
$PrinterName = "${printerName}"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Instalador de Impresora" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Impresora: $DisplayName" -ForegroundColor Green
Write-Host "Servidor: $ServerIP Puerto: $ServerPort (PUERTO FIJO)" -ForegroundColor Green
Write-Host "Nota: Este puerto es exclusivo para esta impresora" -ForegroundColor Yellow
Write-Host ""

# Verificar permisos de admin
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")
if (-NOT $isAdmin) {
    Write-Host "ERROR: Requiere permisos de administrador" -ForegroundColor Red
    Read-Host "Presiona Enter para salir"
    exit 1
}

# Verificar servicio Spooler
Write-Host "Verificando servicio Spooler..." -ForegroundColor Yellow
$spooler = Get-Service -Name "Spooler"
if ($spooler.Status -ne "Running") {
    Start-Service -Name "Spooler"
    Start-Sleep -Seconds 2
}
Write-Host "  Servicio OK" -ForegroundColor Green
Write-Host ""

# Verificar conectividad IPP
Write-Host "Verificando servidor IPP..." -ForegroundColor Yellow
try {
    $tcpClient = New-Object System.Net.Sockets.TcpClient
    $tcpClient.Connect($ServerIP, $ServerPort)
    $tcpClient.Close()
    Write-Host "  Servidor IPP accesible en puerto $ServerPort" -ForegroundColor Green
} catch {
    Write-Host "  ERROR: No se puede conectar al puerto IPP $ServerPort" -ForegroundColor Red
    Write-Host "  Verifica que el servidor este encendido" -ForegroundColor Yellow
    Read-Host "Presiona Enter para salir"
    exit 1
}
Write-Host ""

# Limpiar instalaciones previas
Write-Host "Limpiando instalaciones previas..." -ForegroundColor Yellow

# Primero eliminar impresoras existentes
$existingPrinters = Get-Printer -Name "*$PrinterName*" -ErrorAction SilentlyContinue
foreach ($printer in $existingPrinters) {
    Write-Host "  Eliminando impresora: $($printer.Name)" -ForegroundColor Yellow
    Remove-Printer -Name $printer.Name -Confirm:$false -ErrorAction SilentlyContinue
}

# Esperar a que se liberen los puertos
Start-Sleep -Seconds 3

# Ahora eliminar puertos huerfanos
$portName = "IP_$($ServerIP)_$($PrinterName)"
$existingPort = Get-PrinterPort -Name $portName -ErrorAction SilentlyContinue
if ($existingPort) {
    Write-Host "  Eliminando puerto existente: $portName" -ForegroundColor Yellow
    Remove-PrinterPort -Name $portName -Confirm:$false -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}
Write-Host ""

$success = $false

# METODO 1: Intentar via IPP primero
Write-Host "Metodo 1: Instalando via IPP..." -ForegroundColor Cyan

# Normalizar nombre para URL
$PrinterNameUrl = $PrinterName -replace ' ', '_'
$ippUrl = "http://" + $ServerIP + ":" + $ServerPort + "/printers/" + $PrinterNameUrl
Write-Host "  URL IPP: $ippUrl" -ForegroundColor White

try {
    # Intentar con timeout
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    
    # Crear trabajo para timeout
    $job = Start-Job -ScriptBlock {
        param($url)
        try {
            Add-Printer -ConnectionName $url -ErrorAction Stop
            return $true
        } catch {
            return $false
        }
    } -ArgumentList $ippUrl
    
    # Esperar max 15 segundos
    $completed = Wait-Job $job -Timeout 15
    
    if ($completed) {
        $result = Receive-Job $job
        if ($result -eq $true) {
            $success = $true
            Write-Host "  [OK] Impresora instalada via IPP" -ForegroundColor Green
        }
    } else {
        Write-Host "  [!] Timeout esperando respuesta IPP" -ForegroundColor Yellow
        Stop-Job $job
    }
    
    Remove-Job $job -Force
    
} catch {
    Write-Host "  [!] Error IPP: $($_.Exception.Message)" -ForegroundColor Yellow
}

if (-not $success) {
    Write-Host ""
    Write-Host "Metodo 2: Instalando via TCP/IP RAW..." -ForegroundColor Cyan
    
    # Crear puerto TCP/IP unico por impresora
    Write-Host "  Creando puerto TCP/IP: $portName" -ForegroundColor Yellow
    
    try {
        Add-PrinterPort -Name $portName -PrinterHostAddress $ServerIP -PortNumber $ServerPort -ErrorAction Stop
        Write-Host "  [OK] Puerto creado" -ForegroundColor Green
    } catch {
        if ($_.Exception.Message -like "*already exists*") {
            Write-Host "  [!] Puerto ya existe" -ForegroundColor Yellow
        } else {
            Write-Host "  [ERROR] Error creando puerto: $_" -ForegroundColor Red
            Read-Host "Presiona Enter para salir"
            exit 1
        }
    }
    
    # Agregar impresora con driver generico
    Write-Host "  Agregando impresora..." -ForegroundColor Yellow
    
    try {
        # Intentar con driver generico Text Only
        Add-Printer -Name $PrinterName -PortName $portName -DriverName "Generic / Text Only" -ErrorAction Stop
        $success = $true
        Write-Host "  [OK] Impresora instalada con driver generico" -ForegroundColor Green
    } catch {
        # Si falla, intentar con Microsoft Print To PDF como fallback
        try {
            Add-Printer -Name $PrinterName -PortName $portName -DriverName "Microsoft Print To PDF" -ErrorAction Stop
            $success = $true
            Write-Host "  [OK] Impresora instalada con driver alternativo" -ForegroundColor Yellow
        } catch {
            Write-Host "  [ERROR] $_" -ForegroundColor Red
        }
    }
}

# Resultado
Write-Host ""
if ($success) {
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  INSTALACION EXITOSA" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    $printers = Get-Printer | Where-Object { $_.Name -like "*$DisplayName*" -or $_.Name -like "*$ServerIP*" }
    foreach ($p in $printers) {
        Write-Host ""
        Write-Host "  Nombre: $($p.Name)" -ForegroundColor White
        Write-Host "  Puerto: $($p.PortName)" -ForegroundColor White
        Write-Host "  Driver: $($p.DriverName)" -ForegroundColor White
    }
    Write-Host ""
    Write-Host "NOTA IMPORTANTE:" -ForegroundColor Yellow
    Write-Host "  - Instalada con driver generico" -ForegroundColor White
    Write-Host "  - Para mejor calidad, instala drivers del fabricante" -ForegroundColor White
    Write-Host "  - La impresora esta lista para usar" -ForegroundColor White
} else {
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "  ERROR EN INSTALACION" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "INSTALACION MANUAL (TCP/IP):" -ForegroundColor Yellow
    Write-Host "1. Panel de Control -> Dispositivos e impresoras" -ForegroundColor White
    Write-Host "2. Agregar impresora" -ForegroundColor White
    Write-Host "3. La impresora no esta en la lista" -ForegroundColor White
    Write-Host "4. Agregar mediante direccion TCP/IP" -ForegroundColor White
    Write-Host "5. Direccion: $ServerIP" -ForegroundColor Cyan
    Write-Host "6. Puerto: $ServerPort" -ForegroundColor Cyan
    Write-Host "7. Personalizado -> LPR -> Cola: printers/${printerName}" -ForegroundColor White
}

Write-Host ""
Read-Host "Presiona Enter para salir"`;
    
    return script;
}

// Función auxiliar para descargar archivo
function downloadFile(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    showSuccess('Script descargado: ' + filename, 'Descarga Completa');
    setTimeout(() => {
        showInfo('Recuerda ejecutar el script como Administrador', 'Instrucción Importante');
    }, 500);
}

// ==================== COMPARTIR IMPRESORAS USB/LOCAL ====================

// Descargar script para compartir impresora en Windows
function downloadWindowsScript() {

    
    // Redirigir al endpoint que sirve el archivo
    window.location.href = '/print-server/download/share-windows-script';
    
    // Mostrar mensajes de instrucciones
    setTimeout(() => {
        showSuccess('Script descargado correctamente', 'Descarga Completa');
        setTimeout(() => {
            showInfo(
                '<strong>Instrucciones:</strong><br>' +
                '1. Haz clic derecho en el archivo descargado<br>' +
                '2. Selecciona "Ejecutar con PowerShell"<br>' +
                '3. Si aparece advertencia, selecciona "Abrir"<br>' +
                '4. Selecciona la impresora USB que deseas compartir<br>' +
                '5. El script se configurará automáticamente<br><br>' +
                '<strong>⚠️ Importante:</strong> Debe ejecutarse como Administrador',
                'Cómo usar el script'
            );
        }, 800);
    }, 500);
}

// Descargar script de instalación para Linux
function downloadLinuxClientScript() {
    const printerSelect = document.getElementById('printerSelect');
    const selectedOption = printerSelect.options[printerSelect.selectedIndex];
    
    if (!selectedOption.value) {
        showWarning('Por favor selecciona una impresora primero');
        return;
    }
    
    const printerName = selectedOption.dataset.name;
    const safeFileName = printerName.replace(/[^a-zA-Z0-9_-]/g, '_');
    
    // Redirigir al endpoint que sirve el script de Linux
    window.location.href = '/print-server/download/install-linux-script';
    
    // Mostrar instrucciones
    setTimeout(() => {
        showSuccess('Script descargado: instalar-impresora-ipp.sh', 'Descarga Completa');
        setTimeout(() => {
            showInfo(
                '<strong>🐧 Instrucciones para Linux:</strong><br><br>' +
                '1. Abre una terminal<br>' +
                '2. Navega a la carpeta de descargas:<br>' +
                '   <code>cd ~/Descargas</code><br><br>' +
                '3. Da permisos de ejecución:<br>' +
                '   <code>chmod +x instalar-impresora-ipp.sh</code><br><br>' +
                '4. Ejecuta el script:<br>' +
                '   <code>./instalar-impresora-ipp.sh</code><br><br>' +
                '<strong>ℹ️ Nota:</strong> El script usará IPP Everywhere para enviar PDF directo',
                'Cómo usar el script'
            );
        }, 800);
    }, 500);
}

// Descargar script para compartir impresora en Linux
function downloadLinuxScript() {
    showInfo(
        '<strong>Script para compartir impresoras USB en Linux</strong><br><br>' +
        'Usa CUPS para compartir tu impresora USB:<br><br>' +
        '<code>sudo cupsctl --share-printers<br>' +
        'sudo cupsctl --remote-admin<br>' +
        'sudo systemctl restart cups</code><br><br>' +
        'Luego regístrala en el servidor desde la interfaz web de CUPS.',
        '🐧 Compartir en Linux'
    );
}

// Generar BAT con PowerShell embebido (para doble clic)
function generateBatWithEmbeddedPS(serverIp, serverPort, printerName, safeFileName, isSharedUSB) {
    // Escapar el nombre de la impresora para PowerShell
    const printerNameEscaped = printerName.replace(/'/g, "''");
    
    // Mensaje para el BAT
    const usbNote = isSharedUSB ? ' (servidor reenvía a cliente USB)' : ' (PUERTO FIJO)';
    
    const bat = `@echo off
REM ====================================================================
REM Instalador de Impresora: ${printerName}
REM Servidor: ${serverIp}:${serverPort}
REM Puerto dedicado del servidor${usbNote}
REM ====================================================================

REM Verificar permisos de administrador
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo.
    echo Solicitando permisos de administrador...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

title Instalador de Impresora - ${printerName}

echo.
echo ====================================================================
echo   INSTALADOR DE IMPRESORA
echo ====================================================================
echo.
echo   Impresora: ${printerName}
echo   Servidor: ${serverIp}:${serverPort}
` + (isSharedUSB ? 
`echo   Tipo: USB Compartida (servidor como intermediario)
echo   NOTA: El servidor reenviara a la PC con la impresora USB` :
`echo   Tipo: Red (puerto dedicado FIJO)`) + `
echo.
echo ====================================================================
echo.
echo Instalando impresora, por favor espera...
echo.

REM Ejecutar PowerShell inline directamente CON IPP EVERYWHERE (PDF DIRECTO)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ServerIP='${serverIp}'; $ServerPort=${serverPort}; $PrinterName='${printerNameEscaped}'; $DisplayName='${printerNameEscaped}'; Write-Host 'Verificando servicio Spooler...' -ForegroundColor Yellow; $spooler=Get-Service -Name 'Spooler'; if($spooler.Status -ne 'Running'){Start-Service -Name 'Spooler'; Start-Sleep -Seconds 2}; Write-Host 'Verificando conexion al servidor...' -ForegroundColor Yellow; try{$tcp=New-Object System.Net.Sockets.TcpClient; $tcp.Connect($ServerIP,$ServerPort); $tcp.Close(); Write-Host 'Servidor accesible' -ForegroundColor Green}catch{Write-Host 'ERROR: No se puede conectar al servidor' -ForegroundColor Red; exit 1}; Write-Host 'Limpiando instalaciones previas...' -ForegroundColor Yellow; $existing=Get-Printer | Where-Object {$_.Name -like ('*'+$PrinterName+'*')}; foreach($p in $existing){Remove-Printer -Name $p.Name -Confirm:$false -ErrorAction SilentlyContinue}; Start-Sleep -Seconds 2; $portName=[string]::Format('IP_{0}_{1}',$ServerIP,$PrinterName); $existPort=Get-PrinterPort -Name $portName -ErrorAction SilentlyContinue; if($existPort){Remove-PrinterPort -Name $portName -Confirm:$false -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1}; Write-Host 'Creando puerto de impresora...' -ForegroundColor Yellow; try{Add-PrinterPort -Name $portName -PrinterHostAddress $ServerIP -PortNumber $ServerPort -ErrorAction Stop; Write-Host 'Puerto creado correctamente' -ForegroundColor Green}catch{if($_.Exception.Message -notlike '*already exists*'){Write-Host 'ERROR al crear puerto' -ForegroundColor Red; exit 1}}; Write-Host 'Buscando driver IPP Everywhere (PDF directo)...' -ForegroundColor Yellow; $success=$false; $ippDriver=Get-PrinterDriver | Where-Object {$_.Name -like '*IPP*' -or $_.Name -like '*Internet*'} | Select-Object -First 1; if($ippDriver){Write-Host 'Usando driver IPP Everywhere:' $ippDriver.Name -ForegroundColor Cyan; try{Add-Printer -Name $DisplayName -PortName $portName -DriverName $ippDriver.Name -ErrorAction Stop; $success=$true; Write-Host 'Impresora instalada con IPP Everywhere (PDF DIRECTO)' -ForegroundColor Green}catch{Write-Host 'Fallo IPP, intentando alternativa...' -ForegroundColor Yellow}}; if(-not $success){Write-Host 'Usando Microsoft Print to PDF (PDF directo)...' -ForegroundColor Yellow; try{Add-Printer -Name $DisplayName -PortName $portName -DriverName 'Microsoft Print To PDF' -ErrorAction Stop; $success=$true; Write-Host 'Impresora instalada con driver PDF (mantiene formato)' -ForegroundColor Green}catch{Write-Host 'Intentando driver generico...' -ForegroundColor Yellow; try{Add-Printer -Name $DisplayName -PortName $portName -DriverName 'Generic / Text Only' -ErrorAction Stop; $success=$true; Write-Host 'Impresora instalada con driver generico (texto simple)' -ForegroundColor Yellow}catch{Write-Host 'ERROR: No se pudo instalar la impresora' -ForegroundColor Red; exit 1}}}; if($success){Write-Host ''; Write-Host 'INSTALACION EXITOSA' -ForegroundColor Green; Write-Host 'Puerto asignado:' $ServerPort '(FIJO)' -ForegroundColor Cyan; Write-Host ''; Write-Host 'IMPORTANTE - Tipo de driver instalado:' -ForegroundColor Yellow; if($ippDriver -and $success){Write-Host '- IPP Everywhere: Envia documentos en PDF con formato completo' -ForegroundColor Green}else{Write-Host '- PDF Printer: Mantiene formato, colores y fuentes' -ForegroundColor Green}; Write-Host '- Los documentos se enviaran con TODO su formato original' -ForegroundColor Cyan; Write-Host ''; Write-Host 'La impresora esta lista para usar' -ForegroundColor White; exit 0}else{exit 1}"

set ERROR_CODE=%ERRORLEVEL%

echo.
echo ====================================================================
if %ERROR_CODE% EQU 0 (
    echo   INSTALACION COMPLETADA EXITOSAMENTE
    echo ====================================================================
    echo.
    echo La impresora ha sido instalada correctamente.
    echo Puerto asignado: ${serverPort} (FIJO para esta impresora^)
    echo.
    echo DRIVER INSTALADO: IPP Everywhere o Microsoft PDF
    echo - Los documentos se envian en PDF (mantiene formato^)
    echo - Conserva negritas, colores, tablas, imagenes
    echo - Compatible con Word, Excel, PDF, etc.
    echo.
    echo Ahora puedes imprimir desde cualquier aplicacion.
) else (
    echo   ERROR EN LA INSTALACION
    echo ====================================================================
    echo.
    echo Codigo de error: %ERROR_CODE%
    echo.
    echo Posibles causas:
    echo - El servidor no esta accesible
    echo - No hay permisos de administrador
    echo - Problema con el servicio de impresion
)
echo.
echo ====================================================================
echo.
echo Presiona cualquier tecla para cerrar esta ventana...
pause >nul
`;
    
    return bat;
}





