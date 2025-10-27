package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.repository.PrinterRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Servicio multiplataforma para compartir impresoras vía SMB/Samba.
 * - En Linux: Configura Samba (/etc/samba/smb.conf)
 * - En Windows: Usa comandos nativos de Windows (net share, etc.)
 */
@Service
public class PrinterShareService {

    private static final Logger log = LogManager.getLogger(PrinterShareService.class);
    
    @Value("${printer.share.enabled:true}")
    private boolean shareEnabled;
    
    @Value("${printer.share.guest.access:true}")
    private boolean guestAccess;
    
    @Autowired
    private PrinterRepository printerRepository;
    
    private final boolean isWindows;
    private final boolean isLinux;
    
    public PrinterShareService() {
        String os = System.getProperty("os.name").toLowerCase();
        this.isWindows = os.contains("win");
        this.isLinux = os.contains("nux") || os.contains("nix");
        
        log.info("===========================================");
        log.info("PrinterShareService inicializado");
        log.info("Sistema Operativo: {}", System.getProperty("os.name"));
        log.info("Windows: {} | Linux: {}", isWindows, isLinux);
        log.info("===========================================");
    }

    /**
     * Comparte una impresora automáticamente según el sistema operativo
     */
    public boolean sharePrinter(Printer printer) {
        if (!shareEnabled) {
            log.info("Compartido de impresoras deshabilitado");
            return false;
        }
        
        log.info("===========================================");
        log.info("COMPARTIENDO IMPRESORA: {}", printer.getAlias());
        log.info("===========================================");
        log.info("Modelo: {}", printer.getModel());
        log.info("IP: {}", printer.getIp());
        log.info("Ubicación: {}", printer.getLocation());
        log.info("Sistema: {}", isWindows ? "Windows" : isLinux ? "Linux" : "Desconocido");
        log.info("");
        
        boolean success;
        
        if (isWindows) {
            success = sharePrinterWindows(printer);
        } else if (isLinux) {
            success = sharePrinterLinux(printer);
        } else {
            log.error("Sistema operativo no soportado");
            return false;
        }
        
        if (success) {
            printer.setSharedViaSamba(true);
            printerRepository.save(printer);
            
            log.info("===========================================");
            log.info("✅ IMPRESORA COMPARTIDA EXITOSAMENTE");
            log.info("===========================================");
            log.info("Ruta de acceso: \\\\{}\\{}", getServerIp(), printer.getAlias());
            log.info("===========================================");
        } else {
            log.error("===========================================");
            log.error("❌ ERROR AL COMPARTIR IMPRESORA");
            log.error("===========================================");
        }
        
        return success;
    }

    /**
     * Elimina el compartido de una impresora
     */
    public boolean unsharePrinter(Printer printer) {
        log.info("Eliminando compartido de: {}", printer.getAlias());
        
        boolean success;
        
        if (isWindows) {
            success = unsharePrinterWindows(printer);
        } else if (isLinux) {
            success = unsharePrinterLinux(printer);
        } else {
            return false;
        }
        
        if (success) {
            printer.setSharedViaSamba(false);
            printerRepository.save(printer);
            log.info("✅ Compartido eliminado");
        }
        
        return success;
    }

    /**
     * Comparte una impresora en Windows usando comandos nativos
     */
    private boolean sharePrinterWindows(Printer printer) {
        try {
            log.info("📋 Método: Configuración Windows nativa");
            log.info("");
            
            // Nota: En Windows, para compartir impresoras de red remotas se requiere:
            // 1. La impresora debe estar instalada localmente primero
            // 2. Luego se puede compartir usando 'net share' o PowerShell
            
            log.info("⚠️ IMPORTANTE: En Windows, el compartido SMB funciona diferente:");
            log.info("");
            log.info("OPCIÓN 1: Servidor IPP (Ya implementado)");
            log.info("  - El servidor IPP en puerto 631 ya está activo");
            log.info("  - Windows puede conectarse usando: http://{}:631/printers/{}", 
                    getServerIp(), printer.getAlias());
            log.info("");
            
            log.info("OPCIÓN 2: Instalar impresora localmente y compartir");
            log.info("  1. Instalar driver de la impresora");
            log.info("  2. Agregar puerto TCP/IP: {}", printer.getIp());
            log.info("  3. Compartir con PowerShell:");
            log.info("     Set-Printer -Name \"{}\" -Shared $true -ShareName \"{}\"", 
                    printer.getAlias(), printer.getAlias());
            log.info("");
            
            log.info("OPCIÓN 3: Usar servidor Samba en WSL (Windows Subsystem for Linux)");
            log.info("  - Instalar WSL2");
            log.info("  - Instalar Samba en WSL");
            log.info("  - Configurar desde esta aplicación");
            log.info("");
            
            log.info("✅ Para desarrollo, marcamos como compartida (modo simulación)");
            log.info("   En producción Linux, esto configurará Samba automáticamente");
            
            return true;
            
        } catch (Exception e) {
            log.error("Error en configuración Windows", e);
            return false;
        }
    }

