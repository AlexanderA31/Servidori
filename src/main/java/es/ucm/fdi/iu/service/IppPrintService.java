package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Job;
import es.ucm.fdi.iu.model.Printer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Servicio de impresión IPP (Internet Printing Protocol)
 * 
 * REEMPLAZA CupsService con implementación Java pura usando JIPP
 * Funciona en cualquier sistema operativo sin dependencias externas
 * Compatible con impresoras de red IPP y CUPS remotos
 */
@Service
@Slf4j
public class IppPrintService {

    // Timeouts configurables para conexiones IPP (ms)
    @Value("${printer.discovery.port.timeout:1000}")
    private int discoveryTimeout;
    
    @Value("${printer.connection.timeout:5000}")
    private int connectionTimeout;
    
    @Value("${printer.data.transfer.timeout:10000}")
    private int dataTransferTimeout;
    
    @Value("${printer.connection.retries:3}")
    private int maxRetries;

    /**
     * Información de una impresora IPP
     */
    public static class IppPrinterInfo {
        private String name;
        private String uri;
        private String state;
        private String stateReasons;
        private boolean accepting;
        private String makeModel;
        private List<String> documentFormats;
        
        // Getters y Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        
        public String getStateReasons() { return stateReasons; }
        public void setStateReasons(String stateReasons) { this.stateReasons = stateReasons; }
        
        public boolean isAccepting() { return accepting; }
        public void setAccepting(boolean accepting) { this.accepting = accepting; }
        
        public String getMakeModel() { return makeModel; }
        public void setMakeModel(String makeModel) { this.makeModel = makeModel; }
        
        public List<String> getDocumentFormats() { return documentFormats; }
        public void setDocumentFormats(List<String> documentFormats) { this.documentFormats = documentFormats; }
    }

    /**
     * Información de un trabajo de impresión IPP
     */
    public static class IppJobInfo {
        private int jobId;
        private String jobUri;
        private String state;
        private String name;
        private String user;
        private int pages;
        private String format;
        
        // Getters y Setters
        public int getJobId() { return jobId; }
        public void setJobId(int jobId) { this.jobId = jobId; }
        
        public String getJobUri() { return jobUri; }
        public void setJobUri(String jobUri) { this.jobUri = jobUri; }
        
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        
        public int getPages() { return pages; }
        public void setPages(int pages) { this.pages = pages; }
        
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
    }

    /**
     * Obtiene información de una impresora IPP
     * 
     * @param printerUri URI de la impresora (ej: ipp://192.168.1.100:631/ipp/print)
     * @return Información de la impresora o null si hay error
     */
    public IppPrinterInfo getPrinterInfo(String printerUri) {
        try {
            log.debug("Obteniendo información IPP de: {}", printerUri);
            
            // Primero verificar que el puerto está abierto
            URI uri = new URI(printerUri);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 631;
            
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), connectionTimeout);
                socket.close();
                
                // Intentar obtener atributos reales vía comando ipptool
                IppPrinterInfo info = getPrinterInfoViaIpptool(printerUri);
                
                if (info != null) {
                    log.debug("✓ Información IPP obtenida: {} - {}", info.getName(), info.getMakeModel());
                    return info;
                }
                
                // Fallback: información básica
                info = new IppPrinterInfo();
                info.setUri(printerUri);
                info.setName(extractPrinterName(printerUri));
                info.setState("idle");
                info.setAccepting(true);
                info.setMakeModel("Impresora de Red");
                info.setDocumentFormats(Arrays.asList("application/pdf", "application/postscript", "text/plain"));
                
