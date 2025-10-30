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
import java.util.Optional;

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
    private final PrintQueueService printQueueService;
    private final PrintDocumentConverter documentConverter;
    
    private static final int BASE_PORT = 8631;
    
    public MultiPortIppServerService(IppPrintService ippPrintService, 
                                      PrinterRepository printerRepository,
                                      PrintQueueService printQueueService,
                                      PrintDocumentConverter documentConverter) {
        this.ippPrintService = ippPrintService;
        this.printerRepository = printerRepository;
        this.printQueueService = printQueueService;
        this.documentConverter = documentConverter;
    }

    @PostConstruct
    public void startServer() {
        executorService = Executors.newCachedThreadPool();
        running = true;
        
        // Asignar puertos a impresoras que no tengan uno asignado
        assignIppPortsIfNeeded();
        
        // Obtener todas las impresoras ordenadas por ID
        List<Printer> printers = printerRepository.findAllByOrderByIdAsc();
        
        if (printers.isEmpty()) {
            log.warn("No hay impresoras registradas. El servidor IPP multi-puerto no se iniciará.");
            return;
        }
        
        log.info("════════════════════════════════════════════════════════════");
        log.info("✓ Iniciando servidor IPP Multi-Puerto");
        log.info("════════════════════════════════════════════════════════════");
        
        for (Printer printer : printers) {
            int port = printer.getIppPort() != null ? printer.getIppPort() : BASE_PORT;
            
            try {
                ServerSocket socket = new ServerSocket(port);
                serverSockets.put(printer.getId(), socket);
                printerByPort.put(printer.getId(), printer);
                
                // Iniciar listener para esta impresora
                executorService.submit(() -> acceptConnections(printer, socket, port));
                
                log.info("  ✓ {} → Puerto {} (FIJO)", printer.getAlias(), port);
                
            } catch (IOException e) {
                log.error("  ✗ Error iniciando puerto {} para {}: {}", 
                    port, printer.getAlias(), e.getMessage());
            }
        }
        
        log.info("════════════════════════════════════════════════════════════");
        log.info("Total: {} impresoras con puertos fijos dedicados", serverSockets.size());
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
            
            // Detectar tipo de archivo por los primeros bytes (magic numbers)
            String fileType = detectFileType(data);
            log.info("  📋 Tipo detectado: {}", fileType);
            
            // Mostrar primeros bytes para diagnóstico
            if (data.length > 0) {
                byte[] preview = Arrays.copyOf(data, Math.min(50, data.length));
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                
                for (int i = 0; i < preview.length; i++) {
                    hex.append(String.format("%02X ", preview[i]));
                    char c = (char)(preview[i] & 0xFF);
                    ascii.append(c >= 32 && c < 127 ? c : '.');
                    if ((i + 1) % 16 == 0) {
                        hex.append("\n      ");
                        ascii.append("\n      ");
                    }
                }
                
                log.info("  🔍 Primeros bytes (hex):\n      {}", hex.toString());
                log.info("  🔍 Primeros bytes (ascii):\n      {}", ascii.toString());
            }
            
            log.info("  🖨️  Impresora destino: {}", printer.getAlias());
            
            // Determinar nombre de archivo y usuario
            String fileName = extractFileName(data, clientSocket);
            String ownerName = extractOwner(data, clientSocket);
            
            log.info("  👤 Usuario: {}", ownerName);
            log.info("  📄 Archivo: {}", fileName);
            
            // Verificar que la impresora todavía existe en la base de datos
            Optional<Printer> currentPrinter = printerRepository.findById(printer.getId());
            if (!currentPrinter.isPresent()) {
                log.error("  ❌ Impresora ID {} ya no existe en la base de datos", printer.getId());
                log.error("  ℹ️  Esta impresora fue eliminada pero su puerto {} sigue escuchando", port);
                log.error("  ℹ️  Se requiere reiniciar el servicio para liberar el puerto");
                out.write(new byte[]{0x01, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03});
                out.flush();
                return;
            }
            
            boolean success = false;
            
            // DETECCIÓN DE IMPRESORA USB COMPARTIDA
            // Si es una impresora USB compartida, SIEMPRE reenviar al cliente USB directamente
            boolean isSharedUSB = currentPrinter.get().getLocation() != null && 
                                 currentPrinter.get().getLocation().contains("Compartida-USB");
            
            if (isSharedUSB) {
                // REENVIAR DIRECTAMENTE AL CLIENTE USB (sin cola)
                log.info("  🖨️  Impresora USB compartida detectada");
                log.info("  📤 Reenviando directo a cliente USB: {}:631", currentPrinter.get().getIp());
                
                // Guardar datos en archivo temporal
                try {
                    File tempFile = File.createTempFile("ipp-usb-", ".dat");
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        fos.write(data);
                    }
                    
                    // Enviar directamente al cliente USB en puerto 631 (IPP estándar)
                    success = ippPrintService.sendToRawPort(
                        currentPrinter.get().getIp(), 
                        tempFile.toPath(), 
                        631
                    );
                    
                    // Limpiar archivo temporal
                    tempFile.delete();
                    
                    if (success) {
                        log.info("  ✅ Trabajo reenviado exitosamente a cliente USB");
                        log.info("  ℹ️  El cliente USB procesará e imprimirá el documento");
                    } else {
                        log.error("  ❌ No se pudo conectar al cliente USB");
                        log.error("  ℹ️  Verifica que:");
                        log.error("      - La PC con la impresora esté encendida ({})", currentPrinter.get().getIp());
                        log.error("      - El cliente USB esté ejecutándose");
                        log.error("      - El puerto 631 no esté bloqueado por firewall");
                    }
                } catch (Exception e) {
                    log.error("  ❌ Error reenviando a cliente USB: {}", e.getMessage());
                }
            } else {
                // IMPRESORA DE RED NORMAL - usar cola de impresión
                log.info("  🌐 Impresora de red - registrando en cola");
                
                // IMPORTANTE: Procesar documento antes de guardar en cola
                log.info("  🔄 Procesando documento para impresión...");
                byte[] processedData = documentConverter.processForPrinting(data, currentPrinter.get().getModel());
                
                if (processedData.length != data.length) {
                    log.info("  ✅ Documento convertido: {} bytes → {} bytes", data.length, processedData.length);
                }
                
                try {
                    printQueueService.addJob(currentPrinter.get(), fileName, ownerName, 
                                            currentPrinter.get().getInstance(), processedData);
                    log.info("  ✅ Trabajo registrado en cola de impresión");
                    success = true;
                } catch (Exception e) {
                    log.error("  ❌ Error registrando trabajo en cola: {}", e.getMessage());
                }
            }
            
            // Responder al cliente
            if (success) {
                log.info("  ✅ Trabajo aceptado en cola");
                // Respuesta IPP: success
                out.write(new byte[]{0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03});
            } else {
                log.error("  ❌ Error aceptando trabajo");
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
     * Asigna puertos IPP a impresoras que no tengan uno asignado
     */
    private void assignIppPortsIfNeeded() {
        List<Printer> printersWithoutPort = printerRepository.findByIppPortIsNull();
        
        if (printersWithoutPort.isEmpty()) {
            return;
        }
        
        log.info("Asignando puertos IPP a {} impresoras...", printersWithoutPort.size());
        
        // Obtener el puerto máximo actual
        Integer maxPort = printerRepository.findMaxIppPort();
        int nextPort = (maxPort != null) ? maxPort + 1 : BASE_PORT;
        
        for (Printer printer : printersWithoutPort) {
            printer.setIppPort(nextPort);
            printerRepository.save(printer);
            log.info("  ✓ {} → Puerto {} asignado", printer.getAlias(), nextPort);
            nextPort++;
        }
    }
    
    /**
     * Obtiene el puerto asignado a una impresora
     */
    public int getPortForPrinter(Printer printer) {
        if (printer == null) {
            return BASE_PORT;
        }
        
        // Usar el puerto fijo almacenado en la BD
        return printer.getIppPort() != null ? printer.getIppPort() : BASE_PORT;
    }

    /**
     * Obtiene el puerto asignado a una impresora por ID
     */
    public int getPortForPrinterId(long printerId) {
        Optional<Printer> printer = printerRepository.findById(printerId);
        return printer.map(p -> p.getIppPort() != null ? p.getIppPort() : BASE_PORT)
                      .orElse(BASE_PORT);
    }
    
    /**
     * Cierra el puerto de una impresora eliminada
     */
    public void closePrinterPort(long printerId) {
        ServerSocket socket = serverSockets.remove(printerId);
        if (socket != null) {
            try {
                socket.close();
                printerByPort.remove(printerId);
                log.info("✅ Puerto cerrado para impresora ID {}", printerId);
            } catch (IOException e) {
                log.error("Error cerrando puerto para impresora {}", printerId, e);
            }
        }
    }
    
    /**
     * Detecta el tipo de archivo por magic numbers
     */
    private String detectFileType(byte[] data) {
        if (data == null || data.length < 4) {
            return "UNKNOWN (muy pequeño)";
        }
        
        // PDF: %PDF
        if (data[0] == 0x25 && data[1] == 0x50 && data[2] == 0x44 && data[3] == 0x46) {
            return "PDF";
        }
        
        // PostScript: %!
        if (data[0] == 0x25 && data[1] == 0x21) {
            return "PostScript";
        }
        
        // PCL: ESC E (reset) o ESC &
        if (data[0] == 0x1B && (data[1] == 0x45 || data[1] == 0x26)) {
            return "PCL";
        }
        
        // PNG: 89 50 4E 47
        if (data[0] == (byte)0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "PNG";
        }
        
        // JPEG: FF D8 FF
        if (data[0] == (byte)0xFF && data[1] == (byte)0xD8 && data[2] == (byte)0xFF) {
            return "JPEG";
        }
        
        // IPP request (versión 1.x o 2.x)
        if (data.length >= 8 && data[0] >= 0x01 && data[0] <= 0x02 && data[1] >= 0x00 && data[1] <= 0x09) {
            return "IPP Protocol Request";
        }
        
        // Texto plano (todos caracteres imprimibles)
        boolean isText = true;
        int printable = 0;
        for (int i = 0; i < Math.min(100, data.length); i++) {
            byte b = data[i];
            if ((b >= 32 && b < 127) || b == 9 || b == 10 || b == 13) {
                printable++;
            } else if (b < 0) {
                // Byte fuera de rango ASCII (posible UTF-8)
                printable++;
            }
        }
        
        if (printable > Math.min(90, data.length * 0.9)) {
            return "Texto plano (posible UTF-8)";
        }
        
        return "UNKNOWN/BINARIO";
    }
    
    /**
     * Extrae el nombre del archivo desde los datos IPP
     */
    private String extractFileName(byte[] data, Socket clientSocket) {
        try {
            String dataStr = new String(data, "UTF-8");
            
            // Buscar "job-name" en los datos IPP
            int jobNameIndex = dataStr.indexOf("job-name");
            if (jobNameIndex > 0) {
                int start = jobNameIndex + 8;
                int end = Math.min(start + 100, dataStr.length());
                String substr = dataStr.substring(start, end);
                
                // Buscar primer string imprimible
                StringBuilder fileName = new StringBuilder();
                boolean collecting = false;
                for (char c : substr.toCharArray()) {
                    if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ' ') {
                        fileName.append(c);
                        collecting = true;
                    } else if (collecting) {
                        break;
                    }
                }
                
                if (fileName.length() > 0) {
                    return fileName.toString().trim();
                }
            }
        } catch (Exception e) {
            log.trace("No se pudo extraer nombre de archivo: {}", e.getMessage());
        }
        
        // Nombre por defecto
        return "Documento_" + System.currentTimeMillis() + ".dat";
    }
    
    /**
     * Extrae el nombre del usuario desde los datos IPP
     */
    private String extractOwner(byte[] data, Socket clientSocket) {
        try {
            String dataStr = new String(data, "UTF-8");
            
            // Buscar "requesting-user-name" o "job-originating-user-name"
            String[] patterns = {"requesting-user-name", "job-originating-user-name", "originating-user-name"};
            
            for (String pattern : patterns) {
                int index = dataStr.indexOf(pattern);
                if (index > 0) {
                    int start = index + pattern.length();
                    int end = Math.min(start + 100, dataStr.length());
                    String substr = dataStr.substring(start, end);
                    
                    // Buscar primer string imprimible
                    StringBuilder userName = new StringBuilder();
                    boolean collecting = false;
                    for (char c : substr.toCharArray()) {
                        if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == '@') {
                            userName.append(c);
                            collecting = true;
                        } else if (collecting && userName.length() > 2) {
                            break;
                        }
                    }
                    
                    if (userName.length() > 2) {
                        return userName.toString().trim();
                    }
                }
            }
        } catch (Exception e) {
            log.trace("No se pudo extraer nombre de usuario: {}", e.getMessage());
        }
        
        // Usuario por defecto (usar IP del cliente)
        try {
            return clientSocket.getInetAddress().getHostName();
        } catch (Exception e) {
            return "Usuario_Desconocido";
        }
    }
}
