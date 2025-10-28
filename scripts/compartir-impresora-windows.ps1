# Script para Compartir Impresora USB/Local con el Servidor Central
# Version 2.0 - Sintaxis corregida

param(
    [string]$PrinterName = "",
    [string]$ServerIP = "10.1.16.31",
    [int]$ServerPort = 8080,
    [switch]$Install,
    [switch]$Uninstall,
    [switch]$Silent
)

$ScriptVersion = "2.0"
$LogFile = "$env:TEMP\compartir-impresora.log"
$ConfigFile = "$env:APPDATA\PrinterShare\config.json"
$TaskName = "CompartirImpresoraUSB"

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

function Test-Administrator {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-ComputerInfo {
    $ipAddress = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
        $_.InterfaceAlias -notlike "*Loopback*" -and $_.IPAddress -notlike "169.254.*"
    } | Select-Object -First 1).IPAddress
    
    return @{
        hostname = $env:COMPUTERNAME
        username = $env:USERNAME
        ip = $ipAddress
        os = (Get-WmiObject Win32_OperatingSystem).Caption
    }
}

function Get-LocalPrinters {
    $printers = Get-Printer | Where-Object {
        $_.Type -eq "Local" -or 
        $_.PortName -like "USB*" -or 
        $_.PortName -like "LPT*" -or
        $_.PortName -like "COM*"
    }
    return $printers
}

function Select-Printer {
    $printers = Get-LocalPrinters
    
    if ($printers.Count -eq 0) {
        Write-Log "No se encontraron impresoras USB/locales" "ERROR"
        return $null
    }
    
    Write-Host ""
    Write-Host "====================================================================" -ForegroundColor Cyan
    Write-Host "  Impresoras USB/Locales Disponibles" -ForegroundColor Cyan
    Write-Host "====================================================================" -ForegroundColor Cyan
    Write-Host ""
    
    for ($i = 0; $i -lt $printers.Count; $i++) {
        $printer = $printers[$i]
        $num = $i + 1
        Write-Host "[$num] $($printer.Name)" -ForegroundColor Yellow
        Write-Host "    Puerto: $($printer.PortName)" -ForegroundColor Gray
        Write-Host "    Driver: $($printer.DriverName)" -ForegroundColor Gray
        Write-Host ""
    }
    
    do {
        $maxNum = $printers.Count
        $selection = Read-Host "Selecciona el numero de la impresora (1-$maxNum)"
        $index = [int]$selection - 1
    } while ($index -lt 0 -or $index -ge $printers.Count)
    
    return $printers[$index]
}

function Register-PrinterWithServer {
    param(
        [Parameter(Mandatory=$true)]$Printer,
        [Parameter(Mandatory=$true)]$ComputerInfo
    )
    
    Write-Log "Registrando impresora en el servidor"
    
    $data = @{
        alias = "$($Printer.Name)@$($ComputerInfo.hostname)"
        model = $Printer.DriverName
        ip = $ComputerInfo.ip
        location = "USB - $($ComputerInfo.hostname) ($($ComputerInfo.username))"
        protocol = "SMB"
        port = 445
    } | ConvertTo-Json
    
    $url = "http://${ServerIP}:${ServerPort}/api/register-shared-printer"
    
    try {
        $response = Invoke-RestMethod -Uri $url -Method Post -Body $data -ContentType "application/json" -TimeoutSec 10
        
        if ($response.success) {
            Write-Log "Impresora registrada exitosamente" "SUCCESS"
            return $response
        } else {
            Write-Log "Error al registrar: $($response.error)" "ERROR"
            return $null
        }
    } catch {
        Write-Log "Error de conexion con el servidor" "ERROR"
        Write-Log $_.Exception.Message "ERROR"
        return $null
    }
}

function Save-Configuration {
    param([Parameter(Mandatory=$true)]$Config)
    
    $configDir = Split-Path -Parent $ConfigFile
    if (-not (Test-Path $configDir)) {
        New-Item -ItemType Directory -Path $configDir -Force | Out-Null
    }
    
    $Config | ConvertTo-Json | Set-Content -Path $ConfigFile
    Write-Log "Configuracion guardada"
}

function Load-Configuration {
    if (Test-Path $ConfigFile) {
        $config = Get-Content -Path $ConfigFile | ConvertFrom-Json
        return $config
    }
    return $null
}

