// home-printer-sharing.js - Gestión de compartir impresoras y configuración IPP

document.addEventListener('DOMContentLoaded', function() {
    initializeTabs();
    initializePrinterSelect();
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
    
    // Extraer puerto del URI
    const portMatch = ippUri.match(/:([\d]+)\//);
    const port = portMatch ? portMatch[1] : '8631';
    
    ippUrlElement.textContent = ippUri;
    linuxCommand.textContent = 'lpadmin -p "' + printerName + '" -v "' + ippUri + '" -E';
    instructionsDiv.style.display = 'block';
}

// Copiar al portapapeles
function copyToClipboard(elementId) {
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
        alert('No se pudo copiar al portapapeles');
    });
}

// ==================== SCRIPTS DE DESCARGA ====================

// Descargar script para cliente Windows - SMB + IPP Fallback
function downloadWindowsClientScript() {
    const printerSelect = document.getElementById('printerSelect');
    const selectedOption = printerSelect.options[printerSelect.selectedIndex];
    
    if (!selectedOption.value) {
        alert('Por favor selecciona una impresora primero');
        return;
    }
    
    const ippUrl = selectedOption.dataset.ippuri;
    const printerName = selectedOption.dataset.name;
    
    // Extraer puerto del URI IPP
    const urlParts = ippUrl.match(/ipp:\/\/([^:]+):(\d+)\/printers\/(.+)/);
    const serverIp = urlParts ? urlParts[1] : 'servidor';
    const serverPort = urlParts ? parseInt(urlParts[2]) : 8631;
    const printerPath = urlParts ? urlParts[3] : printerName.replace(/\s/g, '_');
    const safeFileName = printerName.replace(/[^a-zA-Z0-9_-]/g, '_');
    const smbPath = '\\\\\\\\' + serverIp + '\\\\' + printerName.replace(/\s/g, '_');
    
    console.log('Descargando script para:', { printerName, serverIp, serverPort });
    console.log('Puerto especifico detectado:', serverPort);
    
    const scriptContent = generatePowerShellScript(serverIp, serverPort, printerName, printerPath, ippUrl, smbPath, safeFileName);
    downloadFile(scriptContent, 'instalar-' + safeFileName + '.ps1', 'text/plain');
}

// Generar el script PowerShell completo
function generatePowerShellScript(serverIp, serverPort, printerName, printerPath, ippUrl, smbPath, safeFileName) {
    // Nota: serverPort siempre será 8631 (puerto único)
    // El servidor redirige internamente a la IP física de cada impresora
    const script = `# Script de instalacion de impresora via TCP/IP
# NOTA: Este servidor usa UN SOLO PUERTO (8631) para todas las impresoras
# El servidor redirige automáticamente a la IP física de cada impresora
# Impresora: ${printerName}
# Servidor: ${serverIp}

param([string]$DisplayName = "${printerName}")

$ServerIP = "${serverIp}"
$ServerPort = ${serverPort}  # Puerto único para todas las impresoras
$PrinterName = "${printerName}"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Instalador de Impresora" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Impresora: $DisplayName" -ForegroundColor Green
Write-Host "Servidor: $ServerIP Puerto: $ServerPort (PUERTO UNICO)" -ForegroundColor Green
Write-Host "Nota: El servidor redirige a la impresora fisica" -ForegroundColor Yellow
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
    alert('Script descargado: ' + filename + '\n\nEjecuta como Administrador');
}
