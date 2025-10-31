package es.ucm.fdi.iu.control;

import es.ucm.fdi.iu.model.Printer;
import jakarta.persistence.EntityManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import es.ucm.fdi.iu.util.NetworkUtils;
import java.net.InetAddress;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controlador para el servidor de impresion centralizado
 * 
 * Proporciona:
 * - Lista de impresoras disponibles en formato IPP
 * - Scripts de instalacion para clientes
 * - Instrucciones de configuracion
 * 
 * Only loads in server mode (NOT in usb-client)
 */
@Controller
@RequestMapping("/print-server")
@ConditionalOnProperty(name = "app.mode", havingValue = "server", matchIfMissing = true)
public class PrinterServerController {

    private static final Logger log = LogManager.getLogger(PrinterServerController.class);

    @Autowired
    private EntityManager entityManager;
    
    @Autowired
    private es.ucm.fdi.iu.service.MultiPortIppServerService multiPortIppService;

    /**
     * Pagina principal del servidor de impresion
     */
        @GetMapping
    public String printServerIndex(Model model) {
                try {
            // Usar dominio para la interfaz web
            String serverHost = NetworkUtils.getServerHost();
            // Usar IP para IPP URIs (scripts .bat)
            String serverIp = NetworkUtils.getServerIp();
            model.addAttribute("serverIp", serverHost);
            model.addAttribute("serverIpForScripts", serverIp);
            
            List<Printer> printers = entityManager.createQuery(
                "SELECT p FROM Printer p ORDER BY p.id", Printer.class).getResultList();
            model.addAttribute("printers", printers);
            model.addAttribute("totalPrinters", printers.size());
            
                        // Generar URIs IPP para cada impresora con puerto dedicado
            // TODAS las impresoras usan el puerto del servidor (el servidor reenvía si es USB)
            // IMPORTANTE: IPP URIs usan IP (no dominio) para scripts .bat
            List<PrinterInfo> printerInfos = printers.stream()
                .map(p -> {
                    int port = multiPortIppService.getPortForPrinter(p);
                    return new PrinterInfo(
                        p.getAlias(),
                        p.getModel(),
                        p.getLocation(),
                        buildIppUriWithPort(serverIp, p.getAlias(), port),  // Usar IP para scripts
                        buildWindowsCommand(serverIp, p.getAlias()),
                        buildLinuxCommand(serverIp, p.getAlias()),
                        port
                    );
                })
                .collect(Collectors.toList());
            
            model.addAttribute("printerInfos", printerInfos);
            
            log.info("Mostrando servidor de impresion con {} impresoras", printers.size());
            
        } catch (Exception e) {
            log.error("Error al obtener informacion del servidor", e);
            model.addAttribute("serverIp", "localhost");
            model.addAttribute("printers", Collections.emptyList());
        }
        
        return "print-server";
    }

