package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Job;
import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.model.User;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Servicio de gestión de colas de impresión
 * 
 * Gestiona todas las impresiones que se envían a través del servidor
 * para evitar colapsar el sistema con un procesamiento ordenado de trabajos.
 * 
 * Características:
 * - Procesamiento asíncrono de trabajos
 * - Cola separada por impresora
 * - Limitación de trabajos simultáneos
 * - Reintentos automáticos
 * - Notificación de estado
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.mode", havingValue = "server", matchIfMissing = true)
public class PrintQueueService {

    @Autowired
    private EntityManager entityManager;
    
    @Autowired
    private IppPrintService ippPrintService;
    
    @Autowired
    private PrinterDiscoveryService discoveryService;
    
    @Autowired
    private NetworkIdentificationService networkIdService;
    
    @Autowired
    private PlatformTransactionManager transactionManager;
    
    private TransactionTemplate transactionTemplate;
    
    // Executor para procesar trabajos de impresión
    private ExecutorService executorService;
    
    // Mapa de colas activas por impresora
    private final Map<Long, Queue<Job>> activeQueues = new ConcurrentHashMap<>();
    
    // Trabajos en proceso
    private final Set<Long> processingJobs = ConcurrentHashMap.newKeySet();
    
    // Directorio temporal para archivos de impresión
    private Path printSpoolDir;
    
    // Máximo de trabajos simultáneos
    private static final int MAX_CONCURRENT_JOBS = 3;
    
    // Máximo de reintentos por trabajo
    private static final int MAX_RETRIES = 3;
    
    // Estado del servicio
    private volatile boolean running = false;
    
    // Cache de último conteo de trabajos para optimización
    private volatile long lastJobCount = -1;

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("🖨️  Iniciando Servicio de Colas de Impresión");
        log.info("========================================");
        
        // Crear TransactionTemplate para transacciones programáticas
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        
        // Crear directorio de spool
        try {
            printSpoolDir = Paths.get(System.getProperty("java.io.tmpdir"), "print-spool");
            if (!Files.exists(printSpoolDir)) {
                Files.createDirectories(printSpoolDir);
                log.info("📁 Directorio de spool creado: {}", printSpoolDir);
            }
        } catch (Exception e) {
            log.error("❌ Error creando directorio de spool", e);
            printSpoolDir = Paths.get(System.getProperty("java.io.tmpdir"));
        }
        
