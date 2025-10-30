package es.ucm.fdi.iu.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;

/**
 * Servicio para cliente Windows que comparte impresora USB
 * 
 * Este servicio se ejecuta SOLO cuando el perfil "usb-client" está activo.
 * Funcionalidad:
 * - Escucha en puerto 631 (IPP estándar)
 * - Recibe trabajos de impresión del servidor central
 * - Los envía a la impresora USB local usando comandos nativos de Windows
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.mode", havingValue = "usb-client")
public class UsbClientService {

    @Value("${server.port:631}")
    private int serverPort;
    
    @Value("${app.server.ip:10.1.16.31}")
    private String centralServerIp;
    
    @Value("${app.server.port:8080}")
    private int centralServerPort;
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;
    private String localPrinterName;
    private String computerName;
    private String localIp;

    @PostConstruct
    public void init() {
        log.info("════════════════════════════════════════════════════════════");
        log.info("🖨️  MODO: Cliente USB - Compartir Impresora Local");
        log.info("════════════════════════════════════════════════════════════");
        
        try {
            // Obtener información del sistema
            computerName = InetAddress.getLocalHost().getHostName();
            localIp = detectBestLocalIp();
            
            log.info("📍 Computadora: {}", computerName);
            log.info("📍 IP Local: {}", localIp);
            log.info("📍 Servidor Central: {}:{}", centralServerIp, centralServerPort);
            log.info("");
            
            // Detectar impresoras USB locales
            detectLocalPrinters();
            
            if (localPrinterName == null) {
                log.error("❌ No se encontró ninguna impresora USB local");
                log.error("   Conecta una impresora USB y reinicia la aplicación");
                return;
            }
            
            log.info("🖨️  Impresora detectada: {}", localPrinterName);
            log.info("");
            
            // Registrar impresora en el servidor central
            registerWithCentralServer();
            
            // Iniciar servidor IPP
            startIppServer();
            
            log.info("════════════════════════════════════════════════════════════");
            log.info("✅ Cliente USB iniciado correctamente");
            log.info("════════════════════════════════════════════════════════════");
            log.info("   Puerto de escucha: {}", serverPort);
            log.info("   Impresora compartida: {}", localPrinterName);
            log.info("   Acceso: ipp://{}:{}", localIp, serverPort);
            log.info("════════════════════════════════════════════════════════════");
            
        } catch (Exception e) {
            log.error("❌ Error al inicializar cliente USB", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 Deteniendo cliente USB...");
        running = false;
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.error("Error cerrando socket", e);
        }
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        
        log.info("✅ Cliente USB detenido");
    }

    /**
     * Detecta impresoras USB locales usando PowerShell
     */
    private void detectLocalPrinters() {
        try {
            log.info("🔍 Buscando impresoras USB locales...");
            
            // Ejecutar comando PowerShell para listar impresoras USB
            String command = "powershell.exe -Command \"Get-Printer | Where-Object {$_.Type -eq 'Local' -or $_.PortName -like 'USB*'} | Select-Object -First 1 -ExpandProperty Name\"";
            
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            String printerName = reader.readLine();
            if (printerName != null && !printerName.trim().isEmpty()) {
                localPrinterName = printerName.trim();
                log.info("   ✅ Encontrada: {}", localPrinterName);
            } else {
                log.warn("   ⚠️ No se encontraron impresoras USB");
            }
            
            process.waitFor();
            
        } catch (Exception e) {
            log.error("Error detectando impresoras locales", e);
        }
    }

    /**
     * Registra esta impresora en el servidor central
     */
    private void registerWithCentralServer() {
        try {
            log.info("📤 Registrando impresora en servidor central...");
            
            // Obtener información del driver
            String driverName = getDriverName();
            
            // Crear JSON para registro
            String json = String.format(
                "{\"alias\":\"%s_%s\",\"model\":\"%s\",\"ip\":\"%s\",\"location\":\"Compartida-USB - %s - %s\",\"protocol\":\"IPP\",\"port\":631}",
                localPrinterName, computerName, driverName, localIp, computerName, System.getProperty("user.name")
            );
            
            // Enviar al servidor
            URL url = new URL(String.format("http://%s:%d/api/register-shared-printer", centralServerIp, centralServerPort));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes("UTF-8"));
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 201) {
                log.info("   ✅ Impresora registrada exitosamente");
                
                // Leer respuesta
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String response = in.lines().reduce("", String::concat);
                log.debug("   Respuesta: {}", response);
            } else {
                log.error("   ❌ Error al registrar: HTTP {}", responseCode);
            }
            
        } catch (Exception e) {
            log.error("Error registrando impresora en servidor central", e);
        }
    }

    /**
     * Obtiene el nombre del driver de la impresora
     */
    private String getDriverName() {
        try {
            String command = String.format("powershell.exe -Command \"Get-Printer -Name '%s' | Select-Object -ExpandProperty DriverName\"", localPrinterName);
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String driver = reader.readLine();
            process.waitFor();
            return driver != null ? driver.trim() : "Unknown Driver";
        } catch (Exception e) {
            return "Unknown Driver";
        }
    }

    /**
     * Inicia el servidor IPP para recibir trabajos
     */
    private void startIppServer() {
        executorService = Executors.newCachedThreadPool();
        running = true;
        
        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(serverPort);
                log.info("🌐 Servidor IPP escuchando en puerto {}...", serverPort);
                
                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        log.info("📥 Conexión desde: {}", clientSocket.getInetAddress().getHostAddress());
                        
                        // Procesar en thread separado
                        executorService.submit(() -> handlePrintJob(clientSocket));
                        
                    } catch (SocketException e) {
                        if (running) {
                            log.error("Error en socket", e);
                        }
                    }
                }
                
            } catch (IOException e) {
                log.error("Error iniciando servidor IPP", e);
            }
        });
        
        serverThread.setName("IPP-Server-Thread");
        serverThread.setDaemon(false);
        serverThread.start();
    }

    /**
     * Procesa un trabajo de impresión
     */
    private void handlePrintJob(Socket clientSocket) {
        log.info("════════════════════════════════════════════════════════════");
        log.info("🖨️  Procesando trabajo de impresión");
        
        try (InputStream in = clientSocket.getInputStream();
             OutputStream out = clientSocket.getOutputStream()) {
            
            // Leer todos los datos
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
                log.debug("   Conexión vacía (probe)");
                // Responder OK para probes
                out.write(new byte[]{0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03});
                out.flush();
                return;
            }
            
            log.info("   📦 Recibidos: {} bytes", totalBytes);
            
            // Guardar en archivo temporal
            Path tempFile = Files.createTempFile("print-job-", ".dat");
            Files.write(tempFile, data);
            log.info("   💾 Guardado en: {}", tempFile);
            
            // Enviar a impresora local
            boolean success = printToLocalPrinter(tempFile);
            
            // Responder al servidor
            if (success) {
                log.info("   ✅ Trabajo enviado a impresora: {}", localPrinterName);
                // Respuesta IPP: success
                out.write(new byte[]{0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03});
            } else {
                log.error("   ❌ Error al imprimir");
                // Respuesta IPP: server-error
                out.write(new byte[]{0x01, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00, 0x01, 0x03});
            }
            out.flush();
            
            // Limpiar archivo temporal después de un momento
            executorService.submit(() -> {
                try {
                    Thread.sleep(5000);
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    // Ignorar
                }
            });
            
        } catch (Exception e) {
            log.error("   ❌ Error procesando trabajo", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // Ignorar
            }
        }
        
        log.info("════════════════════════════════════════════════════════════");
    }

    /**
     * Envía un archivo a la impresora local usando comandos nativos de Windows
     */
    private boolean printToLocalPrinter(Path file) {
        try {
            // Opción 1: Usar comando print de Windows
            String command = String.format("print /D:\"%s\" \"%s\"", localPrinterName, file.toAbsolutePath());
            
            log.debug("   Ejecutando: {}", command);
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                return true;
            }
            
            // Opción 2: Copiar directamente al share de la impresora
            log.debug("   Intento 1 falló, probando método alternativo...");
            String printerShare = String.format("\\\\localhost\\%s", localPrinterName);
            
            // Crear comando copy
            String copyCommand = String.format("copy /B \"%s\" \"%s\"", file.toAbsolutePath(), printerShare);
            process = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", copyCommand});
            exitCode = process.waitFor();
            
            return exitCode == 0;
            
        } catch (Exception e) {
            log.error("Error al enviar a impresora local", e);
            return false;
        }
    }
    
    /**
     * Detecta la mejor IP local para comunicación con el servidor
     * Prioriza redes corporativas (10.x.x.x) sobre redes privadas locales
     */
    private String detectBestLocalIp() {
        try {
            String fallbackIp = InetAddress.getLocalHost().getHostAddress();
            
            log.debug("🔍 Detectando mejor IP local...");
            
            // Obtener todas las interfaces de red
            var interfaces = NetworkInterface.getNetworkInterfaces();
            
            String bestIp = null;
            int bestPriority = 0;
            
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                
                // Ignorar interfaces inactivas, loopback y virtuales
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                
                var addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    
                    // Solo IPv4
                    if (addr instanceof java.net.Inet6Address) {
                        continue;
                    }
                    
                    String ip = addr.getHostAddress();
                    
                    // Ignorar loopback y link-local
                    if (ip.startsWith("127.") || ip.startsWith("169.254.")) {
                        continue;
                    }
                    
                    int priority = calculateIpPriority(ip);
                    
                    log.debug("   Interface: {} - IP: {} (prioridad: {})", 
                        ni.getDisplayName(), ip, priority);
                    
                    if (priority > bestPriority) {
                        bestPriority = priority;
                        bestIp = ip;
                    }
                }
            }
            
            if (bestIp != null) {
                log.info("   ✅ IP seleccionada: {} (prioridad: {})", bestIp, bestPriority);
                return bestIp;
            }
            
            log.warn("   ⚠️  No se encontró IP óptima, usando: {}", fallbackIp);
            return fallbackIp;
            
        } catch (Exception e) {
            log.error("Error detectando IP local", e);
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException ex) {
                return "127.0.0.1";
            }
        }
    }
    
    /**
     * Calcula prioridad de una IP para selección
     * Mayor prioridad = mejor IP para comunicación con servidor
     */
    private int calculateIpPriority(String ip) {
        // Prioridad 1000: Red corporativa 10.x.x.x
        if (ip.startsWith("10.")) {
            return 1000;
        }
        
        // Prioridad 900: Otras redes privadas clase A (172.16-31.x.x)
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                int second = Integer.parseInt(parts[1]);
                if (second >= 16 && second <= 31) {
                    return 900;
                }
            }
        }
        
        // Prioridad 800: Red privada 192.168.x.x (excepto 192.168.56.x)
        if (ip.startsWith("192.168.")) {
            // Penalizar VirtualBox Host-Only (192.168.56.x)
            if (ip.startsWith("192.168.56.")) {
                return 100; // Baja prioridad
            }
            return 800;
        }
        
        // Prioridad 500: IP pública (fallback)
        return 500;
    }
}
