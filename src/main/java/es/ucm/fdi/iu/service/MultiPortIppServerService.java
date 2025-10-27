package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.repository.PrinterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Servidor IPP Multi-Puerto
 * 
 * Cada impresora tiene su propio puerto dedicado:
 * - Primera impresora (ID=1): Puerto 8631
 * - Segunda impresora (ID=2): Puerto 8632
 * - Tercera impresora (ID=3): Puerto 8633
 * etc.
 * 
 * Esto permite que Windows identifique correctamente cada impresora
 * sin necesidad de protocolo IPP completo.
 */
@Service
@Slf4j
public class MultiPortIppServerService {

    private final Map<Long, ServerSocket> serverSockets = new ConcurrentHashMap<>();
    private final Map<Long, Printer> printerByPort = new ConcurrentHashMap<>();
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    private final IppPrintService ippPrintService;
    private final PrinterRepository printerRepository;
    
    private static final int BASE_PORT = 8631;
    
    public MultiPortIppServerService(IppPrintService ippPrintService, PrinterRepository printerRepository) {
        this.ippPrintService = ippPrintService;
        this.printerRepository = printerRepository;
    }

    @PostConstruct
    public void startServer() {
        executorService = Executors.newCachedThreadPool();
        running = true;
        
        // Obtener todas las impresoras y asignarles un puerto
        List<Printer> printers = printerRepository.findAll();
        
        if (printers.isEmpty()) {
            log.warn("No hay impresoras registradas. El servidor IPP multi-puerto no se iniciará.");
            return;
        }
        
        log.info("════════════════════════════════════════════════════════════");
        log.info("✓ Iniciando servidor IPP Multi-Puerto");
        log.info("════════════════════════════════════════════════════════════");
        
        for (int i = 0; i < printers.size(); i++) {
            Printer printer = printers.get(i);
            int port = BASE_PORT + i;
            
            try {
                ServerSocket socket = new ServerSocket(port);
                serverSockets.put(printer.getId(), socket);
                printerByPort.put(printer.getId(), printer);
                
                // Iniciar listener para esta impresora
                executorService.submit(() -> acceptConnections(printer, socket, port));
                
                log.info("  ✓ {} → Puerto {}", printer.getAlias(), port);
                
            } catch (IOException e) {
                log.error("  ✗ Error iniciando puerto {} para {}: {}", 
                    port, printer.getAlias(), e.getMessage());
            }
        }
        
        log.info("════════════════════════════════════════════════════════════");
        log.info("Total: {} impresoras con puertos dedicados", serverSockets.size());
        log.info("════════════════════════════════════════════════════════════");
    }

    @PreDestroy
    public void stopServer() {
        running = false;
        
        // Cerrar todos los server sockets
        serverSockets.values().forEach(socket -> {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                log.error("Error cerrando socket", e);
            }
        });
        
        serverSockets.clear();
        printerByPort.clear();
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        
        log.info("Servidor IPP Multi-Puerto detenido");
    }

    private void acceptConnections(Printer printer, ServerSocket socket, int port) {
        log.debug("Esperando conexiones en puerto {} para {}", port, printer.getAlias());
        
        while (running && !socket.isClosed()) {
            try {
                Socket clientSocket = socket.accept();
                log.debug("Conexión en puerto {} desde {}", port, clientSocket.getInetAddress());
                
                executorService.submit(() -> handleClient(clientSocket, printer, port));
                
            } catch (IOException e) {
                if (running) {
                    log.error("Error aceptando conexión en puerto {}: {}", port, e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket clientSocket, Printer printer, int port) {
        log.info("══════════════════════════════════════════════════════════");
        log.info("📥 Conexión desde: {} → Puerto {} ({})", 
            clientSocket.getInetAddress(), port, printer.getAlias());
        
        try (InputStream in = clientSocket.getInputStream();
             OutputStream out = clientSocket.getOutputStream()) {
            
            // Leer TODOS los datos enviados
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            int totalBytes = 0;
            
            while ((bytesRead = in.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
            
            byte[] data = baos.toByteArray();
            
            if (totalBytes == 0) {
                log.debug("  Conexión vacía (probe)");
                // Responder OK para probes
                out.write(new byte[]{0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03});
                out.flush();
                return;
            }
            
            log.info("  📦 Datos recibidos: {} bytes", totalBytes);
            log.info("  🖨️  Impresora destino: {}", printer.getAlias());
            
            // Guardar datos temporalmente
            File tempFile = File.createTempFile("print_", ".dat");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(data);
            }
            
            // Enviar a la impresora real
            boolean success = sendToPrinter(printer, tempFile);
            
            // Limpiar
            tempFile.delete();
            
            // Responder al cliente
            if (success) {
                log.info("  ✅ Trabajo enviado correctamente");
                // Respuesta IPP: success
                out.write(new byte[]{0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03});
            } else {
                log.error("  ❌ Error enviando trabajo");
                // Respuesta IPP: server-error
                out.write(new byte[]{0x01, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03});
            }
            out.flush();
            
        } catch (Exception e) {
            log.error("❌ Error procesando cliente: {}", e.getMessage(), e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // Ignorar
            }
        }
        log.info("══════════════════════════════════════════════════════════");
    }

    private boolean sendToPrinter(Printer printer, File dataFile) {
        String protocol = printer.getProtocol() != null ? printer.getProtocol() : "RAW";
        int printPort = determinePrintPort(printer);
        
        log.info("  📡 Enviando a: {}:{} (protocolo: {})", 
            printer.getIp(), printPort, protocol);
        
        try {
            switch (protocol.toUpperCase()) {
                case "RAW":
                    return ippPrintService.sendToRawPort(
                        printer.getIp(), 
                        dataFile.toPath(), 
                        printPort
                    );
                    
                case "IPP":
                    String ippUri = ippPrintService.buildIppUri(printer.getIp(), 631, "/ipp/print");
                    return ippPrintService.sendToIppPrinter(ippUri, dataFile.toPath());
                    
                case "LPD":
                    return ippPrintService.sendToRawPort(printer.getIp(), dataFile.toPath(), 515);
                    
                default:
                    return ippPrintService.sendToRawPort(printer.getIp(), dataFile.toPath(), printPort);
            }
        } catch (Exception e) {
            log.error("  Error enviando a impresora: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Determina el puerto de impresión correcto
     */
    private int determinePrintPort(Printer printer) {
        Integer configuredPort = printer.getPort();
        
        if (configuredPort == null) {
            return 9100;
        }
        
        // Si es puerto SNMP, usar 9100
        if (configuredPort == 161 || configuredPort == 162) {
            log.debug("  Puerto SNMP detectado, usando 9100");
            return 9100;
        }
        
        // Si es puerto IPP, usar 9100 para RAW
        if (configuredPort == 631) {
            return 9100;
        }
        
        return configuredPort;
    }

    /**
     * Obtiene el puerto asignado a una impresora
     */
    public int getPortForPrinter(Printer printer) {
        List<Printer> all = printerRepository.findAll();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(printer.getId())) {
                return BASE_PORT + i;
            }
        }
        return BASE_PORT;
    }

    /**
     * Obtiene el puerto asignado a una impresora por ID
     */
    public int getPortForPrinterId(Long printerId) {
        List<Printer> all = printerRepository.findAll();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(printerId)) {
                return BASE_PORT + i;
            }
        }
        return BASE_PORT;
    }
}
