# =============================================================================
# Script para Compartir Impresora USB/Local con el Servidor Central
# =============================================================================
#
# PROPÓSITO:
#   Este script registra una impresora USB/Local conectada a esta computadora
#   en el servidor central, permitiendo que otras computadoras la usen.
#
# USO:
#   .\compartir-impresora-windows.ps1 -PrinterName "HP LaserJet USB" -ServerIP "10.1.16.31"
#
# INSTALACIÓN AUTOMÁTICA AL INICIO:
#   El script se auto-configura para ejecutarse al iniciar Windows
#
# =============================================================================

param(
    [Parameter(Mandatory=$false)]
    [string]$PrinterName = "",
    
        [Parameter(Mandatory=$false)]
    [string]$ServerHost = "ueb-impresoras.ueb.edu.ec",
    
    [Parameter(Mandatory=$false)]
    [int]$ServerPort = 80,
    
    [Parameter(Mandatory=$false)]
    [switch]$Install,
    
    [Parameter(Mandatory=$false)]
    [switch]$Uninstall,
    
    [Parameter(Mandatory=$false)]
    [switch]$Silent
)

# Variables globales
$ScriptVersion = "1.0"
$ScriptName = "CompartirImpresoraUSB"
$LogFile = "$env:TEMP\compartir-impresora.log"
$ConfigFile = "$env:APPDATA\PrinterShare\config.json"
$TaskName = "CompartirImpresoraUSB"

# Función para escribir log
function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $logMessage = "[$timestamp] [$Level] $Message"
    Add-Content -Path $LogFile -Value $logMessage
    
    if (-not $Silent) {
        switch ($Level) {
            "ERROR" { Write-Host $logMessage -ForegroundColor Red }
            "WARN"  { Write-Host $logMessage -ForegroundColor Yellow }
            "SUCCESS" { Write-Host $logMessage -ForegroundColor Green }
            default { Write-Host $logMessage }
        }
    }
}

# Función para verificar permisos de administrador
function Test-Administrator {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# Función para obtener información de la computadora
function Get-ComputerInfo {
    return @{
        hostname = $env:COMPUTERNAME
        username = $env:USERNAME
        ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.InterfaceAlias -notlike "*Loopback*" -and $_.IPAddress -notlike "169.254.*"} | Select-Object -First 1).IPAddress
        os = (Get-WmiObject Win32_OperatingSystem).Caption
    }
}

# Función para listar impresoras locales
function Get-LocalPrinters {
    $printers = Get-Printer | Where-Object {
        $_.Type -eq "Local" -or 
        $_.PortName -like "USB*" -or 
        $_.PortName -like "LPT*" -or
        $_.PortName -like "COM*"
    }
    return $printers
}

# Función para mostrar menú de selección de impresora
function Select-Printer {
    $printers = Get-LocalPrinters
    
    if ($printers.Count -eq 0) {
        Write-Log "No se encontraron impresoras USB/locales en esta computadora" "ERROR"
        return $null
    }
    
    Write-Host ""
    Write-Host "════════════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  Impresoras USB/Locales Disponibles" -ForegroundColor Cyan
    Write-Host "════════════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host ""
    
    for ($i = 0; $i -lt $printers.Count; $i++) {
        $printer = $printers[$i]
        Write-Host "[$($i + 1)] $($printer.Name)" -ForegroundColor Yellow
        Write-Host "    Puerto: $($printer.PortName)" -ForegroundColor Gray
        Write-Host "    Driver: $($printer.DriverName)" -ForegroundColor Gray
        Write-Host ""
    }
    
    do {
        $selection = Read-Host "Selecciona el número de la impresora a compartir (1-$($printers.Count))"
        $index = [int]$selection - 1
    } while ($index -lt 0 -or $index -ge $printers.Count)
    
    return $printers[$index]
}

