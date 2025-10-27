# ==============================================================================
# Script para Configurar Impresoras IPP - Version Simplificada
# ==============================================================================
#
# USO:
#   .\configurar-impresora-simple.ps1 -PrinterName "NOMBRE_IMPRESORA"
#
# EJEMPLOS:
#   .\configurar-impresora-simple.ps1 -PrinterName "EPSON264A53"
#   .\configurar-impresora-simple.ps1 -ServerIP "10.1.1.79" -PrinterName "HP_LaserJet"
#
# ==============================================================================

param(
    [Parameter(Mandatory=$false)]
    [string]$ServerIP = "10.1.1.79",
    
    [Parameter(Mandatory=$false)]
    [int]$ServerPort = 8631,
    
    [Parameter(Mandatory=$true)]
    [string]$PrinterName,
    
    [Parameter(Mandatory=$false)]
    [string]$DisplayName = $PrinterName
)

# ==============================================================================
# FUNCIONES
# ==============================================================================

function Test-IsAdmin {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Write-ColorMessage {
    param(
        [string]$Message,
        [string]$Color = "White"
    )
    Write-Host $Message -ForegroundColor $Color
}

function Start-PrintSpooler {
    Write-ColorMessage "Verificando servicio de impresion..." "Yellow"
    
    $spooler = Get-Service -Name "Spooler"
    
    if ($spooler.Status -ne "Running") {
        Write-ColorMessage "Iniciando servicio Spooler..." "Yellow"
        Start-Service -Name "Spooler"
        Start-Sleep -Seconds 2
    }
    
    $spooler = Get-Service -Name "Spooler"
    if ($spooler.Status -eq "Running") {
        Write-ColorMessage "Servicio Spooler esta corriendo" "Green"
    } else {
        Write-ColorMessage "ERROR: No se pudo iniciar el servicio Spooler" "Red"
        exit 1
    }
}

function Test-ServerConnection {
    param([string]$IP, [int]$Port)
    
    Write-ColorMessage "Probando conexion con el servidor..." "Yellow"
    
    try {
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $connection = $tcpClient.BeginConnect($IP, $Port, $null, $null)
        $wait = $connection.AsyncWaitHandle.WaitOne(3000, $false)
        
        if ($wait) {
            $tcpClient.EndConnect($connection)
            $tcpClient.Close()
            Write-ColorMessage "Servidor accesible en ${IP}:${Port}" "Green"
            return $true
        } else {
            $tcpClient.Close()
            Write-ColorMessage "No se puede conectar a ${IP}:${Port}" "Yellow"
            return $false
        }
    } catch {
        Write-ColorMessage "Error al probar conexion: $_" "Red"
        return $false
    }
}

function Remove-ExistingPrinter {
    param([string]$Name)
    
    $existing = Get-Printer -Name "*$Name*" -ErrorAction SilentlyContinue
    
    if ($existing) {
        Write-ColorMessage "Eliminando impresora existente: $($existing.Name)" "Yellow"
        Remove-Printer -Name $existing.Name -Confirm:$false -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}

function Install-IPPPrinterViaVBS {
    param([string]$IppUrl)
    
    Write-ColorMessage "Instalando impresora via VBScript..." "Yellow"
    
    $vbsContent = @"
On Error Resume Next
Set objNetwork = CreateObject("WScript.Network")
objNetwork.AddWindowsPrinterConnection "$IppUrl"
If Err.Number <> 0 Then
    WScript.Echo "Error: " & Err.Description
    WScript.Quit 1
End If
WScript.Quit 0
"@
    
    $vbsPath = Join-Path $env:TEMP "add_printer.vbs"
    $vbsContent | Out-File -FilePath $vbsPath -Encoding ASCII -Force
    
    try {
        $process = Start-Process "cscript.exe" -ArgumentList "//NoLogo `"$vbsPath`"" -Wait -PassThru -WindowStyle Hidden
        Remove-Item $vbsPath -Force -ErrorAction SilentlyContinue
        
        if ($process.ExitCode -eq 0) {
            Write-ColorMessage "Impresora instalada correctamente" "Green"
            return $true
        } else {
            Write-ColorMessage "Error al instalar impresora (codigo: $($process.ExitCode))" "Red"
            return $false
        }
    } catch {
        Write-ColorMessage "Error ejecutando VBScript: $_" "Red"
        return $false
    }
}

function Install-IPPPrinterDirect {
    param([string]$IppUrl)
    
    Write-ColorMessage "Instalando impresora via PowerShell..." "Yellow"
    
    try {
        Add-Printer -ConnectionName $IppUrl -ErrorAction Stop
        Write-ColorMessage "Impresora instalada correctamente" "Green"
        return $true
    } catch {
        Write-ColorMessage "Error con Add-Printer: $_" "Yellow"
        return $false
    }
}

function Show-InstalledPrinter {
    Write-ColorMessage "`nImpresoras instaladas:" "Cyan"
    Get-Printer | Where-Object { $_.Type -eq "Connection" } | Format-Table Name, DriverName -AutoSize
}

function Show-ManualSteps {
    param([string]$IppUrl)
    
    Write-ColorMessage "`n============================================================" "Yellow"
    Write-ColorMessage "INSTRUCCIONES DE INSTALACION MANUAL" "Yellow"
    Write-ColorMessage "============================================================" "Yellow"
    Write-ColorMessage ""
    Write-ColorMessage "1. Abre: Panel de Control -> Dispositivos e impresoras" "White"
    Write-ColorMessage "2. Click en 'Agregar una impresora'" "White"
    Write-ColorMessage "3. Click en 'La impresora deseada no esta en la lista'" "White"
    Write-ColorMessage "4. Selecciona: 'Seleccionar una impresora compartida por nombre'" "White"
    Write-ColorMessage "5. Ingresa esta URL:" "White"
    Write-ColorMessage ""
    Write-ColorMessage "   $IppUrl" "Cyan"
    Write-ColorMessage ""
    Write-ColorMessage "6. Sigue el asistente para completar la instalacion" "White"
    Write-ColorMessage ""
}

# ==============================================================================
# PROGRAMA PRINCIPAL
# ==============================================================================

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Configurador de Impresoras IPP" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Configuracion:" -ForegroundColor Green
Write-Host "  Servidor: $ServerIP" -ForegroundColor White
Write-Host "  Puerto: $ServerPort" -ForegroundColor White
Write-Host "  Impresora: $PrinterName" -ForegroundColor White
Write-Host "  Nombre local: $DisplayName" -ForegroundColor White
Write-Host ""

# Verificar permisos de administrador
if (-not (Test-IsAdmin)) {
    Write-ColorMessage "ERROR: Este script requiere permisos de administrador" "Red"
    Write-ColorMessage ""
    Write-ColorMessage "Pasos:" "Yellow"
    Write-ColorMessage "  1. Cierra esta ventana" "White"
    Write-ColorMessage "  2. Click derecho en PowerShell" "White"
    Write-ColorMessage "  3. Selecciona 'Ejecutar como Administrador'" "White"
    Write-ColorMessage "  4. Vuelve a ejecutar el script" "White"
    Write-ColorMessage ""
    Read-Host "Presiona Enter para salir"
    exit 1
}

# Iniciar servicio de impresion
Start-PrintSpooler

# Probar conexion con servidor
Test-ServerConnection -IP $ServerIP -Port $ServerPort

# Construir URL IPP
$IppUrl = "http://${ServerIP}:${ServerPort}/printers/${PrinterName}"
Write-ColorMessage "URL IPP: $IppUrl" "Cyan"
Write-Host ""

# Eliminar impresora existente si la hay
Remove-ExistingPrinter -Name $PrinterName

# Intentar instalar la impresora
$success = $false

# Metodo 1: PowerShell Add-Printer
$success = Install-IPPPrinterDirect -IppUrl $IppUrl

# Metodo 2: VBScript (si el primero fallo)
if (-not $success) {
    Write-ColorMessage "Intentando metodo alternativo..." "Yellow"
    $success = Install-IPPPrinterViaVBS -IppUrl $IppUrl
}

# Verificar resultado
Start-Sleep -Seconds 3

if ($success) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host "  INSTALACION COMPLETADA" -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host ""
    
    Show-InstalledPrinter
    
    Write-Host ""
    Write-ColorMessage "La impresora esta lista para usar" "Green"
    Write-ColorMessage "Puedes encontrarla en: Configuracion > Dispositivos > Impresoras" "White"
    Write-Host ""
    
    # Preguntar si desea imprimir pagina de prueba
    $testPrint = Read-Host "Deseas imprimir una pagina de prueba? (S/N)"
    
    if ($testPrint -eq "S" -or $testPrint -eq "s") {
        $printerObj = Get-Printer | Where-Object { $_.Type -eq "Connection" } | Select-Object -Last 1
        
        if ($printerObj) {
            try {
                $testFile = Join-Path $env:TEMP "test_print.txt"
                @"
============================================================
    PAGINA DE PRUEBA - IMPRESORA IPP
============================================================

Fecha: $(Get-Date -Format "dd/MM/yyyy HH:mm:ss")
Equipo: $env:COMPUTERNAME
Usuario: $env:USERNAME
Servidor: ${ServerIP}:${ServerPort}
Impresora: $PrinterName

Si puedes leer este texto, la configuracion es correcta.

============================================================
"@ | Out-File -FilePath $testFile -Encoding UTF8
                
                Get-Content $testFile | Out-Printer -Name $printerObj.Name
                Remove-Item $testFile -Force -ErrorAction SilentlyContinue
                
                Write-ColorMessage "Documento enviado a la impresora" "Green"
            } catch {
                Write-ColorMessage "Error al imprimir: $_" "Red"
            }
        }
    }
    
} else {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Red
    Write-Host "  NO SE PUDO INSTALAR AUTOMATICAMENTE" -ForegroundColor Red
    Write-Host "============================================================" -ForegroundColor Red
    
    Show-ManualSteps -IppUrl $IppUrl
}

Write-Host ""
Read-Host "Presiona Enter para salir"
exit 0
