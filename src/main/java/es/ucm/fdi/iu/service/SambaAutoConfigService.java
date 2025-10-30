package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.repository.PrinterRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Servicio para auto-configuración de compartidos Samba para impresoras.
 * Configura automáticamente /etc/samba/smb.conf cuando se agregan/editan impresoras.
 * 
 * Only loads in server mode (NOT in usb-client)
 */
@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "server", matchIfMissing = true)
public class SambaAutoConfigService {

    private static final Logger log = LogManager.getLogger(SambaAutoConfigService.class);
    
    @Value("${samba.conf.path:/etc/samba/smb.conf}")
    private String smbConfPath;
    
    @Value("${samba.enable.auto.config:true}")
    private boolean autoConfigEnabled;
    
    @Value("${samba.simulate.on.windows:true}")
    private boolean simulateOnWindows;
    
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
    
    @Value("${samba.guest.access:yes}")
    private String guestAccess;
    
    @Value("${samba.browseable:yes}")
    private String browseable;
    
    @Autowired
    private PrinterRepository printerRepository;

    /**
     * Configura automáticamente una impresora en Samba
     */
    public boolean autoConfigurePrinter(Printer printer) {
        if (!autoConfigEnabled) {
            log.info("Auto-configuración Samba deshabilitada");
            return false;
        }
        
        // Detectar si estamos en Windows
        if (isWindows()) {
            return autoConfigurePrinterWindows(printer);
        }
        
        try {
            log.info("========================================");
            log.info("CONFIGURANDO IMPRESORA VIA SAMBA");
            log.info("========================================");
            log.info("Impresora: {}", printer.getAlias());
            log.info("Modelo: {}", printer.getModel());
            log.info("IP: {}", printer.getIp());
            log.info("Ubicación: {}", printer.getLocation());
            
            // Paso 1: Leer configuración actual
            log.info("Paso 1: Leyendo configuración actual de Samba...");
            List<String> config = readSmbConf();
            
            if (config == null) {
                log.error("No se pudo leer smb.conf");
                return false;
            }
            
            // Paso 2: Verificar/Configurar sección [global]
            log.info("Paso 2: Configurando sección [global]...");
            ensureGlobalPrintingConfig(config);
            
            // Paso 3: Crear/Actualizar sección de la impresora
            log.info("Paso 3: Creando sección de impresora...");
            String sectionName = "[" + printer.getAlias() + "]";
            int sectionIndex = findSection(config, sectionName);
            
            List<String> printerSection = buildPrinterSection(printer);
            
            if (sectionIndex >= 0) {
                log.info("Actualizando sección existente en línea {}", sectionIndex);
                int endIndex = findSectionEnd(config, sectionIndex);
                for (int i = endIndex - 1; i >= sectionIndex; i--) {
                    config.remove(i);
                }
                config.addAll(sectionIndex, printerSection);
            } else {
                log.info("Agregando nueva sección al final del archivo");
                config.addAll(printerSection);
            }
            
            // Paso 4: Escribir configuración
            log.info("Paso 4: Escribiendo configuración...");
            if (!writeSmbConf(config)) {
                log.error("Error al escribir smb.conf");
                return false;
            }
            
            // Paso 5: Verificar configuración
            log.info("Paso 5: Verificando configuración...");
            if (!testSmbConf()) {
                log.error("La configuración de Samba contiene errores");
                return false;
            }
            
            // Paso 6: Recargar Samba
            log.info("Paso 6: Recargando servicios Samba...");
            if (!reloadSamba()) {
                log.warn("No se pudo recargar Samba automáticamente, reinicio manual requerido");
            }
            
            // Paso 7: Actualizar base de datos
            log.info("Paso 7: Actualizando base de datos...");
            printer.setSharedViaSamba(true);
            printerRepository.save(printer);
            
            log.info("========================================");
            log.info("CONFIGURACION EXITOSA");
            log.info("========================================");
            log.info("Ruta de acceso Windows: \\\\<servidor-ip>\\{}", printer.getAlias());
            log.info("Ejemplo: \\\\{}\\{}", getServerIp(), printer.getAlias());
            
            return true;
            
        } catch (Exception e) {
            log.error("========================================");
            log.error("ERROR EN AUTO-CONFIGURACION SAMBA");
            log.error("========================================");
            log.error("Impresora: {}", printer.getAlias());
            log.error("Error: ", e);
            return false;
        }
    }