# Función para registrar impresora en el servidor
function Register-PrinterWithServer {
    param(
        [Parameter(Mandatory=$true)]
        $Printer,
        
        [Parameter(Mandatory=$true)]
        $ComputerInfo
    )
    
    Write-Log "Registrando impresora '$($Printer.Name)' en el servidor"
    
    # Preparar datos JSON
    $data = @{
        printerName = $Printer.Name
        printerDriver = $Printer.DriverName
        printerPort = $Printer.PortName
        computerName = $ComputerInfo.hostname
        computerIP = $ComputerInfo.ip
        computerUser = $ComputerInfo.username
        computerOS = $ComputerInfo.os
        shareType = "USB"
        timestamp = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    } | ConvertTo-Json
    
    $url = "http://${ServerHost}:${ServerPort}/api/register-shared-printer"
    
    try {
        $response = Invoke-RestMethod -Uri $url -Method Post -Body $data -ContentType "application/json" -TimeoutSec 10
        
        if ($response.success) {
            Write-Log "✅ Impresora registrada exitosamente en el servidor" "SUCCESS"
            Write-Log "Puerto IPP asignado: $($response.ippPort)" "SUCCESS"
            return $response
        } else {
            Write-Log "❌ Error al registrar: $($response.message)" "ERROR"
            return $null
        }
    } catch {
        Write-Log "❌ Error de conexión con el servidor: $_" "ERROR"
        Write-Log "Verifica que el servidor esté accesible en: $url" "WARN"
        return $null
    }
}

# Función para guardar configuración
function Save-Configuration {
    param(
        [Parameter(Mandatory=$true)]
        $Config
    )
    
    $configDir = Split-Path -Parent $ConfigFile
    if (-not (Test-Path $configDir)) {
        New-Item -ItemType Directory -Path $configDir -Force | Out-Null
    }
    
    $Config | ConvertTo-Json | Set-Content -Path $ConfigFile
    Write-Log "Configuración guardada en: $ConfigFile"
}

# Función para cargar configuración
function Load-Configuration {
    if (Test-Path $ConfigFile) {
        $config = Get-Content -Path $ConfigFile | ConvertFrom-Json
        return $config
    }
    return $null
}

# Función para instalar tarea programada
function Install-ScheduledTask {
    param(
        [Parameter(Mandatory=$true)]
        [string]$PrinterName
    )
    
    Write-Log "Configurando inicio automático"
    
    $scriptPath = $PSCommandPath
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -PrinterName `"$PrinterName`" -ServerHost `"$ServerHost`" -ServerPort $ServerPort -Silent"
    
    # Crear acción
    $action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $arguments
    
    # Crear trigger (al iniciar sesión)
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    
    # Crear configuración
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
    
    # Crear principal (usuario actual)
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest
    
    # Registrar tarea
    try {
        # Eliminar tarea existente si existe
        $existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($existingTask) {
            Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        }
        
        Register-ScheduledTask -TaskName $TaskName `
            -Action $action `
            -Trigger $trigger `
            -Settings $settings `
            -Principal $principal `
            -Description "Comparte impresora USB '$PrinterName' con el servidor central" | Out-Null
        
        Write-Log "✅ Inicio automático configurado exitosamente" "SUCCESS"
        Write-Log "La impresora se compartirá automáticamente al iniciar Windows" "SUCCESS"
        return $true
    } catch {
        Write-Log "❌ Error al configurar inicio automático: $_" "ERROR"
        return $false
    }
}

# Función para desinstalar tarea programada
function Uninstall-ScheduledTask {
    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($task) {
            Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
            Write-Log "✅ Inicio automático desinstalado" "SUCCESS"
        } else {
            Write-Log "No hay inicio automático configurado" "WARN"
        }
    } catch {
        Write-Log "❌ Error al desinstalar: $_" "ERROR"
    }
}

# Función para iniciar servidor SMB local (para recibir trabajos de impresión)
function Start-PrinterSharingService {
    param(
        [Parameter(Mandatory=$true)]
        $Printer,
        
        [Parameter(Mandatory=$true)]
        [int]$Port
    )
    
    Write-Log "Iniciando servicio de recepción de trabajos de impresión"
    Write-Log "Puerto asignado: $Port"
    
    # Aquí se podría iniciar un listener TCP para recibir trabajos
    # Por simplicidad, usamos SMB nativo de Windows
    
    try {
        # Compartir impresora via SMB
        $shareName = $Printer.Name -replace '[^a-zA-Z0-9]', ''
        
        # Verificar si ya está compartida
        $existingShare = Get-Printer -Name $Printer.Name | Where-Object {$_.Shared -eq $true}
        
        if (-not $existingShare) {
            Set-Printer -Name $Printer.Name -Shared $true -ShareName $shareName
            Write-Log "✅ Impresora compartida como: \\$env:COMPUTERNAME\$shareName" "SUCCESS"
        } else {
            Write-Log "Impresora ya estaba compartida"
        }
        
        return $true
    } catch {
        Write-Log "❌ Error al compartir impresora: $_" "ERROR"
        return $false
    }
}

# ==============================================================================
# FUNCIÓN PRINCIPAL
# ==============================================================================

