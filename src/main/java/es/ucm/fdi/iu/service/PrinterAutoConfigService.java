package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Printer;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.logging.log4j.LogManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de auto-configuración de impresoras
 * 
 * Cuando se agrega una impresora al sistema:
 * 1. La configura automáticamente en CUPS
 * 2. La comparte automáticamente vía Samba
 * 3. Genera URIs de conexión para clientes
 */
@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "server", matchIfMissing = true)
public class PrinterAutoConfigService {

    private static final Logger log = LogManager.getLogger(PrinterAutoConfigService.class);

    @Autowired
    private CupsService cupsService;

    @Autowired
    private SambaService sambaService;

    @Autowired
    private EntityManager entityManager;

    /**
     * Configura completamente una impresora cuando se agrega al sistema
     */
    @Transactional
    public ConfigurationResult autoConfigurePrinter(Printer printer, boolean shareViaSamba) {
        ConfigurationResult result = new ConfigurationResult();
        result.setPrinterName(printer.getAlias());
        
        log.info("========================================");
        log.info("Auto-configurando impresora: {}", printer.getAlias());
        log.info("IP: {}", printer.getIp());
        log.info("Modelo: {}", printer.getModel());
        
        try {
            // PASO 1: Determinar URI del dispositivo y protocolo
            String deviceUri = determineDeviceUri(printer);
            printer.setDeviceUri(deviceUri);
            result.setDeviceUri(deviceUri);
            log.info("URI del dispositivo: {}", deviceUri);
            
            // PASO 2: Determinar driver apropiado
            String driver = determineDriver(printer);
            printer.setDriver(driver);
            result.setDriver(driver);
            log.info("Driver: {}", driver);
            
            // PASO 3: Agregar a CUPS
            log.info("Agregando impresora a CUPS...");
            boolean cupsSuccess = cupsService.addPrinter(printer, deviceUri, driver);
            
            if (cupsSuccess) {
                printer.setAddedToCups(true);
                result.setCupsConfigured(true);
                result.addMessage("✓ Impresora agregada a CUPS exitosamente");
                log.info("✓ Impresora agregada a CUPS");
                
                // PASO 4: Compartir vía Samba si se solicita
                if (shareViaSamba) {
                    log.info("Compartiendo impresora vía Samba...");
                    String shareName = printer.getAlias().replaceAll("[^a-zA-Z0-9_-]", "_");
                    printer.setSambaShareName(shareName);
                    
                    boolean sambaSuccess = sambaService.sharePrinter(printer, false, new ArrayList<>());
                    
                    if (sambaSuccess) {
                        printer.setSharedViaSamba(true);
                        result.setSambaShared(true);
                        result.addMessage("✓ Impresora compartida vía Samba");
                        log.info("✓ Impresora compartida vía Samba como: {}", shareName);
                    } else {
                        result.addMessage("⚠ Error al compartir vía Samba");
                        log.warn("⚠ Error al compartir vía Samba");
                    }
                }
                
                // PASO 5: Generar URIs de conexión para clientes
                generateClientConnectionInfo(printer, result);
                
            } else {
                result.addMessage("✗ Error al agregar impresora a CUPS");
                log.error("✗ Error al agregar impresora a CUPS");
            }
            
            // Guardar cambios
            entityManager.merge(printer);
            
            log.info("========================================");
            return result;
            
        } catch (Exception e) {
            log.error("Error durante auto-configuración", e);
            result.addMessage("✗ Error: " + e.getMessage());
            return result;
        }
    }

    /**
     * Determina el URI del dispositivo según la IP y protocolo
     */
    private String determineDeviceUri(Printer printer) {
        String ip = printer.getIp();
        
        // Si ya tiene un deviceUri configurado, usarlo
        if (printer.getDeviceUri() != null && !printer.getDeviceUri().isEmpty()) {
            return printer.getDeviceUri();
        }
        
        // Intentar detectar el mejor protocolo
        String protocol = detectBestProtocol(ip);
        printer.setProtocol(protocol);
        
        switch (protocol) {
            case "IPP":
                printer.setPort(631);
                return "ipp://" + ip + "/ipp/print";
                
            case "RAW":
                printer.setPort(9100);
                return "socket://" + ip + ":9100";
                
            case "LPD":
                printer.setPort(515);
                return "lpd://" + ip + "/queue";
                
            default:
                // IPP por defecto (más universal)
                printer.setPort(631);
                return "ipp://" + ip + "/ipp/print";
        }
    }

    /**
     * Detecta el mejor protocolo disponible para una impresora
     */
    private String detectBestProtocol(String ip) {
        // Probar protocolos en orden de preferencia
        
        // 1. IPP (631) - Más moderno y compatible
        if (isPortOpen(ip, 631)) {
            log.debug("IPP disponible en {}", ip);
            return "IPP";
        }
        
        // 2. RAW/JetDirect (9100) - Común en impresoras HP
        if (isPortOpen(ip, 9100)) {
            log.debug("RAW disponible en {}", ip);
            return "RAW";
        }
        
        // 3. LPD (515) - Protocolo antiguo pero compatible
        if (isPortOpen(ip, 515)) {
            log.debug("LPD disponible en {}", ip);
            return "LPD";
        }
        
        // Por defecto IPP
        log.debug("No se detectó protocolo, usando IPP por defecto");
        return "IPP";
    }

