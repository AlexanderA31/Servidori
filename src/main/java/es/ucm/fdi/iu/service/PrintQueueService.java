package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Job;
import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.model.User;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
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
public class PrintQueueService {

    @Autowired
    private EntityManager entityManager;
    
    @Autowired
    private IppPrintService ippPrintService;
    
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
            
            while (!success && retries < MAX_RETRIES) {
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
                        if (retries < MAX_RETRIES) {
                            log.warn("⚠️ Intento {}/{} falló, reintentando en 5 segundos...", 
                                retries, MAX_RETRIES);
                            Thread.sleep(5000);
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
                log.info("✅ Trabajo {} completado exitosamente", job.getId());
                // Esperar 5 segundos antes de eliminar para que sea visible en la interfaz
                Thread.sleep(5000);
                removeJob(job);
            } else {
                log.error("❌ Trabajo {} falló después de {} intentos", job.getId(), MAX_RETRIES);
                // Mantener en cola o mover a cola de errores
            }
            
        } catch (Exception e) {
            log.error("❌ Error crítico procesando trabajo {}", job.getId(), e);
        } finally {
            processingJobs.remove(job.getId());
        }
    }
    
    /**
     * Envía un archivo a una impresora
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
     */
    @Transactional
    private void removeJob(Job job) {
        try {
            // Eliminar archivo de spool
            Path spoolFile = findSpoolFile(job);
            if (spoolFile != null) {
                Files.deleteIfExists(spoolFile);
            }
            
            // Eliminar de base de datos
            Job managedJob = entityManager.find(Job.class, job.getId());
            if (managedJob != null) {
                entityManager.remove(managedJob);
                entityManager.flush();
            }
            
        } catch (Exception e) {
            log.error("Error eliminando trabajo completado", e);
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
                            log.debug("📋 Detectados {} trabajos en cola, iniciando procesamiento", jobCount);
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
                            log.debug("✅ Cola vacía, esperando nuevos trabajos...");
                        }
                        lastJobCount = 0;
                    }
                    
                    // Esperar antes del siguiente ciclo
                    Thread.sleep(2000);
                    
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