    /**
     * Configuración simulada para Windows (desarrollo)
     */
    private boolean autoConfigurePrinterWindows(Printer printer) {
        if (!simulateOnWindows) {
            log.warn("========================================");
            log.warn("MODO WINDOWS - CONFIGURACION SAMBA DESHABILITADA");
            log.warn("========================================");
            log.warn("Estás ejecutando en Windows. Samba solo funciona en Linux.");
            log.warn("Para probar en producción, despliega en un servidor Linux.");
            log.warn("========================================");
            return false;
        }
        
        try {
            log.info("========================================");
            log.info("MODO SIMULACION (WINDOWS)");
            log.info("========================================");
            log.info("Impresora: {}", printer.getAlias());
            log.info("Modelo: {}", printer.getModel());
            log.info("IP: {}", printer.getIp());
            log.info("Ubicación: {}", printer.getLocation());
            log.info("");
            log.info("⚠️ ATENCION: Ejecutando en Windows");
            log.info("La configuración real de Samba solo funcionará en Linux.");
            log.info("");
            log.info("📝 SIMULACION DE CONFIGURACION:");
            log.info("1. ✓ Verificar/crear directorio: /var/spool/samba");
            log.info("2. ✓ Leer archivo: /etc/samba/smb.conf");
            log.info("3. ✓ Configurar sección [global]");
            log.info("4. ✓ Crear sección [{}]", printer.getAlias());
            log.info("5. ✓ Escribir configuración");
            log.info("6. ✓ Ejecutar: testparm -s");
            log.info("7. ✓ Ejecutar: systemctl reload smbd");
            log.info("");
            log.info("📋 CONTENIDO QUE SE AGREGARIA A smb.conf:");
            log.info("");
            log.info("[{}]", printer.getAlias());
            log.info("    comment = {} - {}", printer.getModel(), printer.getLocation());
            log.info("    path = /var/spool/samba");
            log.info("    printable = yes");
            log.info("    browseable = yes");
            log.info("    guest ok = yes");
            log.info("    public = yes");
            log.info("    writable = no");
            log.info("    create mask = 0700");
            log.info("    printer name = {}", printer.getAlias());
            log.info("");
            log.info("========================================");
            log.info("✅ SIMULACION EXITOSA");
            log.info("========================================");
            log.info("En producción (Linux), los clientes Windows podrían conectarse usando:");
            log.info("  \\\\{}\\{}", getServerIp(), printer.getAlias());
            log.info("");
            log.info("📦 PARA DEPLOYMENT EN LINUX:");
            log.info("1. Instalar Samba: sudo apt-get install samba");
            log.info("2. Ejecutar: sudo ./scripts/setup-samba.sh");
            log.info("3. La aplicación configurará automáticamente las impresoras");
            log.info("========================================");
            
            // Actualizar base de datos
            printer.setSharedViaSamba(true);
            printerRepository.save(printer);
            
            return true;
            
        } catch (Exception e) {
            log.error("Error en simulación Windows", e);
            return false;
        }
    }
    