    /**
     * Verifica si un puerto está abierto
     */
    private boolean isPortOpen(String ip, int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(ip, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Determina el driver PPD apropiado para la impresora
     */
    private String determineDriver(Printer printer) {
        // Si ya tiene driver configurado, usarlo
        if (printer.getDriver() != null && !printer.getDriver().isEmpty()) {
            return printer.getDriver();
        }
        
        String model = printer.getModel().toLowerCase();
        
        // Drivers específicos por marca
        if (model.contains("hp") || model.contains("laserjet") || model.contains("deskjet")) {
            return "drv:///hp/hpcups.drv/hp-laserjet_pro_mfp_m428.ppd";
        } else if (model.contains("canon")) {
            return "drv:///cupsfilters.drv/pwgrast.ppd";
        } else if (model.contains("epson")) {
            return "drv:///cupsfilters.drv/pwgrast.ppd";
        } else if (model.contains("brother")) {
            return "drv:///cupsfilters.drv/pwgrast.ppd";
        } else if (model.contains("xerox")) {
            return "drv:///cupsfilters.drv/pwgrast.ppd";
        } else {
            // Driver genérico universal (funciona con mayoría de impresoras modernas)
            return "everywhere";
        }
    }

    /**
     * Genera información de conexión para clientes
     */
    private void generateClientConnectionInfo(Printer printer, ConfigurationResult result) {
        String serverIp = getServerIp();
        String printerName = printer.getSambaShareName() != null ? 
            printer.getSambaShareName() : printer.getAlias();
        
        // URI IPP para clientes Linux/Mac
        String ippUri = "ipp://" + serverIp + ":631/printers/" + printer.getAlias();
        result.setIppUri(ippUri);
        
        // URI Samba para clientes Windows
        if (printer.isSharedViaSamba()) {
            String sambaUri = "\\\\" + serverIp + "\\" + printerName;
            result.setSambaUri(sambaUri);
        }
        
        // URI directo a la impresora
        result.setDirectUri(printer.getDeviceUri());
        
        log.info("URIs de conexión generadas:");
        log.info("  - IPP (Linux/Mac): {}", ippUri);
        if (printer.isSharedViaSamba()) {
            log.info("  - Samba (Windows): {}", result.getSambaUri());
        }
        log.info("  - Directo: {}", printer.getDeviceUri());
    }

    /**
     * Obtiene la IP del servidor
     */
    private String getServerIp() {
        return es.ucm.fdi.iu.util.NetworkUtils.getServerHost();
    }

    /**
     * Desactiva completamente una impresora (CUPS y Samba)
     */
    @Transactional
    public boolean unconfigurePrinter(Printer printer) {
        log.info("Desactivando impresora: {}", printer.getAlias());
        boolean success = true;
        
        try {
            // Eliminar de Samba
            if (printer.isSharedViaSamba()) {
                sambaService.unsharePrinter(printer.getAlias());
                printer.setSharedViaSamba(false);
            }
            
            // Eliminar de CUPS
            if (printer.isAddedToCups()) {
                cupsService.removePrinter(printer.getAlias());
                printer.setAddedToCups(false);
            }
            
            entityManager.merge(printer);
            log.info("Impresora desactivada exitosamente");
            
        } catch (Exception e) {
            log.error("Error al desactivar impresora", e);
            success = false;
        }
        
        return success;
    }

    /**
     * Re-sincroniza una impresora con CUPS y Samba
     */
    @Transactional
    public ConfigurationResult resyncPrinter(Printer printer) {
        log.info("Re-sincronizando impresora: {}", printer.getAlias());
        
        // Primero desconfigurar
        unconfigurePrinter(printer);
        
        // Luego volver a configurar
        return autoConfigurePrinter(printer, printer.isSharedViaSamba());
    }

    /**
     * Resultado de la configuración
     */
    public static class ConfigurationResult {
        private String printerName;
        private String deviceUri;
        private String driver;
        private boolean cupsConfigured;
        private boolean sambaShared;
        private String ippUri;
        private String sambaUri;
        private String directUri;
        private List<String> messages = new ArrayList<>();
        
        public void addMessage(String message) {
            messages.add(message);
        }
        
        public boolean isFullyConfigured() {
            return cupsConfigured;
        }
        
        // Getters y Setters
        public String getPrinterName() { return printerName; }
        public void setPrinterName(String printerName) { this.printerName = printerName; }
        
        public String getDeviceUri() { return deviceUri; }
        public void setDeviceUri(String deviceUri) { this.deviceUri = deviceUri; }
        
        public String getDriver() { return driver; }
        public void setDriver(String driver) { this.driver = driver; }
        
        public boolean isCupsConfigured() { return cupsConfigured; }
        public void setCupsConfigured(boolean cupsConfigured) { this.cupsConfigured = cupsConfigured; }
        
        public boolean isSambaShared() { return sambaShared; }
        public void setSambaShared(boolean sambaShared) { this.sambaShared = sambaShared; }
        
        public String getIppUri() { return ippUri; }
        public void setIppUri(String ippUri) { this.ippUri = ippUri; }
        
        public String getSambaUri() { return sambaUri; }
        public void setSambaUri(String sambaUri) { this.sambaUri = sambaUri; }
        
        public String getDirectUri() { return directUri; }
        public void setDirectUri(String directUri) { this.directUri = directUri; }
        
        public List<String> getMessages() { return messages; }
        public void setMessages(List<String> messages) { this.messages = messages; }
    }
}
