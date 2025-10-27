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
 * Servidor IPP Embebido
 * 
 * Expone TODAS las impresoras registradas a través del servidor
 * Los clientes Windows/Linux pueden conectarse a:
 * 
 * ipp://<servidor-ip>:631/printers/<nombre-impresora>
 * 
 * Y la aplicación redirigirá el trabajo a la impresora real
 */
@Service
@Slf4j
public class IppServerService {

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    private final IppPrintService ippPrintService;
    private final PrinterRepository printerRepository;
    
    private static final int IPP_PORT = 8631; // Puerto alternativo para evitar conflicto con CUPS
    
    public IppServerService(IppPrintService ippPrintService, PrinterRepository printerRepository) {
        this.ippPrintService = ippPrintService;
        this.printerRepository = printerRepository;
    }

    @PostConstruct
    public void startServer() {
        try {
            serverSocket = new ServerSocket(IPP_PORT);
            executorService = Executors.newCachedThreadPool();
            running = true;
            
            // Iniciar servidor en thread separado
            executorService.submit(this::acceptConnections);
            
            log.info("════════════════════════════════════════════════════════════");
            log.info("✓ Servidor IPP iniciado en puerto {}", IPP_PORT);
            log.info("  Los clientes pueden conectarse a: ipp://<servidor-ip>:{}}/printers/<nombre>", IPP_PORT);
            log.info("  NOTA: Usando puerto {} para evitar conflicto con CUPS", IPP_PORT);
            log.info("════════════════════════════════════════════════════════════");
            
        } catch (IOException e) {
            log.error("Error al iniciar servidor IPP en puerto {}: {}", IPP_PORT, e.getMessage());
            log.error("Stack trace: ", e);
        }
    }