function Main {
    Write-Host ""
    Write-Host "════════════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  Compartir Impresora USB/Local - v$ScriptVersion" -ForegroundColor Cyan
    Write-Host "════════════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host ""
    
    Write-Log "Iniciando script"
    
    # Verificar permisos de administrador
    if (-not (Test-Administrator)) {
        Write-Log "❌ Este script requiere permisos de administrador" "ERROR"
        Write-Host ""
        Write-Host "Presiona cualquier tecla para salir" -ForegroundColor Yellow
        $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
        exit 1
    }
    
    # Manejar desinstalación
    if ($Uninstall) {
        Uninstall-ScheduledTask
        
        # Eliminar configuración
        if (Test-Path $ConfigFile) {
            Remove-Item $ConfigFile -Force
            Write-Log "Configuración eliminada"
        }
        
        Write-Host ""
        Write-Host "Presiona cualquier tecla para salir" -ForegroundColor Yellow
        $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
        exit 0
    }
    
    # Obtener información de la computadora
    $computerInfo = Get-ComputerInfo
    Write-Log "Computadora: $($computerInfo.hostname) ($($computerInfo.ip))"
    Write-Log "Usuario: $($computerInfo.username)"
    
    # Cargar configuración guardada o seleccionar impresora
    $config = Load-Configuration
    
    if (-not $config -or $Install) {
        # Seleccionar impresora
        if (-not $PrinterName) {
            $selectedPrinter = Select-Printer
            if (-not $selectedPrinter) {
                exit 1
            }
            $PrinterName = $selectedPrinter.Name
        } else {
            $selectedPrinter = Get-Printer -Name $PrinterName -ErrorAction SilentlyContinue
            if (-not $selectedPrinter) {
                Write-Log "❌ Impresora '$PrinterName' no encontrada" "ERROR"
                exit 1
            }
        }
        
        Write-Host ""
        Write-Host "════════════════════════════════════════════════════════════" -ForegroundColor Green
        Write-Host "  Impresora seleccionada: $PrinterName" -ForegroundColor Green
        Write-Host "════════════════════════════════════════════════════════════" -ForegroundColor Green
        Write-Host ""
        
        # Registrar en el servidor
        $response = Register-PrinterWithServer -Printer $selectedPrinter -ComputerInfo $computerInfo
        
        if (-not $response) {
            Write-Host ""
            Write-Host "Presiona cualquier tecla para salir" -ForegroundColor Yellow
            $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
            exit 1
        }
        
        # Iniciar servicio de compartición
        $serviceStarted = Start-PrinterSharingService -Printer $selectedPrinter -Port $response.ippPort
        
        if (-not $serviceStarted) {
            Write-Log "❌ No se pudo iniciar el servicio de compartición" "ERROR"
        }
        
        # Guardar configuración
                $config = @{
            printerName = $PrinterName
            serverHost = $ServerHost
            serverPort = $ServerPort
            ippPort = $response.ippPort
            registeredAt = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        }
        Save-Configuration -Config $config
        
        # Instalar tarea programada si es instalación
        if ($Install) {
            Install-ScheduledTask -PrinterName $PrinterName
        }
        
    } else {
        # Usar configuración guardada
        Write-Log "Usando configuración guardada"
        $PrinterName = $config.printerName
        
        $selectedPrinter = Get-Printer -Name $PrinterName -ErrorAction SilentlyContinue
        if (-not $selectedPrinter) {
            Write-Log "❌ Impresora configurada '$PrinterName' no encontrada" "ERROR"
            Write-Log "Ejecuta el script con -Install para reconfigurar" "WARN"
            exit 1
        }
        
        # Re-registrar en el servidor
        $response = Register-PrinterWithServer -Printer $selectedPrinter -ComputerInfo $computerInfo
        
        if ($response) {
            Start-PrinterSharingService -Printer $selectedPrinter -Port $response.ippPort
        }
    }
    
    Write-Host ""
    Write-Host "════════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "  ✅ CONFIGURACIÓN COMPLETADA" -ForegroundColor Green
    Write-Host "════════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host ""
    Write-Host "La impresora '$PrinterName' ahora está compartida con el servidor" -ForegroundColor White
    Write-Host "Otras computadoras pueden usarla conectándose a:" -ForegroundColor White
    $ippUri = "ipp://${ServerHost}:$($config.ippPort)/printers/$($PrinterName -replace ' ', '_')"
    Write-Host "  $ippUri" -ForegroundColor Cyan
    Write-Host ""
    
    if (-not $Silent) {
        Write-Host "Presiona cualquier tecla para salir" -ForegroundColor Yellow
        $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
    }
}

# Ejecutar función principal
Main