    /**
     * API REST: Lista todas las impresoras disponibles en formato JSON
     */
    @GetMapping("/api/printers")
        @ResponseBody
    public ResponseEntity<Map<String, Object>> listPrinters() {
                try {
            // Usar dominio para interfaz web
            String serverHost = NetworkUtils.getServerHost();
            // Usar IP para IPP URIs (scripts)
            String serverIp = NetworkUtils.getServerIp();
            List<Printer> printers = entityManager.createQuery(
                "SELECT p FROM Printer p ORDER BY p.id", Printer.class).getResultList();
            
            List<Map<String, Object>> printerList = printers.stream()
                .map(p -> {
                    int port = multiPortIppService.getPortForPrinter(p);
                    boolean isSharedUSB = p.getLocation() != null && p.getLocation().contains("Compartida-USB");
                    
                                        Map<String, Object> info = new HashMap<>();
                    info.put("id", p.getId());
                    info.put("name", p.getAlias());
                    info.put("model", p.getModel());
                    info.put("location", p.getLocation());
                    info.put("ip", serverIp);  // IP del servidor para scripts
                    info.put("port", port);  // Puerto del servidor
                    info.put("ippUri", buildIppUriWithPort(serverIp, p.getAlias(), port));  // Usar IP
                    info.put("isSharedUSB", isSharedUSB);
                    return info;
                })
                .collect(Collectors.toList());
            
                                    Map<String, Object> response = new HashMap<>();
            response.put("serverHost", serverHost);  // Dominio para web
            response.put("serverIp", serverIp);  // IP para scripts
            response.put("basePort", 8631);  // Puerto base (primera impresora)
            response.put("printers", printerList);
            response.put("total", printerList.size());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error en API de impresoras", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Descarga el script de PowerShell personalizado para una impresora especifica
     */
    @GetMapping("/download/windows-script/{printerName}")
        public ResponseEntity<String> downloadWindowsScriptForPrinter(@PathVariable String printerName) {
                try {
            // Usar IP para scripts .bat
            String serverHost = NetworkUtils.getServerIp();
            
            // Obtener el puerto especifico de esta impresora
            List<Printer> printers = entityManager.createQuery(
                "SELECT p FROM Printer p WHERE p.alias = :name", Printer.class)
                .setParameter("name", printerName)
                .getResultList();
            
            int port = 8631; // Puerto por defecto
            if (!printers.isEmpty()) {
                port = multiPortIppService.getPortForPrinter(printers.get(0));
            }
            
            String script = generateWindowsScriptForPrinter(serverHost, port, printerName);
            
            log.info("Generando script Windows personalizado para impresora: {} (Puerto: {})", printerName, port);
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=instalar-" + printerName + ".ps1")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("Error generando script Windows personalizado", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Descarga el script de Bash personalizado para una impresora especifica
     */
    @GetMapping("/download/linux-script/{printerName}")
        public ResponseEntity<String> downloadLinuxScriptForPrinter(@PathVariable String printerName) {
                try {
            // Usar IP para scripts Linux
            String serverHost = NetworkUtils.getServerIp();
            String script = generateLinuxScriptForPrinter(serverHost, printerName);
            
            log.info("Generando script Linux personalizado para impresora: {}", printerName);
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=configurar-" + printerName + ".sh")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("Error generando script Linux personalizado", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Descarga el script BAT para compartir impresora USB/Local (Windows)
     */
    @GetMapping("/download/share-windows-script")
    public ResponseEntity<String> downloadShareWindowsScript() {
        try {
            // Intentar primero desde el directorio del proyecto
            java.nio.file.Path batPath = java.nio.file.Paths.get("scripts/compartir-impresora-windows.bat");
            
            // Si no existe, intentar desde el classpath
            if (!java.nio.file.Files.exists(batPath)) {
                log.debug("Script BAT no encontrado en: {}, intentando classpath...", batPath.toAbsolutePath());
                
                // Intentar cargar desde resources
                try {
                    java.io.InputStream is = getClass().getResourceAsStream("/scripts/compartir-impresora-windows.bat");
                    if (is != null) {
                        String script = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.info("✅ Sirviendo script BAT de compartir impresora desde classpath ({} bytes)", script.length());
                        
                        return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=compartir-impresora-windows.bat")
                            .header("Content-Type", "application/x-bat; charset=UTF-8")
                            .body(script);
                    }
                } catch (Exception ex) {
                    log.debug("No se pudo cargar BAT desde classpath: {}", ex.getMessage());
                }
                
                log.error("❌ Script BAT de compartir no encontrado en ninguna ubicación");
                return ResponseEntity.notFound().build();
            }
            
            String script = new String(java.nio.file.Files.readAllBytes(batPath), java.nio.charset.StandardCharsets.UTF_8);
            
            log.info("✅ Sirviendo script BAT de compartir impresora: {} ({} bytes)", batPath.getFileName(), script.length());
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=compartir-impresora-windows.bat")
                .header("Content-Type", "application/x-bat; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("❌ Error leyendo script BAT de compartir", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Descarga el script de PowerShell para Windows desde archivo (generico)
     */
    @GetMapping("/download/windows-script")
    public ResponseEntity<String> downloadWindowsScript() {
        try {
            // Leer el script desde el archivo
            java.nio.file.Path scriptPath = java.nio.file.Paths.get("scripts/configurar-impresora.ps1");
            
            if (!java.nio.file.Files.exists(scriptPath)) {
                log.error("Script no encontrado: {}", scriptPath.toAbsolutePath());
                // Fallback: generar script básico
                String script = generateWindowsScript();
                return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=configurar-impresora.ps1")
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body(script);
            }
            
            // Leer contenido del archivo
            String script = new String(java.nio.file.Files.readAllBytes(scriptPath), java.nio.charset.StandardCharsets.UTF_8);
            
            log.info("Sirviendo script desde archivo: {} ({} bytes)", scriptPath.getFileName(), script.length());
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=configurar-impresora.ps1")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("Error leyendo script Windows", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Descarga el script de Bash para Linux
     */
    @GetMapping("/download/linux-script")
    public ResponseEntity<String> downloadLinuxScript() {
        try {
            String script = generateLinuxScript();
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=configurar-impresora.sh")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("Error generando script Linux", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * API: Genera comando de instalacion para una impresora especifica
     */
    @GetMapping("/api/install-command/{printerName}")
    @ResponseBody
        public ResponseEntity<Map<String, String>> getInstallCommand(@PathVariable String printerName) {
                try {
            // Usar IP para comandos de instalación
            String serverHost = NetworkUtils.getServerIp();
            
            // Buscar la impresora para obtener su puerto
            List<Printer> printers = entityManager.createQuery(
                "SELECT p FROM Printer p WHERE p.alias = :name", Printer.class)
                .setParameter("name", printerName)
                .getResultList();
            
            int port = 8631;
            if (!printers.isEmpty()) {
                port = multiPortIppService.getPortForPrinter(printers.get(0));
            }
            
                        Map<String, String> commands = new HashMap<>();
            commands.put("windows", buildWindowsCommand(serverHost, printerName));
            commands.put("linux", buildLinuxCommand(serverHost, printerName));
            commands.put("ippUri", buildIppUriWithPort(serverHost, printerName, port));
            commands.put("port", String.valueOf(port));
            
            return ResponseEntity.ok(commands);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // Metodos auxiliares
    
    private String buildIppUri(String serverIp, String printerName) {
        String safeName = printerName.replace(" ", "_");
        return String.format("ipp://%s:8631/printers/%s", serverIp, safeName);
    }
    
    private String buildIppUriWithPort(String serverIp, String printerName, int port) {
        String safeName = printerName.replace(" ", "_");
        return String.format("ipp://%s:%d/printers/%s", serverIp, port, safeName);
    }

    private String buildWindowsCommand(String serverIp, String printerName) {
        String safeName = printerName.replace(" ", "_");
        return String.format(
            ".\\\\add-printer.ps1 -ServerIP \"%s\" -PrinterName \"%s\"",
            serverIp, safeName
        );
    }

    private String buildLinuxCommand(String serverIp, String printerName) {
        String safeName = printerName.replace(" ", "_");
        return String.format(
            "./add-printer.sh %s %s",
            serverIp, safeName
        );
    }

    private String generateWindowsScript() {
        return "<#\n" +
            "=================================================================\n" +
            "Script PowerShell para Configurar Impresoras IPP en Cliente\n" +
            "=================================================================\n" +
            "\n" +
            "ESTE SCRIPT INSTALA Y CONFIGURA LA IMPRESORA EN TU COMPUTADOR\n" +
            "\n" +
            "La impresora se guardara con la IP UNICA del servidor central.\n" +
            "Esta IP contiene TODAS las impresoras escaneadas en el sistema.\n" +
            "\n" +
            "VENTAJAS:\n" +
            "  - Una sola IP centralizada para todas las impresoras\n" +
            "  - No necesitas saber la IP fisica de cada impresora\n" +
            "  - El servidor redirige automaticamente a la impresora correcta\n" +
            "  - Funciona sin importar en que VLAN este cada impresora\n" +
            "\n" +
            "INSTRUCCIONES DE USO:\n" +
            "\n" +
            "1. Guardar este script como: add-printer.ps1\n" +
            "\n" +
            "2. Abrir PowerShell como Administrador:\n" +
            "   - Presiona Windows + X\n" +
            "   - Selecciona Windows PowerShell (Admin) o Terminal (Admin)\n" +
            "\n" +
            "3. Permitir ejecucion de scripts (solo primera vez):\n" +
            "   Set-ExecutionPolicy RemoteSigned -Scope CurrentUser\n" +
            "\n" +
            "4. Navegar a la carpeta donde guardaste el script\n" +
            "\n" +
            "5. Ejecutar el script:\n" +
            "   .\\add-printer.ps1 -ServerIP \"192.168.1.100\" -PrinterName \"HP_LaserJet\"\n" +
            "\n" +
            "EJEMPLOS:\n" +
            "   .\\add-printer.ps1 -ServerIP \"192.168.1.100\" -PrinterName \"HP_LaserJet\"\n" +
            "   .\\add-printer.ps1 -ServerIP \"10.0.0.50\" -PrinterName \"Canon_MF620C\"\n" +
            "\n" +
            "IMPORTANTE:\n" +
            "   - ServerIP es SIEMPRE la IP del servidor central\n" +
            "   - NO uses la IP fisica de la impresora\n" +
            "   - El servidor contiene TODAS las impresoras disponibles\n" +
            "\n" +
            "REQUISITOS:\n" +
            "   - PowerShell como Administrador\n" +
            "   - Windows 10/11 o Windows Server 2016+\n" +
            "   - El servidor debe estar accesible en el puerto 8631\n" +
            "\n" +
            "=================================================================\n" +
            "#>\n" +
            "\n" +
            "param(\n" +
            "    [Parameter(Mandatory=$true)]\n" +
            "    [string]$ServerIP,\n" +
            "    \n" +
            "    [Parameter(Mandatory=$true)]\n" +
            "    [string]$PrinterName,\n" +
            "    \n" +
            "    [Parameter(Mandatory=$false)]\n" +
            "    [string]$PrinterAlias = $PrinterName\n" +
            ")\n" +
            "\n" +
            "# Verificar que se ejecuta como administrador\n" +
            "$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)\n" +
            "if (-not $isAdmin) {\n" +
            "    Write-Host \"ERROR: Este script debe ejecutarse como Administrador\" -ForegroundColor Red\n" +
            "    Write-Host \"\"\n" +
            "    Write-Host \"Presiona cualquier tecla para salir...\"\n" +
            "    $null = $Host.UI.RawUI.ReadKey(\"NoEcho,IncludeKeyDown\")\n" +
            "    exit 1\n" +
            "}\n" +
            "\n" +
            "Write-Host \"=========================================================\" -ForegroundColor Cyan\n" +
            "Write-Host \"  Configurando Impresora IPP en el Cliente\" -ForegroundColor Cyan\n" +
            "Write-Host \"=========================================================\" -ForegroundColor Cyan\n" +
            "Write-Host \"  Servidor Central: $ServerIP (IP UNICA)\" -ForegroundColor Green\n" +
            "Write-Host \"  Impresora Seleccionada: $PrinterName\"\n" +
            "Write-Host \"  Alias Local: $PrinterAlias\"\n" +
            "Write-Host \"=========================================================\" -ForegroundColor Cyan\n" +
            "Write-Host \"\"\n" +
            "Write-Host \"La impresora se instalara usando la IP del servidor central.\" -ForegroundColor Yellow\n" +
            "Write-Host \"Esta IP contiene TODAS las impresoras escaneadas.\" -ForegroundColor Yellow\n" +
            "Write-Host \"\"\n" +
            "\n" +
            "# Construir URI IPP - SIEMPRE usando la IP del servidor central\n" +
            "$PrinterUri = \"http://${ServerIP}:8631/printers/${PrinterName}\"\n" +
            "\n" +
            "# Verificar conectividad\n" +
            "Write-Host \"Verificando conectividad con el servidor...\" -ForegroundColor Yellow\n" +
            "$tcpClient = New-Object System.Net.Sockets.TcpClient\n" +
            "try {\n" +
            "    $tcpClient.Connect($ServerIP, 8631)\n" +
            "    $tcpClient.Close()\n" +
            "    Write-Host \"Servidor IPP accesible en puerto 8631\" -ForegroundColor Green\n" +
            "} catch {\n" +
            "    Write-Host \"ERROR: No se puede conectar al servidor\" -ForegroundColor Red\n" +
            "    Write-Host \"Verifica que:\" -ForegroundColor Yellow\n" +
            "    Write-Host \"  1. El servidor este encendido\"\n" +
            "    Write-Host \"  2. La IP sea correcta: $ServerIP\"\n" +
            "    Write-Host \"  3. El firewall permita conexiones al puerto 8631\"\n" +
            "    exit 1\n" +
            "}\n" +
            "\n" +
            "# Eliminar impresora si ya existe\n" +
            "$existingPrinter = Get-Printer -Name $PrinterName -ErrorAction SilentlyContinue\n" +
            "if ($existingPrinter) {\n" +
            "    Write-Host \"Impresora ya existe, eliminando...\" -ForegroundColor Yellow\n" +
            "    Remove-Printer -Name $PrinterName -Confirm:$false\n" +
            "}\n" +
            "\n" +
            "# Agregar impresora\n" +
            "Write-Host \"Instalando impresora en el sistema...\" -ForegroundColor Yellow\n" +
            "\n" +
            "try {\n" +
            "    # Crear puerto IPP\n" +
            "    $portName = \"IPP_${ServerIP}_${PrinterName}\"\n" +
            "    Add-PrinterPort -Name $portName -PrinterHostAddress $PrinterUri -ErrorAction Stop\n" +
            "    \n" +
            "    # Agregar impresora con driver generico IPP\n" +
            "    Add-Printer -Name $PrinterName `\n" +
            "                -PortName $portName `\n" +
            "                -DriverName \"Microsoft IPP Class Driver\" `\n" +
            "                -Comment \"Impresora IPP en $ServerIP\" `\n" +
            "                -Location $PrinterAlias `\n" +
            "                -ErrorAction Stop\n" +
            "    \n" +
            "    Write-Host \"\"\n" +
            "    Write-Host \"=========================================================\" -ForegroundColor Cyan\n" +
            "    Write-Host \"  CONFIGURACION COMPLETADA\" -ForegroundColor Green\n" +
            "    Write-Host \"=========================================================\" -ForegroundColor Cyan\n" +
            "    Write-Host \"\"\n" +
            "    Write-Host \"La impresora '$PrinterAlias' esta lista para usar.\" -ForegroundColor Green\n" +
            "    Write-Host \"\"\n" +
            "    Write-Host \"DETALLES DE INSTALACION:\" -ForegroundColor Cyan\n" +
            "    Write-Host \"  - IP Guardada: $ServerIP (Servidor Central)\" -ForegroundColor Green\n" +
            "    Write-Host \"  - Puerto: 8631 (IPP)\"\n" +
            "    Write-Host \"  - Impresora: $PrinterName\"\n" +
            "    Write-Host \"  - El servidor redirige a la impresora fisica automaticamente\"\n" +
            "    Write-Host \"\"\n" +
            "    Write-Host \"Puedes encontrarla en:\" -ForegroundColor Yellow\n" +
            "    Write-Host \"  Configuracion > Dispositivos > Impresoras y escaneres\"\n" +
            "    Write-Host \"\"\n" +
            "    Write-Host \"VENTAJA: Todas las impresoras usan la misma IP del servidor!\" -ForegroundColor Green\n" +
            "    Write-Host \"\"\n" +
            "    \n" +
            "} catch {\n" +
            "    Write-Host \"ERROR: No se pudo agregar la impresora\" -ForegroundColor Red\n" +
            "    Write-Host \"Detalles: $($_.Exception.Message)\" -ForegroundColor Red\n" +
            "    exit 1\n" +
            "}\n";
    }

    private String generateLinuxScript() {
        return "#!/bin/bash\n" +
            "# =================================================================\n" +
            "# Script para Configurar Impresoras IPP en Cliente Linux\n" +
            "# =================================================================\n" +
            "#\n" +
            "# ESTE SCRIPT INSTALA Y CONFIGURA LA IMPRESORA EN TU COMPUTADOR\n" +
            "#\n" +
            "# La impresora se guardara con la IP UNICA del servidor central.\n" +
            "# Esta IP contiene TODAS las impresoras escaneadas en el sistema.\n" +
            "#\n" +
            "# VENTAJAS:\n" +
            "#   - Una sola IP centralizada para todas las impresoras\n" +
            "#   - No necesitas saber la IP fisica de cada impresora\n" +
            "#   - El servidor redirige automaticamente a la impresora correcta\n" +
            "#   - Funciona sin importar en que VLAN este cada impresora\n" +
            "#\n" +
            "# INSTRUCCIONES DE USO:\n" +
            "#\n" +
            "# 1. Guardar este script como: add-printer.sh\n" +
            "#    \n" +
            "# 2. Dar permisos de ejecucion:\n" +
            "#    chmod +x add-printer.sh\n" +
            "#\n" +
            "# 3. Ejecutar el script:\n" +
            "#    ./add-printer.sh <IP-SERVIDOR-CENTRAL> <NOMBRE-IMPRESORA> [ALIAS]\n" +
            "#\n" +
            "# EJEMPLOS:\n" +
            "#    ./add-printer.sh 192.168.1.100 HP_LaserJet\n" +
            "#    ./add-printer.sh 192.168.1.100 HP_LaserJet \"Impresora Oficina\"\n" +
            "#\n" +
            "# IMPORTANTE:\n" +
            "#    - IP-SERVIDOR-CENTRAL es SIEMPRE la IP del servidor\n" +
            "#    - NO uses la IP fisica de la impresora\n" +
            "#    - El servidor contiene TODAS las impresoras disponibles\n" +
            "#\n" +
            "# REQUISITOS:\n" +
            "#    - CUPS instalado (sudo apt install cups)\n" +
            "#    - Permisos sudo para agregar impresoras\n" +
            "#    - El servidor debe estar accesible en el puerto 8631\n" +
            "#\n" +
            "# =================================================================\n" +
            "\n" +
            "set -e\n" +
            "\n" +
            "# Validar parametros\n" +
            "if [ $# -lt 2 ]; then\n" +
            "    echo \"ERROR: Faltan parametros\"\n" +
            "    echo \"\"\n" +
            "    echo \"Uso: $0 <server-ip> <printer-name> [alias]\"\n" +
            "    echo \"\"\n" +
            "    echo \"Ejemplo:\"\n" +
            "    echo \"  $0 192.168.1.100 HP_LaserJet 'Impresora Oficina'\"\n" +
            "    echo \"\"\n" +
            "    exit 1\n" +
            "fi\n" +
            "\n" +
            "SERVER_IP=\"$1\"\n" +
            "PRINTER_NAME=\"$2\"\n" +
            "PRINTER_ALIAS=\"${3:-$PRINTER_NAME}\"\n" +
            "PRINTER_URI=\"ipp://${SERVER_IP}:8631/printers/${PRINTER_NAME}\"\n" +
            "\n" +
            "echo \"=========================================================\"\n" +
            "echo \"  Configurando Impresora IPP en el Cliente\"\n" +
            "echo \"=========================================================\"\n" +
            "echo \"  Servidor Central: ${SERVER_IP} (IP UNICA)\"\n" +
            "echo \"  Impresora Seleccionada: ${PRINTER_NAME}\"\n" +
            "echo \"  Alias Local: ${PRINTER_ALIAS}\"\n" +
            "echo \"  URI de Conexion: ${PRINTER_URI}\"\n" +
            "echo \"=========================================================\"\n" +
            "echo \"\"\n" +
            "echo \"La impresora se instalara usando la IP del servidor central.\"\n" +
            "echo \"Esta IP contiene TODAS las impresoras escaneadas.\"\n" +
            "echo \"\"\n" +
            "\n" +
            "# Verificar si CUPS esta instalado\n" +
            "if ! command -v lpadmin &> /dev/null; then\n" +
            "    echo \"CUPS no esta instalado. Instalando...\"\n" +
            "    sudo apt-get update && sudo apt-get install -y cups\n" +
            "fi\n" +
            "\n" +
            "# Verificar conectividad\n" +
            "echo \"Verificando conectividad con el servidor...\"\n" +
            "if timeout 3 bash -c \"cat < /dev/null > /dev/tcp/${SERVER_IP}/8631\" 2>/dev/null; then\n" +
            "    echo \"Servidor IPP accesible\"\n" +
            "else\n" +
            "    echo \"ERROR: No se puede conectar al servidor\"\n" +
            "    exit 1\n" +
            "fi\n" +
            "\n" +
            "# Eliminar impresora si ya existe\n" +
            "if lpstat -p \"${PRINTER_NAME}\" &>/dev/null; then\n" +
            "    echo \"Impresora ya existe, eliminando...\"\n" +
            "    sudo lpadmin -x \"${PRINTER_NAME}\"\n" +
            "fi\n" +
            "\n" +
            "# Agregar impresora\n" +
            "echo \"Instalando impresora en el sistema...\"\n" +
            "sudo lpadmin -p \"${PRINTER_NAME}\" \\\n" +
            "    -E \\\n" +
            "    -v \"${PRINTER_URI}\" \\\n" +
            "    -D \"${PRINTER_ALIAS}\" \\\n" +
            "    -L \"Servidor: ${SERVER_IP}\" \\\n" +
            "    -m everywhere\n" +
            "\n" +
            "if [ $? -eq 0 ]; then\n" +
            "    echo \"Impresora agregada exitosamente\"\n" +
            "    \n" +
            "    # Habilitar impresora\n" +
            "    sudo cupsenable \"${PRINTER_NAME}\"\n" +
            "    sudo cupsaccept \"${PRINTER_NAME}\"\n" +
            "    \n" +
            "    echo \"\"\n" +
            "    echo \"=========================================================\"\n" +
            "    echo \"  CONFIGURACION COMPLETADA\"\n" +
            "    echo \"=========================================================\"\n" +
            "    echo \"\"\n" +
            "    echo \"La impresora '${PRINTER_ALIAS}' esta lista para usar.\"\n" +
            "    echo \"\"\n" +
            "    echo \"DETALLES DE INSTALACION:\"\n" +
            "    echo \"  - IP Guardada: ${SERVER_IP} (Servidor Central)\"\n" +
            "    echo \"  - Puerto: 8631 (IPP)\"\n" +
            "    echo \"  - Impresora: ${PRINTER_NAME}\"\n" +
            "    echo \"  - El servidor redirige a la impresora fisica automaticamente\"\n" +
            "    echo \"\"\n" +
            "    echo \"VENTAJA: Todas las impresoras usan la misma IP del servidor!\"\n" +
            "    echo \"\"\n" +
            "    echo \"Comandos utiles:\"\n" +
            "    echo \"  Ver estado:       lpstat -p ${PRINTER_NAME}\"\n" +
            "    echo \"  Imprimir prueba:  lp -d ${PRINTER_NAME} archivo.txt\"\n" +
            "    echo \"  Ver trabajos:     lpq -P ${PRINTER_NAME}\"\n" +
            "    echo \"\"\n" +
            "else\n" +
            "    echo \"ERROR: No se pudo agregar la impresora\"\n" +
            "    exit 1\n" +
            "fi\n";
    }

    /**
     * Genera script Windows personalizado para una impresora especifica
     */
    private String generateWindowsScriptForPrinter(String serverIp, int serverPort, String printerName) {
        // Leer el script base
        try {
            java.nio.file.Path scriptPath = java.nio.file.Paths.get("scripts/configurar-impresora.ps1");
            
            if (java.nio.file.Files.exists(scriptPath)) {
                String scriptBase = new String(java.nio.file.Files.readAllBytes(scriptPath), java.nio.charset.StandardCharsets.UTF_8);
                
                // Reemplazar los valores por defecto con los de la impresora seleccionada
                scriptBase = scriptBase.replace(
                    "[string]$ServerIP = \"10.1.1.79\"",
                    "[string]$ServerIP = \"" + serverIp + "\""
                );
                
                scriptBase = scriptBase.replace(
                    "$ServerPort = 8631",
                    "$ServerPort = " + serverPort
                );
                
                scriptBase = scriptBase.replace(
                    "[Parameter(Mandatory=$true)]",
                    "[Parameter(Mandatory=$false)]"
                );
                
                scriptBase = scriptBase.replace(
                    "[string]$PrinterName,",
                    "[string]$PrinterName = \"" + printerName + "\","
                );
                
                return scriptBase;
            }
        } catch (Exception e) {
            log.error("Error leyendo script base", e);
        }
        
        // Fallback: generar script inline con puerto correcto
        return "# Script personalizado para " + printerName + "\n" +
               "# Servidor: " + serverIp + "\n" +
               "# Puerto: " + serverPort + "\n\n" +
               "Write-Host 'Instalando impresora " + printerName + "...' -ForegroundColor Cyan\n" +
               "$serverIp = \"" + serverIp + "\"\n" +
               "$serverPort = " + serverPort + "\n" +
               "$printerName = \"" + printerName + "\"\n" +
               "$portName = \"IP_${serverIp}_${serverPort}\"\n\n" +
               "# Crear puerto TCP/IP\n" +
               "Add-PrinterPort -Name $portName -PrinterHostAddress $serverIp -PortNumber $serverPort -ErrorAction SilentlyContinue\n\n" +
               "# Agregar impresora\n" +
               "Add-Printer -Name $printerName -PortName $portName -DriverName \"Generic / Text Only\" -ErrorAction Stop\n" +
               "Write-Host 'Impresora instalada correctamente' -ForegroundColor Green\n";
    }
    
    /**
     * Genera script Linux personalizado para una impresora especifica
     */
    private String generateLinuxScriptForPrinter(String serverIp, String printerName) {
        return "#!/bin/bash\n" +
               "# Script personalizado para " + printerName + "\n" +
               "# Servidor: " + serverIp + "\n\n" +
               "SERVER_IP=\"" + serverIp + "\"\n" +
               "PRINTER_NAME=\"" + printerName + "\"\n" +
               "PRINTER_ALIAS=\"${PRINTER_NAME}\"\n" +
               "PRINTER_URI=\"ipp://${SERVER_IP}:8631/printers/${PRINTER_NAME}\"\n\n" +
               "echo \"Instalando impresora ${PRINTER_NAME}...\"\n\n" +
               "# Verificar si CUPS esta instalado\n" +
               "if ! command -v lpadmin &> /dev/null; then\n" +
               "    echo \"CUPS no esta instalado. Instalando...\"\n" +
               "    sudo apt-get update && sudo apt-get install -y cups\n" +
               "fi\n\n" +
               "# Agregar impresora\n" +
               "sudo lpadmin -p \"${PRINTER_NAME}\" -E -v \"${PRINTER_URI}\" -D \"${PRINTER_ALIAS}\" -m everywhere\n" +
               "sudo cupsenable \"${PRINTER_NAME}\"\n" +
               "sudo cupsaccept \"${PRINTER_NAME}\"\n\n" +
               "echo \"Impresora instalada correctamente\"\n";
    }

    // Clase interna para informacion de impresoras
    public static class PrinterInfo {
        public String name;
        public String model;
        public String location;
        public String ippUri;
        public String windowsCommand;
        public String linuxCommand;
        public int port;
        
        public PrinterInfo(String name, String model, String location, 
                          String ippUri, String windowsCommand, String linuxCommand, int port) {
            this.name = name;
            this.model = model;
            this.location = location;
            this.ippUri = ippUri;
            this.windowsCommand = windowsCommand;
            this.linuxCommand = linuxCommand;
            this.port = port;
        }
    }
}
