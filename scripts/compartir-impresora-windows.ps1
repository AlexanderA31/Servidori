# Script para Compartir Impresora USB/Local con el Servidor Central
# Version 2.0 - Sintaxis corregida

param(
    [string]$PrinterName = "",
    [string]$ServerIP = "10.1.16.31",
    [int]$ServerPort = 8080,
    [switch]$Install,
    [switch]$Uninstall,
    [switch]$Reset,
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
    
    Write-Log "Registrando impresora en el servidor central"
    Write-Host "  Impresora: $($Printer.Name)" -ForegroundColor White
    Write-Host "  Host: $($ComputerInfo.hostname)" -ForegroundColor White
    Write-Host "  IP: $($ComputerInfo.ip)" -ForegroundColor White
    Write-Host ""
    
    $data = @{
        alias = "$($Printer.Name)_$($ComputerInfo.hostname)"
        model = $Printer.DriverName
        ip = $ComputerInfo.ip
        location = "Compartida-USB - $($ComputerInfo.hostname) - Usuario: $($ComputerInfo.username)"
        protocol = "IPP"
        port = 631
    } | ConvertTo-Json
    
    $url = "http://${ServerIP}:${ServerPort}/api/register-shared-printer"
    
    Write-Host "Conectando al servidor: $url" -ForegroundColor Yellow
    
    try {
        $response = Invoke-RestMethod -Uri $url -Method Post -Body $data -ContentType "application/json" -TimeoutSec 15
        
        if ($response.success) {
            Write-Log "Impresora registrada en el servidor" "SUCCESS"
            Write-Host ""
            Write-Host "  Puerto IPP asignado: $($response.ippPort)" -ForegroundColor Green
            Write-Host "  ID en servidor: $($response.printerId)" -ForegroundColor Green
            Write-Host "  Nombre registrado: $($response.printerName)" -ForegroundColor Green
            Write-Host ""
            return $response
        } else {
            Write-Log "Error del servidor: $($response.error)" "ERROR"
            if ($response.existingPrinter) {
                Write-Host "  Ya existe una impresora con ese nombre: $($response.existingPrinter)" -ForegroundColor Yellow
            }
            return $null
        }
    } catch {
        Write-Log "Error de conexion con el servidor" "ERROR"
        Write-Log $_.Exception.Message "ERROR"
        Write-Host ""
        Write-Host "POSIBLES CAUSAS:" -ForegroundColor Red
        Write-Host "  1. El servidor no esta accesible en ${ServerIP}:${ServerPort}" -ForegroundColor Yellow
        Write-Host "  2. Firewall bloqueando la conexion" -ForegroundColor Yellow
        Write-Host "  3. Servidor de impresoras no esta ejecutandose" -ForegroundColor Yellow
        Write-Host ""
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
        [Parameter(Mandatory=$true)][int]$IppPort
    )
    
    Write-Log "Configurando comparticion de impresora"
    
    try {
        $shareName = ($Printer.Name -replace '[^a-zA-Z0-9_]', '_').Substring(0, [Math]::Min(($Printer.Name -replace '[^a-zA-Z0-9_]', '_').Length, 80))
        $existingShare = Get-Printer -Name $Printer.Name
        
        if (-not $existingShare.Shared) {
            Set-Printer -Name $Printer.Name -Shared $true -ShareName $shareName
            Write-Log "Compartida via SMB: \\$env:COMPUTERNAME\$shareName" "SUCCESS"
            Write-Host "  SMB Share: \\$env:COMPUTERNAME\$shareName" -ForegroundColor Green
        } else {
            Write-Log "Ya estaba compartida via SMB" "SUCCESS"
        }
        
        Write-Log "Configurando regla de firewall para puerto $IppPort"
        
        $ruleName = "PrinterShare_IPP_$IppPort"
        $existingRule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
        
        if (-not $existingRule) {
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $IppPort -Action Allow -Profile Any -Description "Permite acceso IPP a impresora compartida" | Out-Null
            Write-Log "Regla de firewall creada para puerto $IppPort" "SUCCESS"
            Write-Host "  Firewall: Puerto $IppPort abierto" -ForegroundColor Green
        } else {
            Write-Log "Regla de firewall ya existe" "SUCCESS"
        }
        
        Write-Host ""
        Write-Host "===================================================================" -ForegroundColor Cyan
        Write-Host "  INFORMACION DE CONEXION" -ForegroundColor Cyan
        Write-Host "===================================================================" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "  Otras computadoras pueden conectarse usando:" -ForegroundColor White
        Write-Host ""
        Write-Host "  Desde el Servidor de Impresoras:" -ForegroundColor Yellow
        Write-Host "     - La impresora aparecera automaticamente en la tabla" -ForegroundColor White
        Write-Host "     - Puerto IPP asignado: $IppPort" -ForegroundColor White
        Write-Host ""
        Write-Host "  Conexion directa SMB (Windows):" -ForegroundColor Yellow
        Write-Host "     \\$env:COMPUTERNAME\$shareName" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "  IMPORTANTE:" -ForegroundColor Red
        Write-Host "     - Esta computadora debe estar ENCENDIDA" -ForegroundColor White
        Write-Host "     - La impresora debe estar CONECTADA" -ForegroundColor White
        Write-Host "     - No suspender la computadora" -ForegroundColor White
        Write-Host ""
        
        return $true
    } catch {
        Write-Log "Error al configurar comparticion" "ERROR"
        Write-Log $_.Exception.Message "ERROR"
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
    
    if ($Reset) {
        if (Test-Path $ConfigFile) {
            Remove-Item $ConfigFile -Force
            Write-Log "Configuracion eliminada - se seleccionara nueva impresora" "SUCCESS"
        }
        Write-Host ""
        Write-Host "Configuracion reiniciada" -ForegroundColor Green
        Write-Host "Ahora puedes seleccionar una nueva impresora" -ForegroundColor Green
        Write-Host ""
    }
    
    $computerInfo = Get-ComputerInfo
    Write-Log "Computadora: $($computerInfo.hostname) ($($computerInfo.ip))"
    
    $config = Load-Configuration
    
    # SIEMPRE permitir seleccionar impresora si NO se especificó PrinterName
    $forceSelection = [string]::IsNullOrEmpty($PrinterName)
    
    if (-not $config -or $Install -or $forceSelection) {
        # Si no especificó PrinterName o si forzamos selección, mostrar menú
        if ([string]::IsNullOrEmpty($PrinterName) -or $forceSelection) {
            $selectedPrinter = Select-Printer
            if (-not $selectedPrinter) {
                Write-Host ""
                Write-Host "Presiona Enter para salir" -ForegroundColor Yellow
                Read-Host
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
            Write-Host "No se pudo registrar la impresora en el servidor" -ForegroundColor Red
            Write-Host "Verifica la conexion y que el servidor este funcionando" -ForegroundColor Yellow
            Write-Host ""
            Write-Host "Presiona Enter para salir" -ForegroundColor Yellow
            Read-Host
            exit 1
        }
        
        $assignedPort = if ($response.ippPort) { $response.ippPort } else { 631 }
        Write-Log "Puerto IPP asignado por el servidor: $assignedPort"
        
        $serviceStarted = Start-PrinterSharingService -Printer $selectedPrinter -IppPort $assignedPort
        
        $config = @{
            printerName = $PrinterName
            serverIP = $ServerIP
            serverPort = $ServerPort
            ippPort = $assignedPort
            printerId = if ($response.printerId) { $response.printerId } else { $null }
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
            $assignedPort = if ($response.ippPort) { $response.ippPort } else { if ($config.ippPort) { $config.ippPort } else { 631 } }
            Start-PrinterSharingService -Printer $selectedPrinter -IppPort $assignedPort
        }
    }
    
    Write-Host ""
    Write-Host "====================================================================" -ForegroundColor Green
    Write-Host "  CONFIGURACION COMPLETADA EXITOSAMENTE" -ForegroundColor Green
    Write-Host "====================================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "La impresora USB esta ahora compartida y registrada en el servidor" -ForegroundColor White
    Write-Host "Otras computadoras pueden conectarse a traves del servidor" -ForegroundColor White
    Write-Host ""
    Write-Host "Para ver el estado, accede al panel de administracion:" -ForegroundColor Cyan
    Write-Host "   http://${ServerIP}:${ServerPort}/admin/printers" -ForegroundColor Cyan
    Write-Host ""
    
    if (-not $Silent) {
        Write-Host "Presiona Enter para salir" -ForegroundColor Yellow
        Read-Host
    }
}

Main
