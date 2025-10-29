package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Printer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.*;

// SNMP imports para descubrimiento cross-VLAN
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * Servicio para descubrir impresoras en la red automáticamente
 * - Impresoras de red (diferentes VLANs)
 * - Impresoras USB conectadas a computadoras
 * - Impresoras compartidas por otras computadoras
 */
@Service
@Slf4j
public class PrinterDiscoveryService {

    @PersistenceContext
    private EntityManager em;
    
    private final IppPrintService ippService;
    private final SmbShareService smbService;
    
    public PrinterDiscoveryService(IppPrintService ippService, SmbShareService smbService) {
        this.ippService = ippService;
        this.smbService = smbService;
    }
    
    // Estado del escaneo
    private volatile boolean scanning = false;
    private volatile boolean cancelRequested = false;
    private volatile int totalHosts = 0;
    private volatile int scannedHosts = 0;
    private volatile int foundPrinters = 0;
    private volatile String currentNetwork = "";
    private volatile long scanStartTime = 0;
    private ExecutorService currentExecutor = null;
    private Thread scanThread = null; // Referencia al hilo principal de escaneo

    // Puertos comunes para impresoras de red
    private static final int[] PRINTER_PORTS = {9100, 631, 515}; // RAW, IPP, LPD
    
    // Puerto SNMP para descubrimiento
    private static final int SNMP_PORT = 161;
    
    // OIDs SNMP estándar para impresoras
    private static final String OID_SYS_DESCR = "1.3.6.1.2.1.1.1.0"; // Descripción del sistema
    private static final String OID_SYS_NAME = "1.3.6.1.2.1.1.5.0"; // Nombre del sistema
    private static final String OID_PRINTER_MODEL = "1.3.6.1.2.1.25.3.2.1.3.1"; // Modelo de impresora
    private static final String OID_PRINTER_SERIAL = "1.3.6.1.2.1.43.5.1.1.17.1"; // Número de serie
    
    // Comunidad SNMP por defecto (configurable)
    private static final String SNMP_COMMUNITY = "public";
    
    // Configuración desde application.properties
    @Value("${printer.discovery.networks:192.168.1.0/24}")
    private String configuredNetworks;
    
    @Value("${printer.discovery.snmp.enabled:true}")
    private boolean snmpEnabled;
    
    @Value("${printer.discovery.snmp.timeout:500}")
    private int snmpTimeout;
    
    @Value("${printer.discovery.port.timeout:200}")
    private int portTimeout;

    /**
     * Obtiene los rangos de red configurados o usa los por defecto
     */
    private List<String> getNetworkRangesToScan() {
        List<String> ranges = new ArrayList<>();
        try {
            List<es.ucm.fdi.iu.model.NetworkRange> configuredRanges = 
                em.createNamedQuery("NetworkRange.active", es.ucm.fdi.iu.model.NetworkRange.class)
                .getResultList();
            
            if (!configuredRanges.isEmpty()) {
                // Usar rangos de la base de datos
                for (es.ucm.fdi.iu.model.NetworkRange range : configuredRanges) {
                    ranges.add(range.getCidrRange());
                    log.info("Rango configurado en BD: {} ({})", range.getName(), range.getCidrRange());
                }
            } else {
                // Usar rangos de application.properties
                String[] propertyRanges = configuredNetworks.split(",");
                for (String range : propertyRanges) {
                    ranges.add(range.trim());
                    log.info("Rango configurado en properties: {}", range.trim());
                }
            }
        } catch (Exception e) {
            log.error("Error al obtener rangos de red, usando configuración por defecto", e);
            // Fallback a configuración de properties
            String[] propertyRanges = configuredNetworks.split(",");
            for (String range : propertyRanges) {
                ranges.add(range.trim());
            }
        }
        return ranges;
    }
    