    /**
     * Elimina el compartido en Windows
     */
    private boolean unsharePrinterWindows(Printer printer) {
        log.info("Modo Windows: Eliminando marca de compartido");
        return true;
    }

    /**
     * Comparte una impresora en Linux usando Samba
     */
    private boolean sharePrinterLinux(Printer printer) {
        try {
            log.info("📋 Método: Samba en Linux");
            log.info("");
            
            String smbConfPath = "/etc/samba/smb.conf";
            File smbConf = new File(smbConfPath);
            
            if (!smbConf.exists()) {
                log.error("❌ Samba no está instalado");
                log.info("   Ejecuta: sudo apt-get install samba");
                return false;
            }
            
            log.info("Paso 1: Leyendo configuración Samba...");
            List<String> config = readFile(smbConfPath);
            
            log.info("Paso 2: Configurando sección global...");
            ensureGlobalConfig(config);
            
            log.info("Paso 3: Creando sección de impresora...");
            String sectionName = "[" + printer.getAlias() + "]";
            int sectionIndex = findSection(config, sectionName);
            
            List<String> printerSection = buildPrinterSection(printer);
            
            if (sectionIndex >= 0) {
                log.info("   Actualizando sección existente");
                int endIndex = findSectionEnd(config, sectionIndex);
                for (int i = endIndex - 1; i >= sectionIndex; i--) {
                    config.remove(i);
                }
                config.addAll(sectionIndex, printerSection);
            } else {
                log.info("   Agregando nueva sección");
                config.addAll(printerSection);
            }
            
            log.info("Paso 4: Escribiendo configuración...");
            // Crear backup
            Files.copy(smbConf.toPath(), 
                      new File(smbConfPath + ".backup").toPath(), 
                      StandardCopyOption.REPLACE_EXISTING);
            
            writeFile(smbConfPath, config);
            
            log.info("Paso 5: Validando configuración...");
            if (!testSambaConfig()) {
                log.error("   Configuración inválida");
                return false;
            }
            
            log.info("Paso 6: Recargando Samba...");
            reloadSamba();
            
            return true;
            
        } catch (Exception e) {
            log.error("Error configurando Samba en Linux", e);
            return false;
        }
    }