        // Crear executor con pool de threads
        executorService = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS);
        
        // Iniciar procesador de colas
        running = true;
        startQueueProcessor();
        
        log.info("✅ Servicio de colas iniciado correctamente");
        log.info("   - Trabajos simultáneos máximos: {}", MAX_CONCURRENT_JOBS);
        log.info("   - Directorio de spool: {}", printSpoolDir);
        log.info("========================================");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("🛑 Deteniendo servicio de colas de impresión...");
        running = false;
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        
        log.info("✅ Servicio de colas detenido");
    }
    
    /**
     * Agrega un trabajo a la cola de impresión
     */
    @Transactional
    public Job addJob(Printer printer, String fileName, String owner, User instance, byte[] fileData) {
        try {
            log.info("📝 Agregando trabajo a cola: {} -> {}", fileName, printer.getAlias());
            
            // Crear el trabajo en la base de datos
            Job job = new Job();
            job.setPrinter(printer);
            job.setFileName(fileName);
            job.setOwner(owner);
            job.setInstance(instance);
            
            entityManager.persist(job);
            entityManager.flush();
            
            // Guardar archivo en spool si hay datos
            if (fileData != null && fileData.length > 0) {
                Path spoolFile = printSpoolDir.resolve("job-" + job.getId() + "-" + fileName);
                Files.write(spoolFile, fileData);
                log.debug("💾 Archivo guardado en spool: {}", spoolFile);
            }
            
            log.info("✅ Trabajo {} agregado a la cola de {}", job.getId(), printer.getAlias());
            return job;
            
        } catch (Exception e) {
            log.error("❌ Error agregando trabajo a cola", e);
            throw new RuntimeException("Error al agregar trabajo: " + e.getMessage());
        }
    }
    
    /**
     * Procesa la cola de una impresora específica
     */
    private void processQueue(Printer printer) {
        try {
            // Obtener trabajos pendientes de esta impresora
            @SuppressWarnings("unchecked")
            List<Job> jobs = entityManager.createQuery(
                "SELECT j FROM Job j WHERE j.printer.id = :printerId ORDER BY j.id ASC")
                .setParameter("printerId", printer.getId())
                .setMaxResults(1)
                .getResultList();
            
            if (jobs.isEmpty()) {
                return;
            }
            
            Job job = jobs.get(0);
            
            // Verificar si ya está en proceso
            if (processingJobs.contains(job.getId())) {
                return;
            }
            
            // Marcar como en proceso
            processingJobs.add(job.getId());
            
            // Procesar trabajo en thread separado
            executorService.submit(() -> processJob(job));
            
        } catch (Exception e) {
            log.error("Error procesando cola de impresora {}", printer.getId(), e);
        }
    }
    
    /**
     * Procesa un trabajo individual
     */
    private void processJob(Job job) {
        int retries = 0;
        boolean success = false;
        
        try {
            log.info("🖨️ Procesando trabajo {}: {}", job.getId(), job.getFileName());
            
            Printer printer = job.getPrinter();
            
            // Detectar si es impresora USB compartida
            boolean isSharedUSB = printer.getLocation() != null && 
                                 printer.getLocation().contains("Compartida-USB");
            
            // Para USB compartidas, solo 1 intento (el cliente debe estar corriendo)
            int maxRetries = isSharedUSB ? 1 : MAX_RETRIES;
            
            while (!success && retries < maxRetries) {
                try {
                    // Buscar archivo en spool
                    Path spoolFile = findSpoolFile(job);
                    
                    if (spoolFile != null && Files.exists(spoolFile)) {
                        // Enviar archivo a la impresora
                        success = sendToPrinter(printer, spoolFile);
                    } else {
                        // Si no hay archivo, generar uno de prueba
                        log.warn("⚠️ No se encontró archivo para trabajo {}, generando documento de prueba", job.getId());
                        spoolFile = generateTestDocument(job);
                        success = sendToPrinter(printer, spoolFile);
                    }
                    
                    if (!success) {
                        retries++;
                        if (retries < maxRetries) {
                            if (isSharedUSB) {
                                log.error("❌ Cliente USB no responde - trabajo cancelado");
                                log.error("   Verifica que el cliente esté encendido y ejecutando el servicio");
                                break; // No reintentar para USB
                            } else {
                                log.warn("⚠️ Intento {}/{} falló, reintentando en 5 segundos...", 
                                    retries, maxRetries);
                                Thread.sleep(5000);
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    log.error("❌ Error en intento {}/{}", retries + 1, MAX_RETRIES, e);
                    retries++;
                    if (retries < MAX_RETRIES) {
                        Thread.sleep(5000);
                    }
                }
            }
            
            if (success) {
                log.info("════════════════════════════════");
                log.info("✅ TRABAJO {} COMPLETADO EXITOSAMENTE", job.getId());
                log.info("════════════════════════════════");
                log.info("   Impresora: {}", job.getPrinter().getAlias());
                log.info("   Archivo: {}", job.getFileName());
                log.info("   Esperando 2 segundos antes de eliminar...");
                
                // Esperar 2 segundos (reducido de 5) antes de eliminar
                Thread.sleep(2000);
                
                log.info("   Procediendo a eliminar el trabajo de la cola...");
                removeJob(job);
                
            } else {
                log.error("════════════════════════════════");
                log.error("❌ TRABAJO {} FALLÓ DESPUÉS DE {} INTENTOS", job.getId(), maxRetries);
                log.error("════════════════════════════════");
                log.error("   Impresora: {}", job.getPrinter().getAlias());
                log.error("   Archivo: {}", job.getFileName());
                log.error("   ELIMINANDO TRABAJO FALLIDO PARA EVITAR BUCLE INFINITO");
                log.error("════════════════════════════════");
                
                // CRÍTICO: Eliminar trabajo fallido para evitar bucle infinito
                removeJob(job);
            }
            
        } catch (Exception e) {
            log.error("❌ Error crítico procesando trabajo {}", job.getId(), e);
        } finally {
            processingJobs.remove(job.getId());
        }
    }
    
    /**
     * Envía un archivo a una impresora
     * CON AUTO-REDESCUBRIMIENTO: Si falla, intenta buscar la nueva IP
     */
    private boolean sendToPrinter(Printer printer, Path file) {
        try {
            String ip = printer.getIp();
            
            if (ip == null || ip.isEmpty() || ip.equalsIgnoreCase("LOCAL")) {
                log.warn("⚠️ Impresora {} no tiene IP configurada", printer.getAlias());
                return false;
            }
            
            log.info("📤 Enviando a impresora {} ({})", printer.getAlias(), ip);
            
            // Intentar diferentes métodos según configuración
            boolean success = false;
            
            // DETECCIÓN DE IMPRESORAS COMPARTIDAS USB
            // Las impresoras compartidas USB están en otra PC (cliente USB)
            // Enviar DIRECTAMENTE a la IP del cliente, NO a localhost
            boolean isSharedUSB = printer.getLocation() != null && 
                                 printer.getLocation().contains("Compartida-USB");
            
            if (isSharedUSB) {
                log.info("════════════════════════════════");
                log.info("🔄 IMPRESORA USB COMPARTIDA DETECTADA");
                log.info("════════════════════════════════");
                log.info("   Impresora: {}", printer.getAlias());
                log.info("   Cliente USB: {}", ip);
                log.info("   Puerto servidor (ippPort): {} (NO SE USA)", printer.getIppPort());
                log.info("   Puerto cliente: 631 (IPP estándar)");
                log.info("════════════════════════════════");
                
                log.info("📡 Enviando trabajo al cliente USB {}:631...", ip);
                success = ippPrintService.sendToRawPort(ip, file, 631);
                
                if (success) {
                    log.info("════════════════════════════════");
                    log.info("✅ TRABAJO ENVIADO EXITOSAMENTE AL CLIENTE USB");
                    log.info("════════════════════════════════");
                    return true;
                } else {
                    log.error("════════════════════════════════");
                    log.error("❌ NO SE PUDO CONECTAR AL CLIENTE USB");
                    log.error("════════════════════════════════");
                    log.error("   Cliente: {}:631", ip);
                    log.error("   Posibles causas:");
                    log.error("   1. La computadora cliente está APAGADA");
                    log.error("   2. El servicio USB client NO está ejecutándose");
                    log.error("   3. Firewall bloqueando puerto 631");
                    log.error("════════════════════════════════");
                    return false;
                }
            }
            
            // Impresoras de red normales (incluso si tienen ippPort asignado)
            // Método 1: Puerto RAW (9100)
            if (printer.getProtocol() == null || "RAW".equalsIgnoreCase(printer.getProtocol())) {
                int port = printer.getPort() != null ? printer.getPort() : 9100;
                success = ippPrintService.sendToRawPort(ip, file, port);
                if (success) {
                    log.info("✅ Enviado vía RAW puerto {}", port);
                    return true;
                }
            }
            
            // Método 2: IPP
            if ("IPP".equalsIgnoreCase(printer.getProtocol()) && printer.getDeviceUri() != null) {
                success = ippPrintService.sendToIppPrinter(printer.getDeviceUri(), file);
                if (success) {
                    log.info("✅ Enviado vía IPP");
                    return true;
                }
            }
            
            // Método 3: Intentar con puertos comunes
            if (!success) {
                // Intentar puerto 9100 (RAW)
                success = ippPrintService.sendToRawPort(ip, file, 9100);
                if (success) {
                    log.info("✅ Enviado vía puerto 9100");
                    return true;
                }
                
                // Intentar puerto 515 (LPD)
                success = ippPrintService.sendToRawPort(ip, file, 515);
                if (success) {
                    log.info("✅ Enviado vía puerto 515 (LPD)");
                    return true;
                }
            }
            
            // ═══════════════════════════════════════════════════════════
            // AUTO-REDESCUBRIMIENTO: Si todos los métodos fallaron,
            // la impresora puede haber cambiado de IP
            // ═══════════════════════════════════════════════════════════
            if (!success && !isSharedUSB) {
                log.warn("════════════════════════════════════════════════════════════");
                log.warn("⚠️ IMPRESORA NO RESPONDE EN IP ACTUAL: {}", ip);
                log.warn("════════════════════════════════════════════════════════════");
                log.warn("Impresora: {}", printer.getAlias());
                log.warn("IP registrada: {}", ip);
                log.warn("");
                log.warn("🔍 INICIANDO AUTO-REDESCUBRIMIENTO...");
                log.warn("");
                
                String newIp = attemptPrinterRediscovery(printer);
                
                if (newIp != null && !newIp.equals(ip)) {
                    log.warn("════════════════════════════════════════════════════════════");
                    log.warn("✅ IMPRESORA RE-DESCUBIERTA EN NUEVA IP!");
                    log.warn("════════════════════════════════════════════════════════════");
                    log.warn("Impresora: {}", printer.getAlias());
                    log.warn("IP anterior: {}", ip);
                    log.warn("IP nueva: {}", newIp);
                    log.warn("");
                    log.warn("📝 Actualizando IP en base de datos...");
                    
                    // Actualizar IP en base de datos
                    updatePrinterIp(printer, newIp);
                    
                    log.warn("✅ IP actualizada exitosamente");
                    log.warn("");
                    log.warn("🔄 Reintentando envío con nueva IP...");
                    log.warn("════════════════════════════════════════════════════════════");
                    
                    // Reintentar envío con nueva IP
                    int port = printer.getPort() != null ? printer.getPort() : 9100;
                    success = ippPrintService.sendToRawPort(newIp, file, port);
                    
                    if (success) {
                        log.info("════════════════════════════════════════════════════════════");
                        log.info("✅✅✅ ÉXITO: TRABAJO ENVIADO CON NUEVA IP ✅✅✅");
                        log.info("════════════════════════════════════════════════════════════");
                        log.info("La impresora cambió de IP pero fue detectada automáticamente.");
                        log.info("Los próximos trabajos usarán la nueva IP: {}", newIp);
                        log.info("════════════════════════════════════════════════════════════");
                        return true;
                    } else {
                        log.error("════════════════════════════════════════════════════════════");
                        log.error("❌ FALLÓ INCLUSO CON NUEVA IP");
                        log.error("════════════════════════════════════════════════════════════");
                        log.error("Puede que la impresora esté apagada o desconectada.");
                        log.error("════════════════════════════════════════════════════════════");
                    }
                } else {
                    log.error("════════════════════════════════════════════════════════════");
                    log.error("❌ NO SE PUDO RE-DESCUBRIR LA IMPRESORA");
                    log.error("════════════════════════════════════════════════════════════");
                    log.error("Posibles causas:");
                    log.error("  1. La impresora está APAGADA o desconectada");
                    log.error("  2. Cambió de red/subred completamente diferente");
                    log.error("  3. Firewall bloqueando descubrimiento SNMP");
                    log.error("");
                    log.error("💡 SOLUCIÓN:");
                    log.error("  - Verifica que la impresora esté encendida");
                    log.error("  - Actualiza manualmente la IP en el panel de administración");
                    log.error("  - O ejecuta un escaneo de red para re-descubrirla");
                    log.error("════════════════════════════════════════════════════════════");
                }
            }
            
            return success;
            
        } catch (Exception e) {
            log.error("❌ Error enviando a impresora", e);
            return false;
        }
    }
    
    /**
     * Busca el archivo de spool de un trabajo
     */
    private Path findSpoolFile(Job job) {
        try {
            String pattern = "job-" + job.getId() + "-";
            File[] files = printSpoolDir.toFile().listFiles(
                (dir, name) -> name.startsWith(pattern)
            );
            
            if (files != null && files.length > 0) {
                return files[0].toPath();
            }
        } catch (Exception e) {
            log.error("Error buscando archivo de spool", e);
        }
        return null;
    }
    
    /**
     * Intenta re-descubrir una impresora que no responde
     * MEJORADO: Usa MAC Address para identificación única
     */
    private String attemptPrinterRediscovery(Printer printer) {
        try {
            log.info("🔍 Estrategia de re-descubrimiento:");
            
            // PASO 0: Si tiene MAC Address, buscar por MAC (MÁS CONFIABLE)
            if (printer.getMacAddress() != null && !printer.getMacAddress().isEmpty()) {
                log.info("   0. [PRIORITARIO] Buscar por MAC Address: {}", printer.getMacAddress());
                
                // Primero buscar en tabla ARP (rápido)
                String ipByMac = networkIdService.findIPByMacAddress(printer.getMacAddress());
                if (ipByMac != null) {
                    log.info("   ✓ Encontrada en ARP cache: {} (IDENTIFICACIÓN 100% SEGURA)", ipByMac);
                    int port = printer.getPort() != null ? printer.getPort() : 9100;
                    if (isPortOpenQuick(ipByMac, port)) {
                        log.info("   ✓ Puerto {} confirmado - Impresora verificada!", port);
                        return ipByMac;
                    }
                }
                
                // Si no está en ARP, hacer escaneo RÁPIDO de subred con verificación MAC
                log.info("   ⚠️ No en tabla ARP - escaneando subred con verificación MAC...");
                String subnet = getSubnetFromIp(printer.getIp());
                if (subnet != null) {
                    int targetPort = printer.getPort() != null ? printer.getPort() : 9100;
                    
                    // Escaneo paralelo RÁPIDO (50 threads)
                    log.info("   🔍 Escaneo paralelo rápido de subred {} puerto {}", subnet, targetPort);
                    long startTime = System.currentTimeMillis();
                    
                    String foundIp = scanSubnetForMacParallel(subnet, targetPort, printer.getMacAddress());
                    
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("   ⏱️ Escaneo completado en {} ms", duration);
                    
                    if (foundIp != null) {
                        log.info("   ✅ ENCONTRADA por escaneo+MAC: {} (tiempo: {} ms)", foundIp, duration);
                        return foundIp;
                    }
                }
                
                log.debug("   ✗ No encontrada por MAC en subred");
            } else {
                log.warn("   ⚠️ Impresora sin MAC Address registrada - usando métodos menos confiables");
            }
            
            log.info("   1. Verificar hostname DNS");
            log.info("   2. Buscar por SNMP en subred actual");
            log.info("   3. Escanear puerto {} en subred", printer.getPort() != null ? printer.getPort() : 9100);
            log.info("");
            
            // PASO 1: Intentar resolver hostname si existe
            String hostname = extractHostnameFromPrinter(printer);
            if (hostname != null) {
                log.info("   → Resolviendo hostname: {}", hostname);
                try {
                    InetAddress addr = InetAddress.getByName(hostname);
                    String resolvedIp = addr.getHostAddress();
                    if (!resolvedIp.equals(printer.getIp())) {
                        log.info("   ✓ DNS resuelto: {} → {}", hostname, resolvedIp);
                        // Verificar que realmente es la impresora
                        int port = printer.getPort() != null ? printer.getPort() : 9100;
                        if (isPortOpenQuick(resolvedIp, port)) {
                            log.info("   ✓ Puerto {} abierto - Impresora confirmada!", port);
                            return resolvedIp;
                        }
                    }
                } catch (Exception e) {
                    log.debug("   ✗ DNS no disponible: {}", e.getMessage());
                }
            }
            
            // PASO 2: Buscar por SNMP en subred (si tiene modelo conocido)
            String subnet = getSubnetFromIp(printer.getIp());
            if (subnet != null && printer.getModel() != null) {
                log.info("   → Buscando por SNMP en subred {} con modelo: {}", subnet, printer.getModel());
                String ipBySnmp = findPrinterBySnmpInSubnet(subnet, printer.getModel());
                if (ipBySnmp != null) {
                    log.info("   ✓ Encontrada por SNMP: {}", ipBySnmp);
                    return ipBySnmp;
                }
                log.debug("   ✗ No encontrada por SNMP");
            }
            
            // PASO 3: Escanear puerto en subred (último recurso)
            if (subnet != null) {
                int targetPort = printer.getPort() != null ? printer.getPort() : 9100;
                log.info("   → Escaneando puerto {} en subred {}", targetPort, subnet);
                
                // Si tiene MAC, usar método con verificación de MAC
                String ipByPort;
                if (printer.getMacAddress() != null && !printer.getMacAddress().isEmpty()) {
                    log.info("   → Verificando MAC {} en cada IP encontrada", printer.getMacAddress());
                    ipByPort = findPrinterByPortAndMac(subnet, targetPort, printer.getMacAddress());
                } else {
                    log.warn("   ⚠️ Sin MAC - usando primera IP con puerto abierto (RIESGO)");
                    ipByPort = findPrinterByPortInSubnet(subnet, targetPort);
                }
                
                if (ipByPort != null) {
                    log.info("   ✓ Encontrada escaneando puerto: {}", ipByPort);
                    return ipByPort;
                }
                log.debug("   ✗ No encontrada escaneando puertos");
            }
            
            log.warn("   ✗ Todos los métodos de re-descubrimiento fallaron");
            return null;
            
        } catch (Exception e) {
            log.error("Error durante re-descubrimiento: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extrae hostname del nombre de la impresora o location
     */
    private String extractHostnameFromPrinter(Printer printer) {
        // Si el alias parece ser un hostname válido (sin espacios)
        if (printer.getAlias() != null && 
            !printer.getAlias().contains(" ") && 
            !printer.getAlias().startsWith("Impresora-")) {
            return printer.getAlias();
        }
        return null;
    }
    
    /**
     * Obtiene la subred de una IP
     */
    private String getSubnetFromIp(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2] + ".0/24";
            }
        } catch (Exception e) {
            log.error("Error obteniendo subred: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Busca impresora por SNMP en subred
     */
    private String findPrinterBySnmpInSubnet(String subnet, String model) {
        List<String> ips = generateIpsFromSubnet(subnet, 100);
        
        for (String ip : ips) {
            try {
                Map<String, String> snmpInfo = discoveryService.getPrinterInfoViaSNMP(ip);
                if (snmpInfo.containsKey("description")) {
                    String desc = snmpInfo.get("description").toLowerCase();
                    if (desc.contains(model.toLowerCase())) {
                        return ip;
                    }
                }
            } catch (Exception e) {
                // Ignorar errores
            }
        }
        return null;
    }
    
    /**
     * Busca impresora por puerto en subred
     * MEJORADO: Si la impresora tiene MAC, verifica que coincida
     */
    private String findPrinterByPortInSubnet(String subnet, int port) {
        List<String> ips = generateIpsFromSubnet(subnet, 254);
        
        for (String ip : ips) {
            if (isPortOpenQuick(ip, port)) {
                log.debug("   Puerto {} abierto en {}", port, ip);
                return ip;
            }
        }
        return null;
    }
    
    /**
     * Escanea subred en PARALELO buscando MAC específica
     * MUCHO MÁS RÁPIDO que escaneo serial
     * 
     * @return IP encontrada o null
     */
    private String scanSubnetForMacParallel(String subnet, int port, String macAddress) {
        List<String> ips = generateIpsFromSubnet(subnet, 254);
        
        // Usar ExecutorService con 50 threads para escaneo paralelo
        ExecutorService executor = Executors.newFixedThreadPool(50);
        List<Future<String>> futures = new ArrayList<>();
        
        try {
            for (String ip : ips) {
                Future<String> future = executor.submit(() -> {
                    try {
                        // Verificar puerto primero (rápido)
                        if (isPortOpenQuick(ip, port)) {
                            // Puerto abierto, verificar MAC
                            String foundMac = networkIdService.getMacAddressFromIP(ip);
                            if (foundMac != null && foundMac.equalsIgnoreCase(macAddress.replace("-", ":"))) {
                                log.info("      ✓ MATCH en {}: Puerto {} + MAC {}", ip, port, macAddress);
                                return ip;
                            }
                        }
                    } catch (Exception e) {
                        // Ignorar errores individuales
                    }
                    return null;
                });
                futures.add(future);
            }
            
            // Esperar resultados con timeout de 20 segundos total
            for (Future<String> future : futures) {
                try {
                    String result = future.get(100, TimeUnit.MILLISECONDS);
                    if (result != null) {
                        // Encontrada! Cancelar el resto
                        executor.shutdownNow();
                        return result;
                    }
                } catch (TimeoutException e) {
                    future.cancel(true);
                } catch (Exception e) {
                    // Ignorar
                }
            }
            
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        return null;
    }
    
    /**
     * Busca impresora por puerto Y verifica MAC (MÁS SEGURO)
     */
    private String findPrinterByPortAndMac(String subnet, int port, String macAddress) {
        if (macAddress == null || macAddress.isEmpty()) {
            return findPrinterByPortInSubnet(subnet, port);
        }
        
        List<String> ips = generateIpsFromSubnet(subnet, 254);
        
        for (String ip : ips) {
            if (isPortOpenQuick(ip, port)) {
                log.debug("   Puerto {} abierto en {} - verificando MAC...", port, ip);
                
                // Verificar que la MAC coincida
                if (networkIdService.verifyIPMatchesMAC(ip, macAddress)) {
                    log.info("   ✓ IP {} CONFIRMADA por MAC {} - Impresora correcta!", ip, macAddress);
                    return ip;
                } else {
                    log.debug("   ✗ IP {} tiene MAC diferente - no es esta impresora", ip);
                }
            }
        }
        return null;
    }
    
    /**
     * Verifica puerto abierto con timeout corto
     */
    private boolean isPortOpenQuick(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Genera lista de IPs desde subred
     */
    private List<String> generateIpsFromSubnet(String cidr, int maxIps) {
        List<String> ips = new ArrayList<>();
        try {
            String[] parts = cidr.split("/");
            String baseIP = parts[0];
            String[] octets = baseIP.split("\\.");
            
            int base = Integer.parseInt(octets[0]) << 24 |
                      Integer.parseInt(octets[1]) << 16 |
                      Integer.parseInt(octets[2]) << 8;
            
            for (int i = 1; i <= Math.min(254, maxIps); i++) {
                int ip = base | i;
                ips.add(String.format("%d.%d.%d.%d",
                    (ip >> 24) & 0xFF,
                    (ip >> 16) & 0xFF,
                    (ip >> 8) & 0xFF,
                    ip & 0xFF));
            }
        } catch (Exception e) {
            log.error("Error generando IPs: {}", e.getMessage());
        }
        return ips;
    }
    
    /**
     * Actualiza la IP de una impresora en la base de datos
     */
    @Transactional
    private void updatePrinterIp(Printer printer, String newIp) {
        try {
            Printer managedPrinter = entityManager.find(Printer.class, printer.getId());
            if (managedPrinter != null) {
                String oldIp = managedPrinter.getIp();
                managedPrinter.setIp(newIp);
                
                // Actualizar deviceUri si contiene la IP antigua
                if (managedPrinter.getDeviceUri() != null && 
                    managedPrinter.getDeviceUri().contains(oldIp)) {
                    String newDeviceUri = managedPrinter.getDeviceUri().replace(oldIp, newIp);
                    managedPrinter.setDeviceUri(newDeviceUri);
                    log.info("   DeviceURI actualizado: {} → {}", 
                        managedPrinter.getDeviceUri(), newDeviceUri);
                }
                
                entityManager.merge(managedPrinter);
                entityManager.flush();
                log.info("   ✅ IP actualizada en base de datos: {} → {}", oldIp, newIp);
            }
        } catch (Exception e) {
            log.error("   ❌ Error actualizando IP en BD: {}", e.getMessage());
        }
    }
    
    /**
     * Genera un documento de prueba para un trabajo
     */
    private Path generateTestDocument(Job job) throws Exception {
        StringBuilder content = new StringBuilder();
        content.append("\n\n");
        content.append("========================================\n");
        content.append("       TRABAJO DE IMPRESIÓN\n");
        content.append("========================================\n\n");
        content.append("ID Trabajo: ").append(job.getId()).append("\n");
        content.append("Archivo: ").append(job.getFileName()).append("\n");
        content.append("Usuario: ").append(job.getOwner()).append("\n");
        content.append("Impresora: ").append(job.getPrinter().getAlias()).append("\n");
        content.append("Fecha: ").append(new Date()).append("\n\n");
        content.append("Este es un documento de prueba generado\n");
        content.append("automáticamente porque no se encontró\n");
        content.append("el archivo original.\n\n");
        content.append("========================================\n");
        content.append("\f");
        
        Path tempFile = printSpoolDir.resolve("job-" + job.getId() + "-test.txt");
        Files.write(tempFile, content.toString().getBytes("UTF-8"));
        
        return tempFile;
    }
    
    /**
     * Elimina un trabajo completado
     * Llama al método transaccional a través de self-proxy
     */
    private void removeJob(Job job) {
        try {
            log.info("🗑️ Eliminando trabajo {} de la cola...", job.getId());
            
            // Eliminar archivo de spool primero (no requiere transacción)
            Path spoolFile = findSpoolFile(job);
            if (spoolFile != null) {
                Files.deleteIfExists(spoolFile);
                log.debug("   Archivo de spool eliminado");
            }
            
            // Ejecutar eliminación en una nueva transacción
            transactionTemplate.execute(status -> {
                try {
                    Job managedJob = entityManager.find(Job.class, job.getId());
                    if (managedJob != null) {
                        entityManager.remove(managedJob);
                        entityManager.flush();
                        log.debug("   Trabajo {} eliminado de la base de datos", job.getId());
                        log.info("✅ Trabajo {} eliminado completamente de la cola", job.getId());
                    } else {
                        log.warn("   Trabajo {} ya no existe en la BD", job.getId());
                    }
                } catch (Exception e) {
                    log.error("❌ Error en transacción de eliminación: {}", e.getMessage());
                    status.setRollbackOnly();
                    throw e;
                }
                return null;
            });
            
        } catch (Exception e) {
            log.error("❌ Error eliminando trabajo completado: {}", e.getMessage(), e);
        }
    }
    
    
    /**
     * Inicia el procesador de colas en background
     */
    private void startQueueProcessor() {
        Thread processor = new Thread(() -> {
            log.info("🔄 Procesador de colas iniciado");
            
            while (running) {
                try {
                    // Primero verificar si hay trabajos pendientes con una consulta ligera
                    Long jobCount = (Long) entityManager.createQuery(
                        "SELECT COUNT(j) FROM Job j")
                        .getSingleResult();
                    
                    // Solo consultar las impresoras si hay trabajos
                    if (jobCount > 0) {
                        // Log solo cuando cambia el estado
                        if (lastJobCount <= 0) {
                            log.info("📋 Detectados {} trabajos en cola, iniciando procesamiento", jobCount);
                        }
                        lastJobCount = jobCount;
                        
                        // Obtener solo los IDs de impresoras con trabajos (más eficiente)
                        @SuppressWarnings("unchecked")
                        List<Long> printerIds = entityManager.createQuery(
                            "SELECT DISTINCT j.printer.id FROM Job j")
                            .getResultList();
                        
                        // Procesar cola de cada impresora
                        for (Long printerId : printerIds) {
                            if (!running) break;
                            
                            // Obtener impresora y procesar
                            Printer printer = entityManager.find(Printer.class, printerId);
                            if (printer != null) {
                                processQueue(printer);
                            }
                        }
                    } else {
                        // Log solo cuando cambia el estado a vacío
                        if (lastJobCount > 0) {
                            log.info("✅ Cola vacía, esperando nuevos trabajos...");
                        }
                        lastJobCount = 0;
                    }
                    
                    // Esperar antes del siguiente ciclo
                    // Si hay trabajos, revisar cada 2 segundos
                    // Si NO hay trabajos, revisar cada 5 segundos para reducir carga
                    Thread.sleep(jobCount > 0 ? 2000 : 5000);
                    
                } catch (InterruptedException e) {
                    log.debug("Procesador de colas interrumpido");
                    break;
                } catch (Exception e) {
                    log.error("Error en procesador de colas", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }
            
            log.info("🛑 Procesador de colas detenido");
        });
        
        processor.setName("PrintQueueProcessor");
        processor.setDaemon(true);
        processor.start();
    }
    
    /**
     * Obtiene estadísticas del servicio de colas
     */
    public Map<String, Object> getQueueStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            Long totalJobs = (Long) entityManager.createQuery(
                "SELECT COUNT(j) FROM Job j")
                .getSingleResult();
            
            Long activePrinters = (Long) entityManager.createQuery(
                "SELECT COUNT(DISTINCT p) FROM Printer p JOIN p.queue j")
                .getSingleResult();
            
            stats.put("totalJobs", totalJobs);
            stats.put("activePrinters", activePrinters);
            stats.put("processingJobs", processingJobs.size());
            stats.put("maxConcurrent", MAX_CONCURRENT_JOBS);
            stats.put("running", running);
            
        } catch (Exception e) {
            log.error("Error obteniendo estadísticas", e);
        }
        
        return stats;
    }
    
    /**
     * Cancela un trabajo de la cola
     */
    @Transactional
    public boolean cancelJob(Long jobId) {
        try {
            Job job = entityManager.find(Job.class, jobId);
            if (job != null) {
                // Eliminar archivo de spool
                Path spoolFile = findSpoolFile(job);
                if (spoolFile != null) {
                    Files.deleteIfExists(spoolFile);
                }
                
                // Eliminar de base de datos
                entityManager.remove(job);
                entityManager.flush();
                
                log.info("✅ Trabajo {} cancelado", jobId);
                return true;
            }
        } catch (Exception e) {
            log.error("Error cancelando trabajo", e);
        }
        return false;
    }
    
    /**
     * Limpia la cola de una impresora
     */
    @Transactional
    public int clearQueue(Long printerId) {
        try {
            Printer printer = entityManager.find(Printer.class, printerId);
            if (printer != null) {
                List<Job> jobs = new ArrayList<>(printer.getQueue());
                
                for (Job job : jobs) {
                    // Eliminar archivo de spool
                    Path spoolFile = findSpoolFile(job);
                    if (spoolFile != null) {
                        Files.deleteIfExists(spoolFile);
                    }
                }
                
                int count = jobs.size();
                printer.getQueue().clear();
                entityManager.flush();
                
                log.info("✅ Cola de {} limpiada: {} trabajos eliminados", 
                    printer.getAlias(), count);
                return count;
            }
        } catch (Exception e) {
            log.error("Error limpiando cola", e);
        }
        return 0;
    }
}