    @PreDestroy
    public void stopServer() {
        running = false;
        
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.error("Error al cerrar servidor IPP", e);
            }
        }
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        
        log.info("Servidor IPP detenido");
    }

    private void acceptConnections() {
        log.info("Esperando conexiones IPP...");
        
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                log.debug("Nueva conexión IPP desde: {}", clientSocket.getInetAddress());
                
                executorService.submit(() -> handleClient(clientSocket));
                
            } catch (IOException e) {
                if (running) {
                    log.error("Error aceptando conexión IPP: {}", e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("Nueva solicitud IPP desde: {}", clientSocket.getInetAddress());
        
        try (InputStream in = clientSocket.getInputStream();
             OutputStream out = clientSocket.getOutputStream()) {
            
            // Leer request IPP
            log.debug("Parseando request IPP...");
            IppRequest request = parseIppRequest(in);
            
            if (request != null) {
                log.info("✓ IPP Request recibido:");
                log.info("  Operación: {}", request.operation);
                log.info("  URI: {}", request.printerUri);
                log.info("  Datos: {} bytes", request.documentData != null ? request.documentData.length : 0);
                
                // Procesar según operación
                IppResponse response = processRequest(request);
                
                log.info("  Respuesta: {}", response.statusCode);
                
                // Enviar respuesta
                sendResponse(out, response);
                log.info("✓ Respuesta enviada");
            } else {
                log.warn("⚠ Request IPP nulo");
            }
            
        } catch (Exception e) {
            log.error("❌ Error procesando cliente IPP: {}", e.getMessage(), e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // Ignorar
            }
        }
        log.info("═══════════════════════════════════════════════════════════");
    }

    private IppResponse processRequest(IppRequest request) {
        switch (request.operation) {
            case "Probe":
                // Responder a probes de conexión
                IppResponse probeResp = new IppResponse();
                probeResp.statusCode = "successful-ok";
                return probeResp;
            case "Get-Printer-Attributes":
                return getPrinterAttributes(request);
            case "Print-Job":
                return printJob(request);
            case "Get-Jobs":
                return getJobs(request);
            case "Cancel-Job":
                return cancelJob(request);
            case "CUPS-Get-Printers":
            case "Get-Printers":
                return getAllPrinters();
            default:
                return createErrorResponse("operation-not-supported");
        }
    }

    private IppResponse getPrinterAttributes(IppRequest request) {
        // Extraer nombre de impresora del URI
        String printerName = extractPrinterName(request.printerUri);
        
        Optional<Printer> printerOpt = printerRepository.findByAlias(printerName);
        
        if (printerOpt.isEmpty()) {
            return createErrorResponse("client-error-not-found");
        }
        
        Printer printer = printerOpt.get();
        
        IppResponse response = new IppResponse();
        response.statusCode = "successful-ok";
        response.attributes = new HashMap<>();
        response.attributes.put("printer-name", printer.getAlias());
        response.attributes.put("printer-uri-supported", buildPrinterUri(printer));
        response.attributes.put("printer-state", "3"); // idle
        response.attributes.put("printer-state-reasons", "none");
        response.attributes.put("printer-is-accepting-jobs", "true");
        response.attributes.put("printer-make-and-model", printer.getModel());
        response.attributes.put("printer-location", printer.getLocation());
        
        return response;
    }

    private IppResponse printJob(IppRequest request) {
        String printerName = extractPrinterName(request.printerUri);
        
        Optional<Printer> printerOpt = findPrinterByName(printerName);
        
        if (printerOpt.isEmpty()) {
            log.error("  ❌ No se encontró impresora para: '{}'", printerName);
            log.error("  🔍 Impresoras disponibles:");
            printerRepository.findAll().forEach(p -> 
                log.error("     - '{}' (alias)", p.getAlias()));
            return createErrorResponse("client-error-not-found");
        }
        
        Printer printer = printerOpt.get();
        
        try {
            // Guardar documento temporalmente
            File tempFile = File.createTempFile("print_", ".dat");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(request.documentData);
            }
            
            // Enviar a la impresora real según su protocolo
            String protocol = printer.getProtocol() != null ? printer.getProtocol() : "RAW";
            boolean success = false;
            
            switch (protocol.toUpperCase()) {
                case "RAW":
                    success = ippPrintService.sendToRawPort(
                        printer.getIp(), 
                        tempFile.toPath(), 
                        printer.getPort() != null ? printer.getPort() : 9100
                    );
                    break;
                case "IPP":
                    String ippUri = ippPrintService.buildIppUri(printer.getIp(), 631, "/ipp/print");
                    success = ippPrintService.sendToIppPrinter(ippUri, tempFile.toPath());
                    break;
                case "LPD":
                    success = ippPrintService.sendToRawPort(printer.getIp(), tempFile.toPath(), 515);
                    break;
                default:
                    // Intentar múltiples métodos
                    success = ippPrintService.sendToRawPort(printer.getIp(), tempFile.toPath(), 9100);
            }
            
            int jobId = success ? (int)(System.currentTimeMillis() % 10000) : -1;
            
            // Limpiar
            tempFile.delete();
            
            if (jobId > 0) {
                IppResponse response = new IppResponse();
                response.statusCode = "successful-ok";
                response.attributes = new HashMap<>();
                response.attributes.put("job-id", String.valueOf(jobId));
                response.attributes.put("job-uri", request.printerUri + "/" + jobId);
                
                log.info("✓ Trabajo {} enviado a {}", jobId, printer.getAlias());
                return response;
            }
            
        } catch (Exception e) {
            log.error("Error procesando Print-Job: {}", e.getMessage());
        }
        
        return createErrorResponse("server-error-internal-error");
    }

    private IppResponse getAllPrinters() {
        List<Printer> printers = printerRepository.findAll();
        
        IppResponse response = new IppResponse();
        response.statusCode = "successful-ok";
        response.printerList = new ArrayList<>();
        
        for (Printer printer : printers) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("printer-name", printer.getAlias());
            attrs.put("printer-uri-supported", buildPrinterUri(printer));
            attrs.put("printer-state", "3");
            attrs.put("printer-make-and-model", printer.getModel());
            attrs.put("printer-location", printer.getLocation());
            
            response.printerList.add(attrs);
        }
        
        log.info("Listando {} impresoras disponibles", printers.size());
        return response;
    }

    private IppResponse getJobs(IppRequest request) {
        // Implementación simplificada
        IppResponse response = new IppResponse();
        response.statusCode = "successful-ok";
        response.attributes = new HashMap<>();
        return response;
    }

    private IppResponse cancelJob(IppRequest request) {
        // Implementación simplificada
        IppResponse response = new IppResponse();
        response.statusCode = "successful-ok";
        return response;
    }

    private IppResponse createErrorResponse(String statusCode) {
        IppResponse response = new IppResponse();
        response.statusCode = statusCode;
        response.attributes = new HashMap<>();
        return response;
    }

    private String extractPrinterName(String uri) {
        // ipp://servidor:631/printers/HP_LaserJet → HP LaserJet
        // ipp://servidor:631/printers/MP_501 → MP 501
        // ipp://servidor:631/printers/EPSON264A53 → EPSON264A53
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        
        try {
            URI u = new URI(uri);
            String path = u.getPath();
            if (path == null || path.isEmpty()) {
                return null;
            }
            String[] parts = path.split("/");
            if (parts.length > 0) {
                String name = parts[parts.length - 1];
                // Convertir guiones bajos de vuelta a espacios
                return name.replace("_", " ");
            }
            return null;
        } catch (Exception e) {
            log.debug("  Error extrayendo nombre de impresora de URI: {}", uri);
            return null;
        }
    }

    private String buildPrinterUri(Printer printer) {
        try {
            String serverIp = es.ucm.fdi.iu.util.NetworkUtils.getServerIpAddress();
            return String.format("ipp://%s:%d/printers/%s", 
                serverIp, IPP_PORT, printer.getAlias().replace(" ", "_"));
        } catch (Exception e) {
            log.error("Error obteniendo IP del servidor", e);
            return String.format("ipp://localhost:%d/printers/%s", IPP_PORT, printer.getAlias());
        }
    }

    /**
     * Construye URI de la impresora real según protocolo configurado
     */
    private String buildRealPrinterUri(Printer printer) {
        // Si tiene deviceUri configurado manualmente, usarlo
        if (printer.getDeviceUri() != null && !printer.getDeviceUri().isEmpty()) {
            return printer.getDeviceUri();
        }
        
        // Construir URI según protocolo
        String ip = printer.getIp();
        int port = printer.getPort() != null ? printer.getPort() : 9100;
        String protocol = printer.getProtocol() != null ? printer.getProtocol() : "RAW";
        
        return switch (protocol.toUpperCase()) {
            case "IPP" -> String.format("ipp://%s:631/ipp/print", ip);
            case "RAW" -> String.format("socket://%s:%d", ip, port);
            case "LPD" -> String.format("lpd://%s/queue", ip);
            case "SMB" -> printer.getDeviceUri(); // SMB requiere URI completo
            default -> String.format("socket://%s:9100", ip); // RAW por defecto
        };
    }

    // Clases auxiliares para parsing IPP (simplificadas)
    
    private IppRequest parseIppRequest(InputStream in) throws IOException {
        IppRequest request = new IppRequest();
        
        try {
            // Leer los primeros bytes para analizar el contenido
            BufferedInputStream bufferedIn = new BufferedInputStream(in);
            bufferedIn.mark(1024);
            
            byte[] header = new byte[100];
            int headerRead = bufferedIn.read(header);
            
            if (headerRead <= 0) {
                log.debug("  Conexión vacía (probe)");
                return null;
            }
            
            if (headerRead < 8) {
                log.debug("  Request corto: {} bytes - posiblemente probe de conexión", headerRead);
                // Es un probe de Windows, responder OK
                return createProbeResponse();
            }
            
            log.debug("  Primeros bytes: {}", bytesToHex(header, Math.min(headerRead, 32)));
            
            // Resetear para leer desde el inicio
            bufferedIn.reset();
            
            // Leer version IPP (2 bytes)
            int versionMajor = bufferedIn.read();
            int versionMinor = bufferedIn.read();
            log.debug("  Versión IPP: {}.{}", versionMajor, versionMinor);
            
            // Verificar si es una request IPP válida (versiones 1.0, 1.1, 2.0, etc)
            if (versionMajor < 1 || versionMajor > 3 || versionMinor < 0 || versionMinor > 9) {
                log.warn("  ⚠ No es un request IPP válido (versión: {}.{})", versionMajor, versionMinor);
                log.warn("  Posiblemente sea un trabajo RAW enviado directamente al puerto IPP");
                
                // Intentar procesar como trabajo RAW
                return handleRawPrintJob(bufferedIn, header, headerRead);
            }
            
            // Leer operation-id (2 bytes)
            int operationHi = bufferedIn.read();
            int operationLo = bufferedIn.read();
            int operationId = (operationHi << 8) | operationLo;
            request.operation = getOperationName(operationId);
            log.debug("  Operation ID: 0x{} ({})", Integer.toHexString(operationId), request.operation);
            
            // Leer request-id (4 bytes)
            byte[] requestIdBytes = new byte[4];
            bufferedIn.read(requestIdBytes);
            int requestId = ((requestIdBytes[0] & 0xFF) << 24) |
                           ((requestIdBytes[1] & 0xFF) << 16) |
                           ((requestIdBytes[2] & 0xFF) << 8) |
                           (requestIdBytes[3] & 0xFF);
            log.debug("  Request ID: {}", requestId);
            
            // Leer atributos IPP
            parseIppAttributes(bufferedIn, request);
            
            // Si hay datos de documento (para Print-Job), leerlos
            if ("Print-Job".equals(request.operation)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalBytes = 0;
                
                while ((bytesRead = bufferedIn.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                
                request.documentData = baos.toByteArray();
                log.debug("  Datos del documento: {} bytes", totalBytes);
            }
            
            return request;
            
        } catch (Exception e) {
            log.error("Error parseando request IPP: {}", e.getMessage(), e);
            return null;
        }
    }
    
    private void parseIppAttributes(InputStream in, IppRequest request) throws IOException {
        while (true) {
            int tag = in.read();
            
            if (tag == -1) {
                break; // Fin del stream
            }
            
            if (tag == 0x03) {
                // End-of-attributes tag
                log.debug("  End-of-attributes encontrado");
                break;
            }
            
            // Saltar el tag y leer el nombre del atributo
            int nameLength = (in.read() << 8) | in.read();
            if (nameLength > 0) {
                byte[] nameBytes = new byte[nameLength];
                in.read(nameBytes);
                String name = new String(nameBytes, "UTF-8");
                
                // Leer valor del atributo
                int valueLength = (in.read() << 8) | in.read();
                byte[] valueBytes = new byte[valueLength];
                in.read(valueBytes);
                String value = new String(valueBytes, "UTF-8");
                
                log.debug("    Atributo: {} = {}", name, value);
                
                // Guardar atributos importantes
                if ("printer-uri".equals(name) || "job-printer-uri".equals(name)) {
                    request.printerUri = value;
                } else if ("requesting-user-name".equals(name)) {
                    request.requestingUserName = value;
                } else if ("job-name".equals(name)) {
                    request.jobName = value;
                }
                
                request.options.put(name, value);
            }
        }
    }

    private void sendResponse(OutputStream out, IppResponse response) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // Version IPP (2 bytes)
        baos.write(1); // Major version 1
        baos.write(1); // Minor version 1 (IPP/1.1)
        
        // Status code (2 bytes)
        int statusCode = getStatusCode(response.statusCode);
        baos.write((statusCode >> 8) & 0xFF);
        baos.write(statusCode & 0xFF);
        
        // Request ID (4 bytes)
        baos.write(new byte[]{0, 0, 0, 1});
        
        // Operation attributes tag
        baos.write(0x01);
        
        // attributes-charset
        writeAttribute(baos, 0x47, "attributes-charset", "utf-8");
        
        // attributes-natural-language
        writeAttribute(baos, 0x48, "attributes-natural-language", "en-us");
        
        // Agregar atributos adicionales si existen
        if (response.attributes != null && !response.attributes.isEmpty()) {
            // Job attributes tag
            baos.write(0x02);
            
            for (Map.Entry<String, String> attr : response.attributes.entrySet()) {
                writeAttribute(baos, 0x44, attr.getKey(), attr.getValue());
            }
        }
        
        // End of attributes tag
        baos.write(0x03);
        
        out.write(baos.toByteArray());
        out.flush();
        
        log.debug("  Respuesta IPP enviada: {} bytes", baos.size());
    }
    
    /**
     * Escribe un atributo IPP en el stream
     */
    private void writeAttribute(ByteArrayOutputStream baos, int valueTag, String name, String value) throws IOException {
        // Value tag
        baos.write(valueTag);
        
        // Name length (2 bytes)
        byte[] nameBytes = name.getBytes("UTF-8");
        baos.write((nameBytes.length >> 8) & 0xFF);
        baos.write(nameBytes.length & 0xFF);
        
        // Name
        baos.write(nameBytes);
        
        // Value length (2 bytes)
        byte[] valueBytes = value.getBytes("UTF-8");
        baos.write((valueBytes.length >> 8) & 0xFF);
        baos.write(valueBytes.length & 0xFF);
        
        // Value
        baos.write(valueBytes);
    }

    private String getOperationName(int operationId) {
        return switch (operationId) {
            case 0x0002 -> "Print-Job";
            case 0x000B -> "Get-Printer-Attributes";
            case 0x000A -> "Get-Jobs";
            case 0x0008 -> "Cancel-Job";
            case 0x4002 -> "CUPS-Get-Printers";
            default -> "Unknown";
        };
    }

    private int getStatusCode(String status) {
        return switch (status) {
            case "successful-ok" -> 0x0000;
            case "client-error-not-found" -> 0x0406;
            case "server-error-internal-error" -> 0x0500;
            case "operation-not-supported" -> 0x0501;
            default -> 0x0500;
        };
    }

    // Clases internas
    
    private static class IppRequest {
        String operation;
        String printerUri;
        String requestingUserName;
        String jobName;
        byte[] documentData;
        Map<String, Object> options = new HashMap<>();
    }

    private static class IppResponse {
        String statusCode;
        Map<String, String> attributes;
        List<Map<String, String>> printerList;
    }
    
    /**
     * Crea una respuesta para probes de conexión
     */
    private IppRequest createProbeResponse() {
        IppRequest request = new IppRequest();
        request.operation = "Probe";
        return request;
    }
    
    /**
     * Maneja trabajos de impresión RAW enviados directamente al puerto IPP
     * (cuando el cliente envía datos PCL/PostScript sin protocolo IPP)
     */
    private IppRequest handleRawPrintJob(InputStream in, byte[] initialBytes, int initialLength) {
        log.info("  📄 Procesando como trabajo RAW (no IPP)");
        
        try {
            // Leer todos los datos incluyendo los bytes iniciales
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(initialBytes, 0, initialLength);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            
            byte[] allData = baos.toByteArray();
            log.info("  📦 Datos RAW recibidos: {} bytes", allData.length);
            
            // Crear un request IPP sintético para Print-Job
            IppRequest request = new IppRequest();
            request.operation = "Print-Job";
            request.documentData = allData;
            request.jobName = "RAW Print Job";
            
            // Intentar determinar la impresora de destino
            // Si solo hay una impresora, usarla por defecto
            List<Printer> printers = printerRepository.findAll();
            if (!printers.isEmpty()) {
                Printer defaultPrinter = printers.get(0);
                request.printerUri = buildPrinterUri(defaultPrinter);
                log.info("  🖨️  Usando impresora por defecto: {}", defaultPrinter.getAlias());
            }
            
            return request;
            
        } catch (Exception e) {
            log.error("  ❌ Error procesando trabajo RAW: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Busca una impresora por nombre con múltiples variaciones
     */
    private Optional<Printer> findPrinterByName(String name) {
        if (name == null || name.isEmpty()) {
            // Si no hay nombre, usar la primera disponible
            List<Printer> printers = printerRepository.findAll();
            Optional<Printer> opt = printers.isEmpty() ? Optional.empty() : Optional.of(printers.get(0));
            opt.ifPresent(p -> log.info("  ℹ️  Sin nombre de impresora, usando: {}", p.getAlias()));
            return opt;
        }
        
        log.debug("  🔍 Buscando impresora: '{}'", name);
        
        // 1. Buscar exacto
        Optional<Printer> printer = printerRepository.findByAlias(name);
        if (printer.isPresent()) {
            log.debug("  ✓ Encontrada (exacto): {}", name);
            return printer;
        }
        
        // 2. Buscar sin guiones bajos (MP_501 -> MP 501)
        String nameWithSpaces = name.replace("_", " ");
        printer = printerRepository.findByAlias(nameWithSpaces);
        if (printer.isPresent()) {
            log.debug("  ✓ Encontrada (con espacios): {}", nameWithSpaces);
            return printer;
        }
        
        // 3. Buscar con guiones bajos (MP 501 -> MP_501)
        String nameWithUnderscores = name.replace(" ", "_");
        printer = printerRepository.findByAlias(nameWithUnderscores);
        if (printer.isPresent()) {
            log.debug("  ✓ Encontrada (con guiones bajos): {}", nameWithUnderscores);
            return printer;
        }
        
        // 4. Búsqueda insensitiva a mayúsculas
        List<Printer> allPrinters = printerRepository.findAll();
        for (Printer p : allPrinters) {
            if (p.getAlias().equalsIgnoreCase(name) || 
                p.getAlias().equalsIgnoreCase(nameWithSpaces) ||
                p.getAlias().equalsIgnoreCase(nameWithUnderscores)) {
                log.debug("  ✓ Encontrada (case-insensitive): {}", p.getAlias());
                return Optional.of(p);
            }
        }
        
        log.warn("  ⚠ No se encontró impresora con nombre: '{}'", name);
        return Optional.empty();
    }
    
    // Método auxiliar para debugging
    private String bytesToHex(byte[] bytes, int length) {
        if (bytes == null || length <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(length, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
            if ((i + 1) % 16 == 0) sb.append("\n  ");
        }
        return sb.toString();
    }

}