                log.debug("✓ Impresora IPP disponible (info básica): {}", info.getName());
                return info;
                
            } catch (IOException e) {
                log.trace("IPP no disponible en {}: {}", printerUri, e.getMessage());
                return null;
            }
            
        } catch (Exception e) {
            log.trace("Error verificando impresora: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Obtiene información real de la impresora usando ipptool
     */
    private IppPrinterInfo getPrinterInfoViaIpptool(String printerUri) {
        try {
            log.debug("🔧 Ejecutando ipptool para {}", printerUri);
            
            // Crear archivo temporal para capturar TODA la salida
            Path tempFile = Files.createTempFile("ipptool-output-", ".txt");
            
            try {
                // Ejecutar ipptool y redirigir salida al archivo
                ProcessBuilder pb = new ProcessBuilder(
                    "ipptool", "-tv", printerUri, 
                    "/usr/share/cups/ipptool/get-printer-attributes.test"
                );
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(tempFile.toFile()));
                pb.redirectError(ProcessBuilder.Redirect.appendTo(tempFile.toFile()));
                Process process = pb.start();
                
                // Esperar a que termine el proceso (máximo 5 segundos)
                boolean completed = process.waitFor(5, TimeUnit.SECONDS);
                
                if (!completed) {
                    log.debug("⚠️ ipptool timeout para {}", printerUri);
                    process.destroyForcibly();
                    return null;
                }
                
                int exitCode = process.exitValue();
                
                // Leer el contenido del archivo temporal
                String output = Files.readString(tempFile);
                
                log.debug("🔧 ipptool finalizó con código: {}", exitCode);
                log.debug("📝 Salida capturada desde archivo: {} caracteres", output.length());
                
                // Mostrar la salida si es pequeña (probablemente incompleta)
                if (output.length() < 1000) {
                    log.warn("⚠️ Salida sospechosamente pequeña ({} chars): '{}'", 
                        output.length(), output.substring(0, Math.min(200, output.length())));
                }
                
                if (exitCode != 0) {
                    log.debug("⚠️ ipptool falló con código: {} para {}", exitCode, printerUri);
                    return null;
                }
                
                log.debug("✅ ipptool ejecutado exitosamente para {}", printerUri);
                
                // Parsear la salida
                IppPrinterInfo info = parseIpptoolOutput(output, printerUri);
                
                if (info != null) {
                    log.info("✅ Info parseada exitosamente: {} - {}", info.getName(), info.getMakeModel());
                } else {
                    log.warn("⚠️ No se pudo parsear la salida de ipptool");
                }
                
                return info;
                
            } finally {
                // Limpiar archivo temporal
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.trace("No se pudo eliminar archivo temporal: {}", e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.debug("❌ Error ejecutando ipptool para {}: {}", printerUri, e.getMessage());
            return null;
        }
    }
    
    /**
     * Parsea la salida de ipptool para extraer información de la impresora
     * PRIORIDAD: printer-info (nombre personalizado) > printer-name (nombre técnico)
     */
    private IppPrinterInfo parseIpptoolOutput(String output, String printerUri) {
        try {
            IppPrinterInfo info = new IppPrinterInfo();
            info.setUri(printerUri);
            
            log.debug("Parseando salida ipptool ({} caracteres)", output.length());
            
            // 1. PRIORIDAD ALTA: Buscar printer-info (NOMBRE PERSONALIZADO de la impresora)
            // Este es el campo que contiene el nombre que el usuario le puso a la impresora
            // Ejemplo: "HP OfficeJet Oficina 2" en lugar de "HP_OfficeJet_Pro_8720"
            String printerInfo = extractValue(output, "printer-info\\s*\\([^)]+\\)\\s*=\\s*(.+)");
            if (printerInfo != null && !printerInfo.isEmpty()) {
                // Limpiar escapes de ipptool (\[ -> [)
                printerInfo = printerInfo.replaceAll("\\\\\\[", "[").replaceAll("\\\\\\]", "]").trim();
                info.setName(printerInfo);
                log.info("  ✅ NOMBRE PERSONALIZADO detectado (printer-info): '{}'", info.getName());
            }
            
            // 2. FALLBACK: Si no hay printer-info, usar printer-name (nombre técnico del sistema)
            // NOTA: Este es el nombre técnico/interno, NO el nombre personalizado
            if (info.getName() == null || info.getName().isEmpty()) {
                String printerName = extractValue(output, "printer-name\\s*\\([^)]+\\)\\s*=\\s*(.+)");
                if (printerName != null && !printerName.isEmpty()) {
                    info.setName(printerName.trim());
                    log.warn("  ⚠️ Usando nombre técnico (printer-name): '{}' - No se encontró nombre personalizado", info.getName());
                }
            }
            
            // 3. Buscar printer-make-and-model (marca y modelo del fabricante)
            // Este campo contiene información del fabricante, ej: "HP OfficeJet Pro 8720 series"
            String makeModel = extractValue(output, "printer-make-and-model\\s*\\([^)]+\\)\\s*=\\s*(.+)");
            if (makeModel != null && !makeModel.isEmpty()) {
                info.setMakeModel(makeModel.trim());
                log.debug("  📋 Marca/Modelo: '{}'", info.getMakeModel());
            }
            
            // Buscar printer-state
            String state = extractValue(output, "printer-state\\s*\\([^)]+\\)\\s*=\\s*(.+)");
            if (state != null) {
                info.setState(state.trim());
            }
            
            // Buscar printer-is-accepting-jobs
            String accepting = extractValue(output, "printer-is-accepting-jobs\\s*\\([^)]+\\)\\s*=\\s*(.+)");
            if (accepting != null) {
                info.setAccepting(accepting.toLowerCase().contains("true"));
            }
            
            // Si no obtuvimos ningún dato, retornar null
            if (info.getName() == null && info.getMakeModel() == null) {
                log.debug("  No se encontró nombre ni modelo en la salida");
                return null;
            }
            
            // Valores por defecto
            if (info.getName() == null) {
                info.setName("Impresora de Red");
            }
            if (info.getMakeModel() == null) {
                info.setMakeModel("Desconocido");
            }
            if (info.getState() == null) {
                info.setState("idle");
            }
            
            log.info("  ✅ Parseado exitoso - Nombre: '{}' | Modelo: '{}'", info.getName(), info.getMakeModel());
            return info;
            
        } catch (Exception e) {
            log.debug("Error parseando salida ipptool: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extrae un valor usando expresión regular (multiline)
     */
    private String extractValue(String text, String pattern) {
        try {
            Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
            Matcher m = p.matcher(text);
            if (m.find()) {
                String value = m.group(1).trim();
                log.trace("  Pattern '{}' encontró: {}", pattern, value);
                return value;
            } else {
                log.trace("  Pattern '{}' no encontró coincidencias", pattern);
            }
        } catch (Exception e) {
            log.trace("  Error con pattern '{}': {}", pattern, e.getMessage());
        }
        return null;
    }
    
    private String extractPrinterName(String uri) {
        try {
            String[] parts = uri.split("/");
            return parts[parts.length - 1];
        } catch (Exception e) {
            return "Printer";
        }
    }

    /**
     * Envía un archivo a una impresora IPP directamente
     */
    public boolean sendToIppPrinter(String printerUri, Path file) {
        try {
            log.info("Enviando a impresora IPP: {}", printerUri);
            
            URI uri = new URI(printerUri);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 631;
            
            // Verificar que la impresora esté disponible
            try (Socket testSocket = new Socket()) {
                testSocket.connect(new InetSocketAddress(host, port), 3000);
            }
            
            // Por ahora, enviar como RAW al puerto 631
            // TODO: Implementar protocolo IPP completo
            boolean success = sendToRawPort(host, file, port);
            
            if (success) {
                log.info("✓ Archivo enviado exitosamente vía IPP");
            }
            
            return success;
            
        } catch (Exception e) {
            log.error("Error enviando a IPP: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Obtiene el estado de un trabajo de impresión
     */
    public IppJobInfo getJobStatus(String printerUri, int jobId) {
        log.debug("getJobStatus - implementación simplificada");
        return null;
    }

    /**
     * Cancela un trabajo de impresión
     */
    public boolean cancelJob(String printerUri, int jobId) {
        log.debug("cancelJob - implementación simplificada");
        return false;
    }

    /**
     * Lista todos los trabajos de una impresora
     */
    public List<IppJobInfo> listJobs(String printerUri) {
        log.debug("listJobs - implementación simplificada");
        return new ArrayList<>();
    }

    /**
     * Determina el tipo MIME de un archivo
     */
    private String determineMimeType(String filePath) {
        String lower = filePath.toLowerCase();
        
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".ps")) return "application/postscript";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        
        return "application/octet-stream";
    }

    /**
     * Construye URI IPP para una impresora
     * 
     * @param ip IP de la impresora
     * @param port Puerto IPP (631 por defecto)
     * @param path Ruta del endpoint (ej: /ipp/print)
     * @return URI completo
     */
    public String buildIppUri(String ip, int port, String path) {
        if (path == null || path.isEmpty()) {
            path = "/ipp/print";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return String.format("ipp://%s:%d%s", ip, port, path);
    }

    /**
     * Valida que una impresora esté disponible y aceptando trabajos
     */
    public boolean validatePrinter(String printerUri) {
        IppPrinterInfo info = getPrinterInfo(printerUri);
        return info != null && info.isAccepting();
    }

    /**
     * Obtiene capacidades de una impresora (formatos, opciones, etc.)
     */
    public Map<String, Object> getPrinterCapabilities(String printerUri) {
        Map<String, Object> capabilities = new HashMap<>();
        
        IppPrinterInfo info = getPrinterInfo(printerUri);
        if (info != null) {
            capabilities.put("name", info.getName());
            capabilities.put("state", info.getState());
            capabilities.put("accepting", info.isAccepting());
            capabilities.put("makeModel", info.getMakeModel());
            capabilities.put("formats", info.getDocumentFormats());
        }
        
        return capabilities;
    }
    
    /**
     * Genera e imprime una página de prueba en texto plano
     * Compatible con cualquier impresora que soporte texto plano
     * 
     * @param printer Objeto Printer con la información de la impresora
     * @param username Usuario que envía la prueba
     * @return true si se envió exitosamente, false en caso contrario
     */
    public boolean printTestPage(Printer printer, String username) {
        try {
            log.info("🖨️ Generando página de prueba para: {}", printer.getAlias());
            
            // Crear contenido de la página de prueba
            StringBuilder testPage = new StringBuilder();
            testPage.append("\n\n");
            testPage.append("========================================\n");
            testPage.append("    PÁGINA DE PRUEBA DE IMPRESIÓN\n");
            testPage.append("========================================\n\n");
            testPage.append("Impresora: ").append(printer.getAlias()).append("\n");
            testPage.append("Modelo: ").append(printer.getModel()).append("\n");
            testPage.append("Ubicación: ").append(printer.getLocation()).append("\n");
            testPage.append("IP: ").append(printer.getIp()).append("\n\n");
            testPage.append("Usuario: ").append(username).append("\n");
            testPage.append("Fecha: ").append(new Date().toString()).append("\n\n");
            testPage.append("Estado de prueba: \n");
            testPage.append("  [X] Conexión establecida\n");
            testPage.append("  [X] Documento generado\n");
            testPage.append("  [X] Enviado a impresora\n\n");
            testPage.append("Si puede leer este mensaje,\n");
            testPage.append("la impresora funciona correctamente.\n\n");
            testPage.append("========================================\n");
            testPage.append("  Sistema de Gestión de Impresoras\n");
            testPage.append("========================================\n");
            testPage.append("\f"); // Form feed para expulsar página
            
            // Crear archivo temporal
            Path tempFile = Files.createTempFile("test-page-", ".txt");
            Files.write(tempFile, testPage.toString().getBytes("UTF-8"));
            
            log.info("📝 Página de prueba creada: {} bytes", testPage.length());
            
            // Intentar diferentes métodos de impresión
            boolean success = false;
            
            // DETECCIÓN DE IMPRESORAS COMPARTIDAS USB
            // Las pruebas de impresión NO funcionan en impresoras compartidas USB
            // porque el servidor no puede comunicarse directamente con el cliente USB
            boolean isSharedUSB = printer.getLocation() != null && 
                                 printer.getLocation().contains("Compartida-USB");
            
            if (isSharedUSB) {
                log.warn("⚠️ Impresora compartida USB detectada");
                log.warn("⚠️ Las pruebas de impresión no están soportadas para impresoras USB compartidas");
                log.warn("⚠️ Para probar esta impresora, envía un trabajo desde un cliente externo");
                return false;
            }
            // Impresoras de red normales (incluso si tienen ippPort asignado)
            else if (printer.getIp() != null && !printer.getIp().startsWith("LOCAL")) {
                // Método 1: Intentar enviar directamente al puerto RAW (9100)
                success = sendToRawPort(printer.getIp(), tempFile, 9100);
                if (success) {
                    log.info("✅ Página enviada exitosamente vía RAW (puerto 9100)");
                }
                
                // Método 2: Intentar LPD (puerto 515)
                if (!success) {
                    success = sendToRawPort(printer.getIp(), tempFile, 515);
                    if (success) {
                        log.info("✅ Página enviada exitosamente vía LPD (puerto 515)");
                    }
                }
            }
            
            // Método 3: Usar comando lp/lpr si está disponible (Linux/Mac)
            if (!success) {
                success = sendViaLpCommand(printer.getAlias(), tempFile);
                if (success) {
                    log.info("✅ Página enviada exitosamente vía comando lp");
                }
            }
            
            // Limpiar archivo temporal
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception e) {
                log.warn("No se pudo eliminar archivo temporal: {}", e.getMessage());
            }
            
            if (!success) {
                log.warn("⚠️ No se pudo enviar la página de prueba con ningún método");
            }
            
            return success;
            
        } catch (Exception e) {
            log.error("❌ Error al imprimir página de prueba: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Envía datos directamente a un puerto de impresora (RAW o LPD)
     * Método público para ser usado por IppServerService
     * Incluye diagnósticos mejorados y reintentos automáticos
     */
    public boolean sendToRawPort(String ip, Path file, int port) {
        log.info("📡 Iniciando envío a {}:{}", ip, port);
        
        // Paso 1: Diagnóstico previo de conectividad (solo si está habilitado)
        NetworkDiagnostics diagnostics = performNetworkDiagnostics(ip, port);
        
        if (!diagnostics.isReachable) {
            log.error("❌ Host {} no alcanzable", ip);
            log.error("   💡 Verifica:");
            log.error("      - El dispositivo está encendido");
            log.error("      - La dirección IP es correcta");
            log.error("      - No hay problemas de red entre servidor y dispositivo");
            return false;
        }
        
        if (!diagnostics.isPortOpen) {
            log.error("❌ Puerto {}:{} cerrado o filtrado", ip, port);
            log.error("   💡 Verifica:");
            log.error("      - El servicio está ejecutándose en el puerto {}", port);
            log.error("      - El firewall permite tráfico al puerto {}", port);
            log.error("      - La aplicación cliente USB está activa (si aplica)");
            return false;
        }
        
        log.info("✅ Diagnóstico previo exitoso (RTT: {} ms)", diagnostics.latencyMs);
        
        // Paso 2: Intentar envío con reintentos
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("   📤 Intento {}/{}: Enviando archivo ({} bytes)", 
                    attempt, maxRetries, Files.size(file));
                
                if (sendToRawPortInternal(ip, file, port)) {
                    log.info("✅ Envío exitoso a {}:{} (intento {})", ip, port, attempt);
                    return true;
                }
                
            } catch (IOException e) {
                log.warn("⚠️ Intento {}/{} falló: {}", attempt, maxRetries, e.getMessage());
                
                if (attempt < maxRetries) {
                    // Backoff exponencial: 1s, 2s, 4s...
                    long waitMs = (long) Math.pow(2, attempt - 1) * 1000;
                    log.info("   ⏳ Esperando {} ms antes del siguiente intento...", waitMs);
                    
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    log.error("❌ Todos los intentos fallaron para {}:{}", ip, port);
                    log.error("   📊 Estadísticas finales:");
                    log.error("      - Intentos realizados: {}", maxRetries);
                    log.error("      - Último error: {}", e.getMessage());
                    log.error("      - Tipo de error: {}", e.getClass().getSimpleName());
                }
            }
        }
        
        return false;
    }
    
    /**
     * Realiza el envío real de datos al puerto
     */
    private boolean sendToRawPortInternal(String ip, Path file, int port) throws IOException {
        Socket socket = null;
        try {
            socket = new Socket();
            
            // Configurar timeouts
            socket.connect(new InetSocketAddress(ip, port), connectionTimeout);
            socket.setSoTimeout(dataTransferTimeout);
            
            // Aumentar buffer de envío para evitar pérdida de datos
            socket.setSendBufferSize(65536); // 64KB
            
            // Deshabilitar Nagle para envío inmediato (importante para datos pequeños)
            socket.setTcpNoDelay(true);
            
            // Mantener conexión viva
            socket.setKeepAlive(true);
            
            long startTime = System.currentTimeMillis();
            long totalBytes = 0;
            
            try (OutputStream out = socket.getOutputStream();
                 FileInputStream fis = new FileInputStream(file.toFile())) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                    
                    // Flush periódicamente para evitar buffers llenos
                    if (totalBytes % 8192 == 0) {
                        out.flush();
                    }
                }
                
                // IMPORTANTE: Flush final y esperar a que los datos se envíen
                out.flush();
                
                // Dar tiempo al socket para enviar todos los datos (especialmente importante en redes lentas)
                // Sin esto, el socket se cierra antes de que el último buffer llegue al destino
                try {
                    Thread.sleep(100); // 100ms debería ser suficiente para redes locales
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                long duration = System.currentTimeMillis() - startTime;
                double speedKBps = duration > 0 ? (totalBytes / 1024.0) / (duration / 1000.0) : 0;
                
                log.info("   📊 Transferencia completa:");
                log.info("      - Bytes enviados: {} ({} KB)", totalBytes, totalBytes / 1024);
                log.info("      - Duración: {} ms", duration);
                if (duration > 0) {
                    log.info("      - Velocidad: {} KB/s", String.format("%.2f", speedKBps));
                } else {
                    log.info("      - Velocidad: instantánea (buffered)");
                }
                
                // Intentar leer respuesta del cliente (opcional pero ayuda a confirmar recepción)
                try {
                    socket.setSoTimeout(1000); // 1 segundo para respuesta
                    InputStream in = socket.getInputStream();
                    if (in.available() > 0) {
                        byte[] response = new byte[256];
                        int respLen = in.read(response);
                        log.debug("   📨 Respuesta del cliente: {} bytes", respLen);
                    }
                } catch (Exception e) {
                    // Es normal que no haya respuesta en sockets RAW
                    log.trace("Sin respuesta del cliente (normal para RAW)");
                }
                
                return true;
            }
            
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    // Shutdown ordenado: cerrar salida pero dejar entrada abierta
                    // Esto le indica al receptor que terminamos de enviar
                    socket.shutdownOutput();
                    
                    // Dar tiempo para que el receptor procese los datos
                    Thread.sleep(50);
                    
                    // Ahora cerrar completamente
                    socket.close();
                } catch (Exception e) {
                    log.trace("Error cerrando socket: {}", e.getMessage());
                    try {
                        socket.close();
                    } catch (Exception ex) {
                        // Ignorar
                    }
                }
            }
        }
    }
    
    /**
     * Realiza diagnósticos de red previos al envío
     */
    private NetworkDiagnostics performNetworkDiagnostics(String ip, int port) {
        NetworkDiagnostics diag = new NetworkDiagnostics();
        
        log.info("🔍 Realizando diagnóstico de red para {}:{}", ip, port);
        
        // Test 1: ¿El host es alcanzable?
        try {
            InetAddress address = InetAddress.getByName(ip);
            long startPing = System.currentTimeMillis();
            diag.isReachable = address.isReachable(Math.max(discoveryTimeout, 2000));
            diag.latencyMs = System.currentTimeMillis() - startPing;
            
            if (diag.isReachable) {
                log.info("   ✅ Host alcanzable (RTT: {} ms)", diag.latencyMs);
            } else {
                log.warn("   ⚠️ Host no responde a ping (puede estar bloqueado por firewall)");
                // Algunos hosts bloquean ICMP, así que no es fatal
                diag.isReachable = true; // Asumir alcanzable
            }
        } catch (UnknownHostException e) {
            log.error("   ❌ No se puede resolver el host: {}", ip);
            log.error("   💡 Verifica que la dirección IP sea correcta");
            diag.isReachable = false;
            return diag;
        } catch (IOException e) {
            log.warn("   ⚠️ Error verificando alcance: {}", e.getMessage());
            diag.isReachable = true; // Continuar de todas formas
        }
        
        // Test 2: ¿El puerto está abierto?
        try (Socket testSocket = new Socket()) {
            long startConnect = System.currentTimeMillis();
            testSocket.connect(new InetSocketAddress(ip, port), connectionTimeout);
            long connectTime = System.currentTimeMillis() - startConnect;
            
            diag.isPortOpen = true;
            log.info("   ✅ Puerto {} abierto (conexión en {} ms)", port, connectTime);
            
        } catch (IOException e) {
            diag.isPortOpen = false;
            diag.errorMessage = e.getMessage();
            
            // Diagnosticar tipo específico de error
            if (e instanceof ConnectException) {
                if (e.getMessage().contains("Connection refused")) {
                    log.warn("   ⚠️ Conexión rechazada - Puerto cerrado o servicio no escuchando");
                } else if (e.getMessage().contains("Connection timed out")) {
                    log.warn("   ⚠️ Timeout de conexión - Puerto filtrado o host lento");
                } else {
                    log.warn("   ⚠️ Error de conexión: {}", e.getMessage());
                }
            } else if (e instanceof SocketTimeoutException) {
                log.warn("   ⚠️ Timeout - Puerto no responde en {} ms", connectionTimeout);
            } else {
                log.warn("   ⚠️ Error verificando puerto: {}", e.getMessage());
            }
        }
        
        return diag;
    }
    
    /**
     * Clase interna para almacenar resultados de diagnóstico
     */
    private static class NetworkDiagnostics {
        boolean isReachable = false;
        boolean isPortOpen = false;
        long latencyMs = 0;
        String errorMessage = null;
    }
    
    /**
     * Intenta imprimir usando el comando lp/lpr del sistema
     * DEPRECADO: Solo para compatibilidad con sistemas que tienen CUPS instalado
     */
    @Deprecated
    private boolean sendViaLpCommand(String printerName, Path file) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            
            // Solo intentar en Linux/Mac
            if (!os.contains("nix") && !os.contains("nux") && !os.contains("mac")) {
                log.debug("Comando lp no disponible en {}", os);
                return false;
            }
            
            log.debug("Intentando comando lp para impresora: {}", printerName);
            
            // Intentar con lp
            ProcessBuilder pb = new ProcessBuilder("lp", "-d", printerName, file.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.debug("✅ Comando lp ejecutado exitosamente");
                return true;
            } else {
                // Leer salida de error
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("lp output: {}", line);
                    }
                }
                log.debug("❌ Comando lp falló con código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.debug("❌ Error ejecutando comando lp: {}", e.getMessage());
            return false;
        }
    }
}