    /**
     * Elimina la configuración de una impresora de Samba
     */
    public boolean unconfigurePrinter(Printer printer) {
        if (isWindows()) {
            log.info("========================================");
            log.info("MODO WINDOWS - SIMULACION");
            log.info("========================================");
            log.info("Eliminando compartido Samba de: {}", printer.getAlias());
            log.info("En producción (Linux), se ejecutaría:");
            log.info("1. Editar /etc/samba/smb.conf");
            log.info("2. Eliminar sección [{}]", printer.getAlias());
            log.info("3. Ejecutar: systemctl reload smbd");
            log.info("========================================");
            
            printer.setSharedViaSamba(false);
            printerRepository.save(printer);
            return true;
        }
        
        try {
            log.info("Eliminando configuración Samba para: {}", printer.getAlias());
            
            List<String> config = readSmbConf();
            if (config == null) {
                return false;
            }
            
            String sectionName = "[" + printer.getAlias() + "]";
            int sectionIndex = findSection(config, sectionName);
            
            if (sectionIndex >= 0) {
                int endIndex = findSectionEnd(config, sectionIndex);
                for (int i = endIndex - 1; i >= sectionIndex; i--) {
                    config.remove(i);
                }
                
                writeSmbConf(config);
                reloadSamba();
                
                printer.setSharedViaSamba(false);
                printerRepository.save(printer);
                
                log.info("Configuración eliminada exitosamente");
                return true;
            } else {
                log.warn("No se encontró sección para {}", printer.getAlias());
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al eliminar configuración Samba", e);
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
        section.add("    browseable = " + browseable);
        section.add("    guest ok = " + guestAccess);
        section.add("    public = " + guestAccess);
        section.add("    writable = no");
        section.add("    create mask = 0700");
        section.add("    printer name = " + printer.getAlias());
        section.add("");
        
        return section;
    }

    /**
     * Asegura que la sección [global] tenga la configuración necesaria para impresión
     */
    private void ensureGlobalPrintingConfig(List<String> config) {
        int globalIndex = findSection(config, "[global]");
        
        if (globalIndex < 0) {
            log.warn("No se encontró sección [global], creándola...");
            config.add(0, "[global]");
            config.add(1, "    workgroup = WORKGROUP");
            config.add(2, "    server string = Print Server");
            config.add(3, "");
            globalIndex = 0;
        }
        
        int endIndex = findSectionEnd(config, globalIndex);
        
        // Configuraciones necesarias para impresión
        String[] requiredSettings = {
            "load printers = yes",
            "printing = cups",
            "printcap name = cups"
        };
        
        for (String setting : requiredSettings) {
            String key = setting.split("=")[0].trim();
            boolean found = false;
            
            for (int i = globalIndex + 1; i < endIndex; i++) {
                if (config.get(i).trim().toLowerCase().startsWith(key.toLowerCase())) {
                    found = true;
                    // Actualizar valor si existe
                    config.set(i, "    " + setting);
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
     * Lee el archivo smb.conf
     */
    private List<String> readSmbConf() {
        try {
            File file = new File(smbConfPath);
            if (!file.exists()) {
                log.error("Archivo smb.conf no existe: {}", smbConfPath);
                return null;
            }
            
            if (!file.canRead()) {
                log.error("No se puede leer smb.conf: permisos insuficientes");
                return null;
            }
            
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            
            log.info("Leídas {} líneas de {}", lines.size(), smbConfPath);
            return lines;
            
        } catch (IOException e) {
            log.error("Error al leer smb.conf", e);
            return null;
        }
    }

    /**
     * Escribe el archivo smb.conf
     */
    private boolean writeSmbConf(List<String> lines) {
        try {
            File file = new File(smbConfPath);
            File backup = new File(smbConfPath + ".backup");
            
            // Crear backup
            if (file.exists()) {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("Backup creado: {}", backup.getAbsolutePath());
            }
            
            // Escribir nueva configuración
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            
            log.info("Configuración escrita exitosamente");
            return true;
            
        } catch (IOException e) {
            log.error("Error al escribir smb.conf", e);
            return false;
        }
    }

    /**
     * Encuentra el índice de una sección en la configuración
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
     * Verifica la configuración de Samba
     */
    private boolean testSmbConf() {
        try {
            Process process = Runtime.getRuntime().exec("testparm -s " + smbConfPath);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream())
            );
            
            String line;
            boolean hasErrors = false;
            
            while ((line = errorReader.readLine()) != null) {
                log.warn("testparm: {}", line);
                if (line.toLowerCase().contains("error")) {
                    hasErrors = true;
                }
            }
            
            int exitCode = process.waitFor();
            reader.close();
            errorReader.close();
            
            if (exitCode == 0 && !hasErrors) {
                log.info("Configuración Samba válida");
                return true;
            } else {
                log.error("Configuración Samba inválida, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.warn("No se pudo ejecutar testparm: {}", e.getMessage());
            // No consideramos esto un error crítico
            return true;
        }
    }

    /**
     * Recarga la configuración de Samba
     */
    private boolean reloadSamba() {
        try {
            // Intentar reload primero
            Process process = Runtime.getRuntime().exec("systemctl reload smbd");
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("Samba recargado exitosamente");
                return true;
            }
            
            // Si falla, intentar restart
            log.warn("Reload falló, intentando restart...");
            process = Runtime.getRuntime().exec("systemctl restart smbd nmbd");
            exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("Samba reiniciado exitosamente");
                return true;
            } else {
                log.error("Error al reiniciar Samba, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al recargar Samba", e);
            return false;
        }
    }

    /**
     * Obtiene la IP del servidor
     */
    private String getServerIp() {
        try {
            Process process = Runtime.getRuntime().exec("hostname -I");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String ips = reader.readLine();
            reader.close();
            process.waitFor();
            
            if (ips != null && !ips.isEmpty()) {
                return ips.trim().split(" ")[0];
            }
            
        } catch (Exception e) {
            log.warn("No se pudo obtener IP del servidor");
        }
        
        return "<IP-DEL-SERVIDOR>";
    }

    /**
     * Verifica si Samba está instalado y accesible
     */
    public boolean isSambaAvailable() {
        if (isWindows()) {
            log.debug("Ejecutando en Windows - Samba no disponible (modo simulación activo)");
            return simulateOnWindows; // Retorna true si simulación está habilitada
        }
        
        try {
            File smbConf = new File(smbConfPath);
            if (!smbConf.exists()) {
                log.warn("Samba no está instalado o smb.conf no existe");
                return false;
            }
            
            Process process = Runtime.getRuntime().exec("which smbd");
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                log.warn("smbd no está en el PATH");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("Error al verificar disponibilidad de Samba", e);
            return false;
        }
    }

    /**
     * Obtiene el estado de una impresora en Samba
     */
    public SambaShareStatus getShareStatus(Printer printer) {
        SambaShareStatus status = new SambaShareStatus();
        status.printerName = printer.getAlias();
        status.sharedViaSamba = printer.isSharedViaSamba();
        status.isWindows = isWindows();
        status.simulationMode = isWindows() && simulateOnWindows;
        
        if (isWindows()) {
            status.configuredInSamba = printer.isSharedViaSamba(); // En simulación
            status.sambaRunning = simulateOnWindows;
            status.accessPath = "\\\\<servidor-linux-ip>\\" + printer.getAlias();
        } else {
            try {
                List<String> config = readSmbConf();
                if (config != null) {
                    String sectionName = "[" + printer.getAlias() + "]";
                    status.configuredInSamba = findSection(config, sectionName) >= 0;
                }
                
                status.sambaRunning = isSambaRunning();
                status.accessPath = "\\\\" + getServerIp() + "\\" + printer.getAlias();
                
            } catch (Exception e) {
                log.error("Error al obtener estado de compartido", e);
            }
        }
        
        return status;
    }

    /**
     * Verifica si Samba está ejecutándose
     */
    private boolean isSambaRunning() {
        try {
            Process process = Runtime.getRuntime().exec("systemctl is-active smbd");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String line = reader.readLine();
            boolean running = "active".equals(line);
            
            process.waitFor();
            reader.close();
            
            return running;
            
        } catch (Exception e) {
            log.error("Error al verificar estado de Samba", e);
            return false;
        }
    }

    /**
     * Clase para representar el estado de un compartido Samba
     */
    public static class SambaShareStatus {
        public String printerName;
        public boolean sharedViaSamba;
        public boolean configuredInSamba;
        public boolean sambaRunning;
        public String accessPath;
        public boolean isWindows;
        public boolean simulationMode;
    }
}