function Install-ScheduledTask {
    param([Parameter(Mandatory=$true)][string]$PrinterName)
    
    Write-Log "Configurando inicio automatico"
    
    $scriptPath = $PSCommandPath
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -PrinterName `"$PrinterName`" -ServerIP `"$ServerIP`" -ServerPort $ServerPort -Silent"
    
    $action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $arguments
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest
    
    try {
        $existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($existingTask) {
            Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        }
        
        Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description "Comparte impresora USB con el servidor central" | Out-Null
        
        Write-Log "Inicio automatico configurado" "SUCCESS"
        return $true
    } catch {
        Write-Log "Error al configurar inicio automatico" "ERROR"
        return $false
    }
}

function Uninstall-ScheduledTask {
    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($task) {
            Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
            Write-Log "Inicio automatico desinstalado" "SUCCESS"
        }
    } catch {
        Write-Log "Error al desinstalar" "ERROR"
    }
}

function Start-PrinterSharingService {
    param(
        [Parameter(Mandatory=$true)]$Printer,
        [Parameter(Mandatory=$true)][int]$Port
    )
    
    Write-Log "Compartiendo impresora via SMB"
    
    try {
        $shareName = $Printer.Name -replace '[^a-zA-Z0-9]', ''
        $existingShare = Get-Printer -Name $Printer.Name | Where-Object {$_.Shared -eq $true}
        
        if (-not $existingShare) {
            Set-Printer -Name $Printer.Name -Shared $true -ShareName $shareName
            Write-Log "Impresora compartida como: \\$env:COMPUTERNAME\$shareName" "SUCCESS"
        }
        return $true
    } catch {
        Write-Log "Error al compartir impresora" "ERROR"
        return $false
    }
}

function Main {
    Write-Host ""
    Write-Host "====================================================================" -ForegroundColor Cyan
    Write-Host "  Compartir Impresora USB/Local - v$ScriptVersion" -ForegroundColor Cyan
    Write-Host "====================================================================" -ForegroundColor Cyan
    Write-Host ""
    
    Write-Log "Iniciando script"
    
    if (-not (Test-Administrator)) {
        Write-Log "Este script requiere permisos de administrador" "ERROR"
        Write-Host ""
        Write-Host "Presiona Enter para salir" -ForegroundColor Yellow
        Read-Host
        exit 1
    }
    
    if ($Uninstall) {
        Uninstall-ScheduledTask
        if (Test-Path $ConfigFile) {
            Remove-Item $ConfigFile -Force
            Write-Log "Configuracion eliminada"
        }
        Write-Host ""
        Write-Host "Presiona Enter para salir" -ForegroundColor Yellow
        Read-Host
        exit 0
    }
    
    $computerInfo = Get-ComputerInfo
    Write-Log "Computadora: $($computerInfo.hostname) ($($computerInfo.ip))"
    
    $config = Load-Configuration
    
    if (-not $config -or $Install) {
        if (-not $PrinterName) {
            $selectedPrinter = Select-Printer
            if (-not $selectedPrinter) {
                exit 1
            }
            $PrinterName = $selectedPrinter.Name
        } else {
            $selectedPrinter = Get-Printer -Name $PrinterName -ErrorAction SilentlyContinue
            if (-not $selectedPrinter) {
                Write-Log "Impresora no encontrada: $PrinterName" "ERROR"
                exit 1
            }
        }
        
        Write-Host ""
        Write-Host "====================================================================" -ForegroundColor Green
        Write-Host "  Impresora seleccionada: $PrinterName" -ForegroundColor Green
        Write-Host "====================================================================" -ForegroundColor Green
        Write-Host ""
        
        $response = Register-PrinterWithServer -Printer $selectedPrinter -ComputerInfo $computerInfo
        
        if (-not $response) {
            Write-Host ""
            Write-Host "Presiona Enter para salir" -ForegroundColor Yellow
            Read-Host
            exit 1
        }
        
        $serviceStarted = Start-PrinterSharingService -Printer $selectedPrinter -Port 445
        
        $config = @{
            printerName = $PrinterName
            serverIP = $ServerIP
            serverPort = $ServerPort
            registeredAt = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        }
        Save-Configuration -Config $config
        
        if ($Install) {
            Install-ScheduledTask -PrinterName $PrinterName
        }
        
    } else {
        Write-Log "Usando configuracion guardada"
        $PrinterName = $config.printerName
        
        $selectedPrinter = Get-Printer -Name $PrinterName -ErrorAction SilentlyContinue
        if (-not $selectedPrinter) {
            Write-Log "Impresora configurada no encontrada: $PrinterName" "ERROR"
            exit 1
        }
        
        $response = Register-PrinterWithServer -Printer $selectedPrinter -ComputerInfo $computerInfo
        
        if ($response) {
            Start-PrinterSharingService -Printer $selectedPrinter -Port 445
        }
    }
    
    Write-Host ""
    Write-Host "====================================================================" -ForegroundColor Green
    Write-Host "  CONFIGURACION COMPLETADA" -ForegroundColor Green
    Write-Host "====================================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "La impresora esta compartida con el servidor" -ForegroundColor White
    Write-Host ""
    
    if (-not $Silent) {
        Write-Host "Presiona Enter para salir" -ForegroundColor Yellow
        Read-Host
    }
}

Main
