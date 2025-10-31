# Script PowerShell para copiar certificados SSL al servidor Ubuntu
# Ejecutar desde Windows en el directorio del proyecto

param(
    [Parameter(Mandatory=$true)]
    [string]$ServidorIP = "10.1.16.31",
    
    [Parameter(Mandatory=$true)]
    [string]$Usuario = "tics"
)

Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  Copiar Certificados SSL al Servidor Ubuntu" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# Verificar que existen los archivos de certificado
$certFile = "ssl\STAR_ueb_edu_ec.crt"
$keyFile = "ssl\private.key"

if (-not (Test-Path $certFile)) {
    Write-Host "✗ Error: No se encontró el certificado en: $certFile" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $keyFile)) {
    Write-Host "✗ Error: No se encontró la llave privada en: $keyFile" -ForegroundColor Red
    exit 1
}

Write-Host "✓ Certificados encontrados:" -ForegroundColor Green
Write-Host "  - $certFile" -ForegroundColor White
Write-Host "  - $keyFile" -ForegroundColor White
Write-Host ""

Write-Host "Servidor de destino:" -ForegroundColor Yellow
Write-Host "  IP: $ServidorIP" -ForegroundColor White
Write-Host "  Usuario: $Usuario" -ForegroundColor White
Write-Host ""

# Verificar si SCP está disponible (viene con Git for Windows o Windows 10+)
$scpAvailable = Get-Command scp -ErrorAction SilentlyContinue

if (-not $scpAvailable) {
    Write-Host "✗ SCP no está disponible" -ForegroundColor Red
    Write-Host ""
    Write-Host "Opciones:" -ForegroundColor Yellow
    Write-Host "1. Instalar Git for Windows: https://git-scm.com/download/win" -ForegroundColor White
    Write-Host "2. Usar OpenSSH (Windows 10+): " -ForegroundColor White
    Write-Host "   Add-WindowsCapability -Online -Name OpenSSH.Client~~~~0.0.1.0" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "O copiar manualmente con WinSCP o FileZilla" -ForegroundColor Yellow
    exit 1
}

Write-Host "✓ SCP disponible" -ForegroundColor Green
Write-Host ""

# Copiar archivos usando SCP
Write-Host "Copiando archivos al servidor..." -ForegroundColor Yellow
Write-Host ""

try {
    # Crear directorio ssl en el home del usuario
    Write-Host "[1/3] Creando directorio ssl en el servidor..." -ForegroundColor Cyan
    ssh "${Usuario}@${ServidorIP}" "mkdir -p ~/ssl"
    
    # Copiar certificado
    Write-Host "[2/3] Copiando certificado..." -ForegroundColor Cyan
    scp $certFile "${Usuario}@${ServidorIP}:~/ssl/STAR_ueb_edu_ec.crt"
    
    # Copiar llave privada
    Write-Host "[3/3] Copiando llave privada..." -ForegroundColor Cyan
    scp $keyFile "${Usuario}@${ServidorIP}:~/ssl/private.key"
    
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "  ✓ ARCHIVOS COPIADOS EXITOSAMENTE" -ForegroundColor Green
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host ""
    Write-Host "Archivos copiados a: ${Usuario}@${ServidorIP}:~/ssl/" -ForegroundColor White
    Write-Host ""
    Write-Host "SIGUIENTE PASO:" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Conecta al servidor y ejecuta:" -ForegroundColor White
    Write-Host "  ssh ${Usuario}@${ServidorIP}" -ForegroundColor Cyan
    Write-Host "  cd ~/Servidori" -ForegroundColor Cyan
    Write-Host "  chmod +x configurar-ssl-propio.sh" -ForegroundColor Cyan
    Write-Host "  sudo ./configurar-ssl-propio.sh" -ForegroundColor Cyan
    Write-Host ""
    
} catch {
    Write-Host ""
    Write-Host "✗ Error al copiar archivos" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ""
    Write-Host "ALTERNATIVA - Copiar manualmente:" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "1. Conecta al servidor: ssh ${Usuario}@${ServidorIP}" -ForegroundColor White
    Write-Host "2. Crea directorio: mkdir -p ~/ssl" -ForegroundColor White
    Write-Host ""
    Write-Host "3. Desde esta PC, copia los archivos:" -ForegroundColor White
    Write-Host "   scp ssl\STAR_ueb_edu_ec.crt ${Usuario}@${ServidorIP}:~/ssl/" -ForegroundColor Cyan
    Write-Host "   scp ssl\private.key ${Usuario}@${ServidorIP}:~/ssl/" -ForegroundColor Cyan
    Write-Host ""
    exit 1
}