    /**
     * Escanea la red y descubre todas las impresoras disponibles
     */
    public List<DiscoveredPrinter> discoverNetworkPrinters() {
        // Guardar referencia al hilo actual
        scanThread = Thread.currentThread();
        
        scanning = true;
        cancelRequested = false;
        scannedHosts = 0;
        foundPrinters = 0;
        scanStartTime = System.currentTimeMillis();
        
        log.info("========================================");
        log.info("Iniciando descubrimiento de impresoras en red...");
        List<DiscoveredPrinter> discovered = new CopyOnWriteArrayList<>();
        currentExecutor = Executors.newFixedThreadPool(50);
        ExecutorService executor = currentExecutor;
        
        // Obtener rangos de red configurados
        List<String> networkRanges = getNetworkRangesToScan();
        log.info("Total de redes a escanear: {}", networkRanges.size());
        for (String range : networkRanges) {
            log.info("  - {}", range);
        }
        
        // Calcular total de hosts a escanear
        totalHosts = 0;
        for (String networkRange : networkRanges) {
            totalHosts += generateIPsFromRange(networkRange).size();
        }
        
        try {
            // Preparar todas las IPs de todas las redes
            List<Future<?>> allFutures = new ArrayList<>();
            
            // Escanear cada rango de red en paralelo
            for (String networkRange : networkRanges) {
                // Verificar interrupción del hilo
                if (Thread.currentThread().isInterrupted() || cancelRequested) {
                    log.info("⚠️ Escaneo cancelado (hilo interrumpido o cancelRequested)");
                    break;
                }
                
                List<String> ips = generateIPsFromRange(networkRange);
                log.info("Añadiendo rango al escaneo: {} ({} IPs)", networkRange, ips.size());
                
                final String currentRange = networkRange;
                
                // Agregar todos los hosts de esta red al pool
                for (String ip : ips) {
                    // Verificar si se solicitó cancelación o interrupción
                    if (Thread.currentThread().isInterrupted() || cancelRequested) {
                        log.info("⚠️ Escaneo cancelado por el usuario");
                        break;
                    }
                    
                    allFutures.add(executor.submit(() -> {
                        try {
                            // Verificar cancelación o interrupción antes de escanear
                            if (Thread.currentThread().isInterrupted() || cancelRequested) {
                                scannedHosts++; // Contar como escaneado aunque se canceló
                                return;
                            }
                            
                            currentNetwork = currentRange; // Actualizar red actual
                            DiscoveredPrinter printer = scanIPForPrinter(ip);
                            if (printer != null) {
                                discovered.add(printer);
                                foundPrinters++;
                                log.info("✓ Impresora encontrada en {} ({}): {}", 
                                    ip, currentRange, printer.getName());
                            }
                        } catch (Exception e) {
                            // Ignorar errores individuales
                        } finally {
                            scannedHosts++;
                        }
                    }));
                }
                
                // Si se canceló, salir del loop de redes
                if (cancelRequested) {
                    break;
                }
                    }
            
            log.info("Iniciando escaneo paralelo de {} hosts en {} redes...", 
                    totalHosts, networkRanges.size());
            
            // Esperar a que terminen TODOS los scans (con timeout)
            for (Future<?> future : allFutures) {
                try {
                    future.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                } catch (Exception e) {
                    // Ignorar errores
                }
            }
        } finally {
            // Si no fue cancelado externamente, hacer shutdown normal
            if (!cancelRequested) {
                executor.shutdown();
                try {
                    // Esperar máximo 5 minutos para completar normalmente
                    if (!executor.awaitTermination(300, TimeUnit.SECONDS)) {
                        log.warn("⚠️ Timeout esperando fin del escaneo, forzando...");
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    log.warn("⚠️ Escaneo interrumpido");
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            // Si fue cancelado, el executor ya fue detenido por cancelScan()
            
            // Asegurar que llegue al 100%
            scannedHosts = totalHosts;
            
            // Limpiar estado
            boolean wasCancelled = cancelRequested || Thread.currentThread().isInterrupted();
            scanning = false;
            cancelRequested = false;
            currentExecutor = null;
            scanThread = null;
            
            if (wasCancelled) {
                log.info("✅ Escaneo cancelado - limpieza completada");
                // Limpiar la bandera de interrupción si existe
                Thread.interrupted();
            }
        }
        
        long duration = (System.currentTimeMillis() - scanStartTime) / 1000;
        log.info("========================================");
        boolean wasCancelled = cancelRequested || Thread.currentThread().isInterrupted();
        if (wasCancelled) {
            log.info("⚠️ Escaneo CANCELADO después de {} segundos", duration);
        } else {
            log.info("✅ Descubrimiento COMPLETADO en {} segundos", duration);
        }
        log.info("Hosts escaneados: {}/{}", scannedHosts, totalHosts);
        log.info("Impresoras encontradas: {}", discovered.size());
        log.info("========================================");
        return discovered;
    }
    
    /**
     * Cancela el escaneo en progreso INMEDIATAMENTE
     */
    public void cancelScan() {
        log.info("⚠️ Solicitando cancelación INMEDIATA del escaneo...");
        
        // Marcar como cancelado primero
        cancelRequested = true;
        
        // 1. Detener el ExecutorService inmediatamente
        if (currentExecutor != null) {
            log.info("🛑 Deteniendo executor con {} tareas...", currentExecutor);
            List<Runnable> pendingTasks = currentExecutor.shutdownNow();
            log.info("⚠️ {} tareas pendientes canceladas", pendingTasks.size());
            
            try {
                // Esperar máximo 1 segundo a que terminen los hilos
                if (!currentExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    log.warn("⚠️ Algunos hilos del executor no respondieron");
                }
            } catch (InterruptedException e) {
                log.warn("⚠️ Interrupción durante espera del executor");
            }
        }
        
        // 2. Intentar interrumpir el hilo principal del escaneo
        if (scanThread != null && scanThread.isAlive()) {
            log.info("🛑 Interrumpiendo hilo principal de escaneo...");
            scanThread.interrupt();
        }
        
        // 3. Forzar estado a completado/cancelado
        scanning = false;
        scannedHosts = totalHosts; // Marcar como completo para que el progreso sea 100%
        currentExecutor = null;
        scanThread = null;
        
        log.info("✅ Escaneo cancelado exitosamente");
    }
    
    /**
     * Obtiene el estado actual del escaneo
     */
    public ScanStatus getScanStatus() {
        ScanStatus status = new ScanStatus();
        status.setScanning(scanning);
        status.setCancelled(cancelRequested);
        status.setTotalHosts(totalHosts);
        status.setScannedHosts(scannedHosts);
        status.setFoundPrinters(foundPrinters);
        status.setCurrentNetwork(currentNetwork);
        
        if (totalHosts > 0) {
            int progress = (int) ((scannedHosts * 100.0) / totalHosts);
            // Asegurar que no exceda 100
            status.setProgress(Math.min(progress, 100));
        } else {
            status.setProgress(0);
        }
        
        // Si fue cancelado, marcar progreso al 100% para cerrar la barra
        if (cancelRequested && !scanning) {
            status.setProgress(100);
        }
        
        if (scanStartTime > 0) {
            long elapsed = (System.currentTimeMillis() - scanStartTime) / 1000;
            status.setElapsedTime(elapsed);
            
            // Estimar tiempo restante solo si está activo
            if (scanning && scannedHosts > 0) {
                long totalEstimated = (elapsed * totalHosts) / scannedHosts;
                status.setEstimatedRemaining(totalEstimated - elapsed);
            }
        }
        
        return status;
    }

    /**
     * Descubre impresoras locales (USB) y compartidas en este sistema
     */
    public List<DiscoveredPrinter> discoverLocalPrinters() {
        log.info("Buscando impresoras locales y compartidas...");
        List<DiscoveredPrinter> discovered = new ArrayList<>();
        
        try {
            // Usar Java Print Service para encontrar impresoras locales
            PrintService[] printServices = PrintServiceLookup.lookupPrintServices(null, null);
            
            for (PrintService service : printServices) {
                DiscoveredPrinter printer = new DiscoveredPrinter();
                printer.setName(service.getName());
                printer.setType(determineLocalPrinterType(service));
                printer.setModel(extractPrinterModel(service));
                printer.setStatus("Disponible");
                printer.setConnectionType(printer.getType());
                
                discovered.add(printer);
                log.info("✓ Impresora local encontrada: {} ({})", printer.getName(), printer.getType());
            }
        } catch (Exception e) {
            log.error("Error al buscar impresoras locales: {}", e.getMessage());
        }
        
        log.info("Encontradas {} impresoras locales", discovered.size());
        return discovered;
    }

    /**
     * Escanea una IP específica para ver si hay una impresora
     * MEJORADO: Ahora intenta múltiples protocolos (SNMP, IPP, SMB)
     */
    private DiscoveredPrinter scanIPForPrinter(String ip) {
        // ESTRATEGIA 1: Intentar descubrimiento SNMP (MEJOR para cross-VLAN)
        DiscoveredPrinter snmpPrinter = scanViaSNMP(ip);
        if (snmpPrinter != null) {
            log.debug("Impresora descubierta vía SNMP en {}", ip);
            return snmpPrinter;
        }
        
        // ESTRATEGIA 2: Intentar IPP (puerto 631)
        DiscoveredPrinter ippPrinter = scanViaIPP(ip);
        if (ippPrinter != null) {
            log.debug("Impresora descubierta vía IPP en {}", ip);
            return ippPrinter;
        }
        
        // ESTRATEGIA 3: Intentar SMB (puerto 445) para impresoras compartidas Windows
        DiscoveredPrinter smbPrinter = scanViaSMB(ip);
        if (smbPrinter != null) {
            log.debug("Impresora descubierta vía SMB en {}", ip);
            return smbPrinter;
        }
        
        // ESTRATEGIA 4: Verificar puertos RAW/LPD directamente
        for (int port : new int[]{9100, 515}) { // RAW, LPD (ya probamos IPP)
            if (isPortOpen(ip, port, portTimeout)) {
                DiscoveredPrinter printer = new DiscoveredPrinter();
                printer.setIp(ip);
                printer.setName(resolveDNSName(ip));
                printer.setType("Red - " + getPortProtocol(port));
                printer.setModel("Impresora de Red");
                printer.setStatus("En línea");
                printer.setConnectionType("RED");
                printer.setPort(port);
                log.debug("Impresora descubierta vía escaneo de puertos en {}:{}", ip, port);
                return printer;
            }
        }
        
        return null;
    }
    
    /**
     * Descubre impresoras usando protocolo IPP (Java puro, sin CUPS)
     */
    private DiscoveredPrinter scanViaIPP(String ip) {
        try {
            // Intentar varios endpoints IPP comunes
            String[] endpoints = {"/ipp/print", "/ipp", "/"};
            
            for (String endpoint : endpoints) {
                String ippUri = ippService.buildIppUri(ip, 631, endpoint);
                IppPrintService.IppPrinterInfo info = ippService.getPrinterInfo(ippUri);
                
                if (info != null && info.getName() != null) {
                    DiscoveredPrinter printer = new DiscoveredPrinter();
                    printer.setIp(ip);
                    printer.setName(info.getName());
                    printer.setModel(info.getMakeModel() != null ? info.getMakeModel() : "IPP Printer");
                    printer.setType("Red - IPP");
                    printer.setStatus(info.isAccepting() ? "En línea" : "No acepta trabajos");
                    printer.setConnectionType("IPP");
                    printer.setPort(631);
                    return printer;
                }
            }
        } catch (Exception e) {
            log.trace("IPP no disponible en {}: {}", ip, e.getMessage());
        }
        return null;
    }
    
    /**
     * Descubre impresoras compartidas vía SMB (Java puro, sin Samba)
     */
    private DiscoveredPrinter scanViaSMB(String ip) {
        try {
            // Intentar descubrimiento anónimo primero
            List<SmbShareService.SmbShareInfo> printers = smbService.discoverPrinters(ip, null, null);
            
            if (!printers.isEmpty()) {
                SmbShareService.SmbShareInfo smbInfo = printers.get(0);
                
                DiscoveredPrinter printer = new DiscoveredPrinter();
                printer.setIp(ip);
                printer.setName(smbInfo.getName());
                printer.setModel("Impresora SMB");
                printer.setType("Red - SMB/Windows");
                printer.setStatus(smbInfo.isAvailable() ? "En línea" : "No disponible");
                printer.setConnectionType("SMB");
                printer.setPort(445);
                return printer;
            }
        } catch (Exception e) {
            log.trace("SMB no disponible en {}: {}", ip, e.getMessage());
        }
        return null;
    }
    
    /**
     * Descubre impresoras usando protocolo SNMP
     * Este método FUNCIONA CROSS-VLAN si hay enrutamiento IP y firewall permite SNMP
     */
    private DiscoveredPrinter scanViaSNMP(String ip) {
        // Verificar si SNMP está habilitado
        if (!snmpEnabled) {
            return null;
        }
        
        Snmp snmp = null;
        try {
            log.debug("🔍 Intentando SNMP en {} (timeout: {}ms)", ip, snmpTimeout);
            
            // Crear transporte SNMP
            TransportMapping<?> transport = new DefaultUdpTransportMapping();
            snmp = new Snmp(transport);
            transport.listen();
            
            // Crear target con más reintentos para cross-VLAN
            CommunityTarget target = new CommunityTarget();
            target.setCommunity(new OctetString(SNMP_COMMUNITY));
            target.setAddress(new UdpAddress(ip + "/" + SNMP_PORT));
            target.setRetries(3); // Aumentado de 1 a 3 para redes cross-VLAN
            target.setTimeout(snmpTimeout);
            target.setVersion(SnmpConstants.version2c);
            
            // Intentar obtener descripción del sistema
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(OID_SYS_DESCR)));
            pdu.add(new VariableBinding(new OID(OID_SYS_NAME)));
            pdu.setType(PDU.GET);
            
            ResponseEvent response = snmp.send(pdu, target);
            
            if (response != null && response.getResponse() != null) {
                PDU responsePDU = response.getResponse();
                
                log.debug("✅ SNMP respondió desde {}", ip);
                
                // Verificar si es una impresora
                String sysDescr = responsePDU.get(0).getVariable().toString().toLowerCase();
                log.debug("Descripción SNMP: {}", sysDescr);
                
                // Buscar palabras clave de impresoras
                if (containsPrinterKeywords(sysDescr)) {
                    log.info("✅ Impresora detectada vía SNMP en {}: {}", ip, sysDescr);
                    DiscoveredPrinter printer = new DiscoveredPrinter();
                    printer.setIp(ip);
                    
                    // Obtener nombre del sistema
                    String sysName = responsePDU.size() > 1 ? 
                        responsePDU.get(1).getVariable().toString() : 
                        resolveDNSName(ip);
                    printer.setName(sysName);
                    
                    // Extraer modelo de la descripción
                    printer.setModel(extractModelFromDescription(sysDescr));
                    printer.setType("Red - SNMP");
                    printer.setStatus("En línea");
                    printer.setConnectionType("RED");
                    printer.setPort(SNMP_PORT);
                    
                    return printer;
                } else {
                    log.debug("❌ {} respondió SNMP pero no es impresora: {}", ip, sysDescr);
                }
            } else {
                log.debug("❌ SNMP timeout en {}", ip);
            }
        } catch (IOException e) {
            log.debug("❌ SNMP error en {}: {}", ip, e.getMessage());
        } finally {
            if (snmp != null) {
                try {
                    snmp.close();
                } catch (IOException e) {
                    // Ignorar
                }
            }
        }
        return null;
    }
    
    /**
     * Verifica si la descripción contiene palabras clave de impresoras
     */
    private boolean containsPrinterKeywords(String description) {
        String[] keywords = {
            "printer", "impresora", "hp", "canon", "epson", "brother", 
            "xerox", "ricoh", "samsung", "kyocera", "lexmark", "dell",
            "print server", "jetdirect", "laserjet", "deskjet", "officejet"
        };
        
        for (String keyword : keywords) {
            if (description.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Extrae el modelo de impresora de la descripción SNMP
     */
    private String extractModelFromDescription(String description) {
        // Intentar extraer marca y modelo
        String[] brands = {"HP", "Canon", "Epson", "Brother", "Xerox", "Ricoh", "Samsung", "Kyocera", "Lexmark"};
        
        for (String brand : brands) {
            if (description.toUpperCase().contains(brand.toUpperCase())) {
                // Intentar extraer el modelo después de la marca
                int idx = description.toUpperCase().indexOf(brand.toUpperCase());
                String afterBrand = description.substring(idx);
                String[] parts = afterBrand.split("[\\s,;]+");
                if (parts.length >= 2) {
                    return parts[0] + " " + parts[1];
                }
                return brand;
            }
        }
        
        return "Impresora de Red";
    }

    /**
     * Verifica si un host es alcanzable
     * NOTA: Este método NO funciona bien cross-VLAN porque usa ICMP
     * Por eso ahora se intenta SNMP primero
     */
    private boolean isHostReachable(String ip, int timeout) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isReachable(timeout);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Verifica si un puerto está abierto
     */
    private boolean isPortOpen(String ip, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeout);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Genera lista de IPs desde un rango CIDR
     */
    private List<String> generateIPsFromRange(String cidr) {
        List<String> ips = new ArrayList<>();
        try {
            String[] parts = cidr.split("/");
            String baseIP = parts[0];
            int prefix = Integer.parseInt(parts[1]);
            
            String[] octets = baseIP.split("\\.");
            int baseAddress = (Integer.parseInt(octets[0]) << 24) |
                             (Integer.parseInt(octets[1]) << 16) |
                             (Integer.parseInt(octets[2]) << 8) |
                             Integer.parseInt(octets[3]);
            
            int mask = 0xFFFFFFFF << (32 - prefix);
            int network = baseAddress & mask;
            int broadcast = network | ~mask;
            
            for (int i = network + 1; i < broadcast; i++) {
                String ip = String.format("%d.%d.%d.%d",
                    (i >> 24) & 0xFF,
                    (i >> 16) & 0xFF,
                    (i >> 8) & 0xFF,
                    i & 0xFF);
                ips.add(ip);
            }
        } catch (Exception e) {
            log.error("Error generando rango de IPs: {}", e.getMessage());
        }
        return ips;
    }

    /**
     * Intenta resolver el nombre DNS de una IP
     */
    private String resolveDNSName(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            String hostname = addr.getHostName();
            return hostname.equals(ip) ? "Impresora-" + ip.replace(".", "-") : hostname;
        } catch (Exception e) {
            return "Impresora-" + ip.replace(".", "-");
        }
    }

    /**
     * Obtiene información adicional de una impresora vía SNMP
     * (para actualizar datos de impresoras ya descubiertas)
     */
    public Map<String, String> getPrinterInfoViaSNMP(String ip) {
        Map<String, String> info = new HashMap<>();
        Snmp snmp = null;
        
        try {
            TransportMapping<?> transport = new DefaultUdpTransportMapping();
            snmp = new Snmp(transport);
            transport.listen();
            
            CommunityTarget target = new CommunityTarget();
            target.setCommunity(new OctetString(SNMP_COMMUNITY));
            target.setAddress(new UdpAddress(ip + "/" + SNMP_PORT));
            target.setRetries(2);
            target.setTimeout(1500);
            target.setVersion(SnmpConstants.version2c);
            
            // Obtener múltiples OIDs
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(OID_SYS_DESCR)));
            pdu.add(new VariableBinding(new OID(OID_SYS_NAME)));
            pdu.add(new VariableBinding(new OID(OID_PRINTER_MODEL)));
            pdu.add(new VariableBinding(new OID(OID_PRINTER_SERIAL)));
            pdu.setType(PDU.GET);
            
            ResponseEvent response = snmp.send(pdu, target);
            
            if (response != null && response.getResponse() != null) {
                PDU responsePDU = response.getResponse();
                
                if (responsePDU.size() > 0) info.put("description", responsePDU.get(0).getVariable().toString());
                if (responsePDU.size() > 1) info.put("name", responsePDU.get(1).getVariable().toString());
                if (responsePDU.size() > 2) info.put("model", responsePDU.get(2).getVariable().toString());
                if (responsePDU.size() > 3) info.put("serial", responsePDU.get(3).getVariable().toString());
            }
        } catch (IOException e) {
            log.error("Error obteniendo info SNMP de {}: {}", ip, e.getMessage());
        } finally {
            if (snmp != null) {
                try {
                    snmp.close();
                } catch (IOException e) {
                    // Ignorar
                }
            }
        }
        
        return info;
    }

    /**
     * Determina el tipo de impresora local
     */
    private String determineLocalPrinterType(PrintService service) {
        String name = service.getName().toLowerCase();
        if (name.contains("usb")) return "USB";
        if (name.contains("network") || name.contains("net")) return "Red";
        if (name.contains("bluetooth") || name.contains("bt")) return "Bluetooth";
        if (name.contains("wireless") || name.contains("wifi")) return "WiFi";
        return "Local";
    }

    /**
     * Extrae el modelo de la impresora
     */
    private String extractPrinterModel(PrintService service) {
        String name = service.getName();
        // Intentar extraer el modelo del nombre
        String[] parts = name.split(" ");
        return parts.length > 1 ? parts[0] + " " + parts[1] : name;
    }

    /**
     * Obtiene el protocolo según el puerto
     */
    private String getPortProtocol(int port) {
        switch (port) {
            case 9100: return "RAW";
            case 631: return "IPP";
            case 515: return "LPD";
            default: return "Desconocido";
        }
    }

    /**
     * Registra una impresora descubierta en la base de datos
     * Si ya existe, NO la sobrescribe y mantiene el puerto IPP asignado
     * @return Printer si se registró exitosamente, null si ya existía
     */
    @Transactional
    public Printer registerDiscoveredPrinter(DiscoveredPrinter discovered, es.ucm.fdi.iu.model.User user) {
        try {
            // Generar IP para impresoras locales sin IP
            String printerIp = discovered.getIp();
            if (printerIp == null || printerIp.isEmpty()) {
                printerIp = "LOCAL-" + Math.abs(discovered.getName().hashCode());
            }
            
            // Verificar si ya existe por IP
            List<Printer> existingByIp = em.createQuery(
                    "SELECT p FROM Printer p WHERE p.ip = :ip", Printer.class)
                    .setParameter("ip", printerIp)
                    .getResultList();
            
            if (!existingByIp.isEmpty()) {
                Printer existing = existingByIp.get(0);
                log.info("🔒 Impresora ya registrada (IP: {}), manteniendo configuración existente: {} (Puerto IPP: {})", 
                    printerIp, existing.getAlias(), existing.getIppPort());
                return null; // Retornar null para indicar que no se agregó (ya existe)
            }
            
            // Verificar si ya existe por alias (nombre similar)
            List<Printer> existingByAlias = em.createQuery(
                    "SELECT p FROM Printer p WHERE LOWER(p.alias) = LOWER(:alias)", Printer.class)
                    .setParameter("alias", discovered.getName())
                    .getResultList();
            
            if (!existingByAlias.isEmpty()) {
                Printer existing = existingByAlias.get(0);
                log.info("🔒 Impresora ya registrada (Nombre: {}), manteniendo configuración existente (Puerto IPP: {})", 
                    existing.getAlias(), existing.getIppPort());
                return null;
            }
            
            // Crear nueva impresora
            Printer printer = new Printer();
            printer.setAlias(discovered.getName());
            printer.setModel(discovered.getModel());
            printer.setLocation(discovered.getConnectionType() + " - " + discovered.getType());
            printer.setIp(printerIp);
            printer.setInstance(user);
            printer.setInk(100);
            printer.setPaper(100);
            
            // Configurar protocolo y puerto si están disponibles
            if (discovered.getConnectionType() != null) {
                if (discovered.getConnectionType().contains("IPP")) {
                    printer.setProtocol("IPP");
                    printer.setPort(631);
                } else if (discovered.getConnectionType().contains("SMB")) {
                    printer.setProtocol("SMB");
                    printer.setPort(445);
                } else if (discovered.getPort() > 0) {
                    printer.setPort(discovered.getPort());
                }
            }
            
            // Asignar puerto IPP único y dedicado
            Integer maxPort = em.createQuery(
                "SELECT MAX(p.ippPort) FROM Printer p", Integer.class)
                .getSingleResult();
            int nextPort = (maxPort != null) ? maxPort + 1 : 8631;
            printer.setIppPort(nextPort);
            log.info("Puerto IPP {} asignado a impresora descubierta {}", nextPort, discovered.getName());
            
            em.persist(printer);
            em.flush(); // Asegurar que se persiste inmediatamente
            log.info("✅ Impresora registrada: {} en {} ({})", 
                printer.getAlias(), printer.getIp(), printer.getModel());
            return printer;
            
        } catch (Exception e) {
            log.error("❌ Error al registrar impresora {}: {}", 
                discovered.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Clase para almacenar información de impresoras descubiertas
     */
    public static class DiscoveredPrinter {
        private String name;
        private String ip;
        private String model;
        private String type;
        private String status;
        private String connectionType; // RED, USB, COMPARTIDA
        private int port;

        // Getters y Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getConnectionType() { return connectionType; }
        public void setConnectionType(String connectionType) { this.connectionType = connectionType; }
        
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }
    
    /**
     * Clase para el estado del escaneo
     */
    public static class ScanStatus {
        private boolean scanning;
        private boolean cancelled;
        private int totalHosts;
        private int scannedHosts;
        private int foundPrinters;
        private int progress;
        private String currentNetwork;
        private long elapsedTime;
        private long estimatedRemaining;
        
        // Getters y Setters
        public boolean isScanning() { return scanning; }
        public void setScanning(boolean scanning) { this.scanning = scanning; }
        
        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
        
        public int getTotalHosts() { return totalHosts; }
        public void setTotalHosts(int totalHosts) { this.totalHosts = totalHosts; }
        
        public int getScannedHosts() { return scannedHosts; }
        public void setScannedHosts(int scannedHosts) { this.scannedHosts = scannedHosts; }
        
        public int getFoundPrinters() { return foundPrinters; }
        public void setFoundPrinters(int foundPrinters) { this.foundPrinters = foundPrinters; }
        
        public int getProgress() { return progress; }
        public void setProgress(int progress) { this.progress = progress; }
        
        public String getCurrentNetwork() { return currentNetwork; }
        public void setCurrentNetwork(String currentNetwork) { this.currentNetwork = currentNetwork; }
        
        public long getElapsedTime() { return elapsedTime; }
        public void setElapsedTime(long elapsedTime) { this.elapsedTime = elapsedTime; }
        
        public long getEstimatedRemaining() { return estimatedRemaining; }
        public void setEstimatedRemaining(long estimatedRemaining) { this.estimatedRemaining = estimatedRemaining; }
    }
}