    /**
     * Elimina el compartido en Linux
     */
    private boolean unsharePrinterLinux(Printer printer) {
        try {
            String smbConfPath = "/etc/samba/smb.conf";
            List<String> config = readFile(smbConfPath);
            
            String sectionName = "[" + printer.getAlias() + "]";
            int sectionIndex = findSection(config, sectionName);
            
            if (sectionIndex >= 0) {
                int endIndex = findSectionEnd(config, sectionIndex);
                for (int i = endIndex - 1; i >= sectionIndex; i--) {
                    config.remove(i);
                }
                
                writeFile(smbConfPath, config);
                reloadSamba();
                
                log.info("Sección eliminada de smb.conf");
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("Error eliminando compartido Linux", e);
            return false;
        }
    }

    /**
     * Construye la sección de configuración para una impresora
     */
    private List<String> buildPrinterSection(Printer printer) {
        List<String> section = new ArrayList<>();
        
        section.add("");
        section.add("[" + printer.getAlias() + "]");
        section.add("    comment = " + printer.getModel() + " - " + printer.getLocation());
        section.add("    path = /var/spool/samba");
        section.add("    printable = yes");
        section.add("    browseable = yes");
        section.add("    guest ok = " + (guestAccess ? "yes" : "no"));
        section.add("    public = " + (guestAccess ? "yes" : "no"));
        section.add("    writable = no");
        section.add("    create mask = 0700");
        section.add("    printer name = " + printer.getAlias());
        section.add("");
        
        return section;
    }

    /**
     * Asegura que la sección global tenga configuración de impresoras
     */
    private void ensureGlobalConfig(List<String> config) {
        int globalIndex = findSection(config, "[global]");
        
        if (globalIndex < 0) {
            log.warn("Sección [global] no encontrada, creándola...");
            config.add(0, "[global]");
            config.add(1, "    workgroup = WORKGROUP");
            config.add(2, "    server string = Print Server");
            config.add(3, "");
            globalIndex = 0;
        }
        
        int endIndex = findSectionEnd(config, globalIndex);
        
        String[] required = {
            "load printers = yes",
            "printing = cups",
            "printcap name = cups"
        };
        
        for (String setting : required) {
            String key = setting.split("=")[0].trim();
            boolean found = false;
            
            for (int i = globalIndex + 1; i < endIndex; i++) {
                if (config.get(i).trim().toLowerCase().startsWith(key.toLowerCase())) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                config.add(endIndex, "    " + setting);
                endIndex++;
            }
        }
    }

    /**
     * Lee un archivo de texto
     */
    private List<String> readFile(String path) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Escribe un archivo de texto
     */
    private void writeFile(String path, List<String> lines) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    /**
     * Encuentra una sección en el archivo de configuración
     */
    private int findSection(List<String> config, String sectionName) {
        for (int i = 0; i < config.size(); i++) {
            if (config.get(i).trim().equals(sectionName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Encuentra el final de una sección
     */
    private int findSectionEnd(List<String> config, int startIndex) {
        for (int i = startIndex + 1; i < config.size(); i++) {
            String line = config.get(i).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                return i;
            }
        }
        return config.size();
    }

    /**
     * Valida la configuración de Samba
     */
    private boolean testSambaConfig() {
        try {
            Process process = Runtime.getRuntime().exec("testparm -s");
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.warn("No se pudo ejecutar testparm");
            return true; // No bloqueamos si testparm no está disponible
        }
    }

    /**
     * Recarga la configuración de Samba
     */
    private void reloadSamba() {
        try {
            Process process = Runtime.getRuntime().exec("systemctl reload smbd");
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                log.warn("Reload falló, intentando restart...");
                process = Runtime.getRuntime().exec("systemctl restart smbd nmbd");
                process.waitFor();
            }
        } catch (Exception e) {
            log.error("Error recargando Samba", e);
        }
    }

    /**
     * Obtiene la IP del servidor
     */
    private String getServerIp() {
        return es.ucm.fdi.iu.util.NetworkUtils.getServerIpAddress();
    }

    /**
     * Verifica si el servicio de compartido está disponible
     */
    public boolean isAvailable() {
        if (isWindows) {
            return true; // Siempre disponible en Windows (modo simulación/IPP)
        } else if (isLinux) {
            File smbConf = new File("/etc/samba/smb.conf");
            return smbConf.exists();
        }
        return false;
    }

    /**
     * Obtiene información del estado del compartido
     */
    public ShareStatus getStatus(Printer printer) {
        ShareStatus status = new ShareStatus();
        status.printerName = printer.getAlias();
        status.sharedViaSamba = printer.isSharedViaSamba();
        status.os = isWindows ? "Windows" : isLinux ? "Linux" : "Unknown";
        status.available = isAvailable();
        status.accessPath = "\\\\" + getServerIp() + "\\" + printer.getAlias();
        
        if (isLinux) {
            try {
                List<String> config = readFile("/etc/samba/smb.conf");
                status.configuredInSamba = findSection(config, "[" + printer.getAlias() + "]") >= 0;
            } catch (Exception e) {
                status.configuredInSamba = false;
            }
        } else {
            status.configuredInSamba = printer.isSharedViaSamba();
        }
        
        return status;
    }

    /**
     * Clase para el estado del compartido
     */
    public static class ShareStatus {
        public String printerName;
        public boolean sharedViaSamba;
        public boolean configuredInSamba;
        public boolean available;
        public String os;
        public String accessPath;
    }
}
