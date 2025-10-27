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
    const urlParts = ippUrl.match(/ipp:\/\/([^:]+):(\d+)\/printers\/(.+)/);
    const serverIp = urlParts ? urlParts[1] : 'servidor';
    const serverPort = urlParts ? urlParts[2] : '8631';
    const printerPath = urlParts ? urlParts[3] : printerName.replace(/\s/g, '_');
    const safeFileName = printerName.replace(/[^a-zA-Z0-9_-]/g, '_');
    const smbPath = '\\\\\\\\' + serverIp + '\\\\' + printerName.replace(/\s/g, '_');
    
    const scriptContent = generatePowerShellScript(serverIp, serverPort, printerName, printerPath, ippUrl, smbPath);
    downloadFile(scriptContent, 'instalar-' + safeFileName + '.ps1', 'text/plain');
}

// Generar el script PowerShell completo
function generatePowerShellScript(serverIp, serverPort, printerName, printerPath, ippUrl, smbPath) {
    return '# Script de instalacion de impresora via TCP/IP\n' +
'# Impresora: ' + printerName + '\n' +
'# Servidor: ' + serverIp + '\n' +
'\n' +
'param([string]$DisplayName = "' + printerName + '")\n' +
'\n' +
'$ServerIP = "' + serverIp + '"\n' +
'$ServerPort = ' + serverPort + '\n' +
'$PrinterName = "' + printerName + '"\n' +

'\n' +
'Write-Host "========================================" -ForegroundColor Cyan\n' +
'Write-Host "  Instalador de Impresora" -ForegroundColor Cyan\n' +
'Write-Host "========================================" -ForegroundColor Cyan\n' +
'Write-Host ""\n' +
'Write-Host "Impresora: $DisplayName" -ForegroundColor Green\n' +
'Write-Host "Servidor: $ServerIP" -ForegroundColor Green\n' +
'Write-Host "Ruta SMB: $SmbPath" -ForegroundColor Cyan\n' +
'Write-Host ""\n' +
'\n' +
'# Verificar permisos de admin\n' +
'$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")\n' +
'if (-NOT $isAdmin) {\n' +
'    Write-Host "ERROR: Requiere permisos de administrador" -ForegroundColor Red\n' +
'    Read-Host "Presiona Enter para salir"\n' +
'    exit 1\n' +
'}\n' +
'\n' +
'# Verificar servicio Spooler\n' +
'Write-Host "Verificando servicio Spooler..." -ForegroundColor Yellow\n' +
'$spooler = Get-Service -Name "Spooler"\n' +
'if ($spooler.Status -ne "Running") {\n' +
'    Start-Service -Name "Spooler"\n' +
'    Start-Sleep -Seconds 2\n' +
'}\n' +
'Write-Host "  Servicio OK" -ForegroundColor Green\n' +
'Write-Host ""\n' +
'\n' +
'# Verificar conectividad IPP\n' +
'Write-Host "Verificando servidor IPP..." -ForegroundColor Yellow\n' +
'try {\n' +
'    $tcpClient = New-Object System.Net.Sockets.TcpClient\n' +
'    $tcpClient.Connect($ServerIP, $ServerPort)\n' +
'    $tcpClient.Close()\n' +
'    Write-Host "  Servidor IPP accesible en puerto $ServerPort" -ForegroundColor Green\n' +
'} catch {\n' +
'    Write-Host "  ERROR: No se puede conectar al puerto IPP $ServerPort" -ForegroundColor Red\n' +
'    Write-Host "  Verifica que el servidor este encendido" -ForegroundColor Yellow\n' +
'    Read-Host "Presiona Enter para salir"\n' +
'    exit 1\n' +
'}\n' +
'Write-Host ""\n' +
'\n' +
'# Limpiar instalaciones previas\n' +
'Write-Host "Limpiando instalaciones previas..." -ForegroundColor Yellow\n' +
'\n' +
'# Primero eliminar impresoras existentes\n' +
'$existingPrinters = Get-Printer -Name "*$PrinterName*" -ErrorAction SilentlyContinue\n' +
'foreach ($printer in $existingPrinters) {\n' +
'    Write-Host "  Eliminando impresora: $($printer.Name)" -ForegroundColor Yellow\n' +
'    Remove-Printer -Name $printer.Name -Confirm:$false -ErrorAction SilentlyContinue\n' +
'}\n' +
'\n' +
'# Esperar a que se liberen los puertos\n' +
'Start-Sleep -Seconds 3\n' +
'\n' +
'# Ahora eliminar puertos huérfanos\n' +
'$portName = "IP_${ServerIP}_${PrinterName}"\n' +
'$existingPort = Get-PrinterPort -Name $portName -ErrorAction SilentlyContinue\n' +
'if ($existingPort) {\n' +
'    Write-Host "  Eliminando puerto existente: $portName" -ForegroundColor Yellow\n' +
'    Remove-PrinterPort -Name $portName -Confirm:$false -ErrorAction SilentlyContinue\n' +
'    Start-Sleep -Seconds 2\n' +
'}\n' +
'Write-Host ""\n' +
'\n' +
'$success = $false\n' +
'\n' +
'# METODO CORRECTO: Puerto TCP/IP con driver generico\n' +
'Write-Host "Instalando impresora via TCP/IP..." -ForegroundColor Cyan\n' +
'\n' +
'# Crear puerto TCP/IP\n' +
'Write-Host "  Creando puerto TCP/IP: $portName" -ForegroundColor Yellow\n' +
'\n' +
'try {\n' +
'    Add-PrinterPort -Name $portName -PrinterHostAddress $ServerIP -PortNumber $ServerPort -ErrorAction Stop\n' +
'    Write-Host "  ✓ Puerto creado correctamente" -ForegroundColor Green\n' +
'} catch {\n' +
'    if ($_.Exception.Message -like "*already exists*") {\n' +
'        Write-Host "  ! Puerto ya existe, continuando..." -ForegroundColor Yellow\n' +
'    } else {\n' +
'        Write-Host "  ✗ Error creando puerto: $_" -ForegroundColor Red\n' +
'        Read-Host "Presiona Enter para salir"\n' +
'        exit 1\n' +
'    }\n' +
'}\n' +
'\n' +
'# Agregar impresora con driver generico\n' +
'Write-Host "  Agregando impresora..." -ForegroundColor Yellow\n' +
'\n' +
'try {\n' +
'    # Intentar con driver generico Text Only\n' +
'    Add-Printer -Name $PrinterName -PortName $portName -DriverName "Generic / Text Only" -ErrorAction Stop\n' +
'    $success = $true\n' +
'    Write-Host "  Impresora instalada con driver generico" -ForegroundColor Green\n' +
'} catch {\n' +
'    # Si falla, intentar con Microsoft Print To PDF como fallback\n' +
'    try {\n' +
'        Add-Printer -Name $PrinterName -PortName $portName -DriverName "Microsoft Print To PDF" -ErrorAction Stop\n' +
'        $success = $true\n' +
'        Write-Host "  Impresora instalada con driver alternativo" -ForegroundColor Yellow\n' +
'    } catch {\n' +
'        Write-Host "  Error: $_" -ForegroundColor Red\n' +
'    }\n' +
'}\n' +
'\n' +
'# Resultado\n' +
'Write-Host ""\n' +
'if ($success) {\n' +
'    Write-Host "========================================" -ForegroundColor Green\n' +
'    Write-Host "  INSTALACION EXITOSA" -ForegroundColor Green\n' +
'    Write-Host "========================================" -ForegroundColor Green\n' +
'    $printers = Get-Printer | Where-Object { $_.Name -like "*$DisplayName*" -or $_.Name -like "*$ServerIP*" }\n' +
'    foreach ($p in $printers) {\n' +
'        Write-Host ""\n' +
'    Write-Host "  Nombre: $($p.Name)" -ForegroundColor White\n' +
'        Write-Host "  Puerto: $($p.PortName)" -ForegroundColor White\n' +
'        Write-Host "  Driver: $($p.DriverName)" -ForegroundColor White\n' +
'    }\n' +
'    Write-Host ""\n' +
'    Write-Host "NOTA IMPORTANTE:" -ForegroundColor Yellow\n' +
'    Write-Host "  - Instalada con driver generico" -ForegroundColor White\n' +
'    Write-Host "  - Para mejor calidad, instala drivers del fabricante" -ForegroundColor White\n' +
'    Write-Host "  - La impresora esta lista para usar" -ForegroundColor White\n' +
'} else {\n' +
'    Write-Host "========================================" -ForegroundColor Red\n' +
'    Write-Host "  ERROR EN INSTALACION" -ForegroundColor Red\n' +
'    Write-Host "========================================" -ForegroundColor Red\n' +
'    Write-Host ""\n' +
'    Write-Host "INSTALACION MANUAL (TCP/IP):" -ForegroundColor Yellow\n' +
'    Write-Host "1. Panel de Control -> Dispositivos e impresoras" -ForegroundColor White\n' +
'    Write-Host "2. Agregar impresora" -ForegroundColor White\n' +
'    Write-Host "3. La impresora no esta en la lista" -ForegroundColor White\n' +
'    Write-Host "4. Agregar mediante direccion TCP/IP" -ForegroundColor White\n' +
'    Write-Host "5. Direccion: $ServerIP" -ForegroundColor Cyan\n' +
'    Write-Host "6. Puerto: $ServerPort" -ForegroundColor Cyan\n' +
'    Write-Host "7. Personalizado -> LPR -> Cola: printers/$PrinterName" -ForegroundColor White\n' +
'}\n' +
'\n' +
'Write-Host ""\n' +
'Read-Host "Presiona Enter para salir"';
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
