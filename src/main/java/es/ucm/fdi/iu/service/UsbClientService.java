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
            
            // Configurar impresora para evitar diálogos de FAX
            configureDriverToDisableFax();
            
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
     * FILTRA impresoras de red (puerto IP_*) y busca SOLO puerto USB
     */
    private void detectLocalPrinters() {
        try {
            log.info("🔍 Buscando impresoras USB locales...");
            
            // Buscar impresoras con puerto USB* y excluir puertos de red (IP_*)
            String command = "powershell.exe -Command \"Get-Printer | Where-Object {$_.PortName -like 'USB*' -and $_.PortName -notlike 'IP_*'} | Select-Object -First 1 -ExpandProperty Name\"";
            
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            String printerName = reader.readLine();
            if (printerName != null && !printerName.trim().isEmpty()) {
                localPrinterName = printerName.trim();
                
                // Obtener información del puerto para logging
                String portCommand = String.format(
                    "powershell.exe -Command \"Get-Printer -Name '%s' | Select-Object -ExpandProperty PortName\"",
                    localPrinterName
                );
                Process portProcess = Runtime.getRuntime().exec(portCommand);
                BufferedReader portReader = new BufferedReader(new InputStreamReader(portProcess.getInputStream()));
                String portName = portReader.readLine();
                portProcess.waitFor();
                
                log.info("   ✅ Encontrada: {}", localPrinterName);
                log.info("   🔌 Puerto USB: {}", portName != null ? portName.trim() : "Desconocido");
            } else {
                log.warn("   ⚠️ No se encontraron impresoras con puerto USB");
                log.warn("   💡 Verifica que la impresora esté conectada por USB");
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
            
                        // Guardar en archivo temporal con extensión correcta según el tipo
            String fileExtension = detectFileExtension(data);
            Path tempFile = Files.createTempFile("print-job-", fileExtension);
            
            log.debug("   🔍 Escribiendo {} bytes al archivo {}", data.length, tempFile);
            Files.write(tempFile, data);
            
            // Verificar que el archivo se escribió correctamente
            long savedSize = Files.size(tempFile);
            if (savedSize != data.length) {
                log.error("   ❌ ERROR: Tamaño inconsistente!");
                log.error("      Bytes recibidos: {}", data.length);
                log.error("      Bytes guardados: {}", savedSize);
            } else {
                log.debug("   ✅ Archivo verificado: {} bytes", savedSize);
            }
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
            log.info("   🖨️ Enviando a impresora: {}", localPrinterName);
            
            // Detectar tipo de archivo
            byte[] header = Files.readAllBytes(file);
            boolean isPDF = header.length >= 4 && 
                          header[0] == 0x25 && header[1] == 0x50 && 
                          header[2] == 0x44 && header[3] == 0x46;
            boolean isPCL = header.length >= 2 && 
                          header[0] == 0x1B && (header[1] == 0x45 || header[1] == 0x26);
            boolean isPostScript = header.length >= 4 && 
                                 header[0] == 0x25 && header[1] == 0x21;
            
            if (isPDF) {
                log.info("   📄 Tipo: PDF ({} bytes)", header.length);
                return printPDF(file);
            } else if (isPCL || isPostScript) {
                log.info("   📄 Tipo: {} ({} bytes)", isPCL ? "PCL" : "PostScript", header.length);
                return printRawData(file);
            } else {
                log.info("   📄 Tipo: RAW/Desconocido ({} bytes)", header.length);
                // Intentar como RAW primero, si falla intentar como PDF
                if (printRawData(file)) {
                    return true;
                }
                log.warn("   ⚠️ Formato RAW falló, intentando como PDF...");
                return printPDF(file);
            }
            
        } catch (Exception e) {
            log.error("   ❌ Excepción al enviar a impresora local", e);
            return false;
        }
    }
    
    /**
     * Imprime un archivo PDF usando diferentes métodos
     */
    private boolean printPDF(Path file) {
        // MÉTODO 1: Usar SumatraPDF (mejor opción si está instalado)
        if (printWithSumatraPDF(file)) {
            return true;
        }
        
        // MÉTODO 2: Usar Adobe Reader (si está instalado)
        if (printWithAdobeReader(file)) {
            return true;
        }
        
        // MÉTODO 3: Usar PowerShell con .NET (Windows 10+)
        if (printWithPowerShell(file)) {
            return true;
        }
        
        // MÉTODO 4: Usar Ghostscript (si está instalado)
        if (printWithGhostscript(file)) {
            return true;
        }
        
        log.error("   ❌ Todos los métodos de impresión PDF fallaron");
        log.error("   💡 Instala SumatraPDF para impresión silenciosa: https://www.sumatrapdfreader.org/");
        return false;
    }
    
    /**
     * Imprime datos RAW (PCL, PostScript, etc.) directamente al puerto
     */
    private boolean printRawData(Path file) {
        try {
            log.info("   🔄 Enviando datos RAW al puerto de impresora...");
            
            // Obtener el puerto de la impresora
            String printerPort = getPrinterPort(localPrinterName);
            
            if (printerPort == null || printerPort.startsWith("IP_")) {
                log.error("   ❌ Puerto USB no disponible");
                return false;
            }
            
            log.debug("   Puerto: {}", printerPort);
            
            // Copiar archivo al puerto
            String copyCommand = String.format("cmd.exe /c copy /B \"%s\" \"%s\"", 
                file.toAbsolutePath(), printerPort);
            
            Process process = Runtime.getRuntime().exec(copyCommand);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("   ✅ Datos RAW enviados exitosamente");
                return true;
            } else {
                log.error("   ❌ Error al copiar al puerto (código: {})", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("   ❌ Error enviando datos RAW", e);
            return false;
        }
    }
    
        /**
     * Imprime PDF usando SumatraPDF (impresión silenciosa)
     */
    private boolean printWithSumatraPDF(Path file) {
        try {
            // Buscar SumatraPDF en ubicaciones comunes
            String[] possiblePaths = {
                "C:\\Program Files\\SumatraPDF\\SumatraPDF.exe",
                "C:\\Program Files (x86)\\SumatraPDF\\SumatraPDF.exe",
                System.getenv("LOCALAPPDATA") + "\\SumatraPDF\\SumatraPDF.exe",
                System.getenv("ProgramFiles") + "\\SumatraPDF\\SumatraPDF.exe"
            };
            
            String sumatraPath = null;
            for (String path : possiblePaths) {
                if (path != null && Files.exists(Paths.get(path))) {
                    sumatraPath = path;
                    break;
                }
            }
            
            if (sumatraPath == null) {
                log.debug("   ⏭️  SumatraPDF no encontrado, saltando...");
                return false;
            }
            
            log.info("   🔄 Método 1: SumatraPDF (impresión silenciosa)...");
            log.debug("      Ejecutable: {}", sumatraPath);
            log.debug("      Impresora: {}", localPrinterName);
            log.debug("      Archivo: {}", file.toAbsolutePath());
            
            // Usar ProcessBuilder para mejor manejo de argumentos con espacios
            ProcessBuilder pb = new ProcessBuilder(
                sumatraPath,
                "-print-to",
                localPrinterName,
                "-silent",
                file.toAbsolutePath().toString()
            );
            
            // Redirigir stdout y stderr para capturar errores
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // Capturar salida para diagnóstico
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // Esperar máximo 30 segundos
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            
            if (!finished) {
                log.warn("   ⚠️ SumatraPDF timeout, abortando...");
                process.destroyForcibly();
                return false;
            }
            
            int exitCode = process.exitValue();
            
            // Mostrar salida si hay errores
            if (exitCode != 0 && output.length() > 0) {
                log.warn("   📄 Salida de SumatraPDF:");
                for (String line : output.toString().split("\n")) {
                    if (!line.trim().isEmpty()) {
                        log.warn("      {}", line.trim());
                    }
                }
            }
            
            if (exitCode == 0) {
                log.info("   ✅ PDF enviado con SumatraPDF");
                return true;
            } else {
                log.warn("   ⚠️ SumatraPDF falló (código: {})", exitCode);
                
                // Diagnósticos adicionales
                verifyPrinterAccess();
                verifyPdfFile(file);
                
                return false;
            }
            
        } catch (Exception e) {
            log.warn("   ⚠️ Error con SumatraPDF: {}", e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("   Stack trace:", e);
            }
            return false;
        }
    }
    
    /**
     * Imprime PDF usando Adobe Reader
     */
    private boolean printWithAdobeReader(Path file) {
        try {
            // Buscar Adobe Reader
            String[] possiblePaths = {
                "C:\\Program Files\\Adobe\\Acrobat DC\\Acrobat\\Acrobat.exe",
                "C:\\Program Files (x86)\\Adobe\\Acrobat Reader DC\\Reader\\AcroRd32.exe",
                "C:\\Program Files\\Adobe\\Acrobat Reader DC\\Reader\\AcroRd32.exe"
            };
            
            String adobePath = null;
            for (String path : possiblePaths) {
                if (Files.exists(Paths.get(path))) {
                    adobePath = path;
                    break;
                }
            }
            
            if (adobePath == null) {
                log.debug("   ⏭️  Adobe Reader no encontrado, saltando...");
                return false;
            }
            
            log.info("   🔄 Método 2: Adobe Reader...");
            
            // Comando: AcroRd32.exe /t "archivo.pdf" "Impresora"
            String command = String.format("\"%s\" /t \"%s\" \"%s\"",
                adobePath, file.toAbsolutePath(), localPrinterName);
            
            Process process = Runtime.getRuntime().exec(command);
            
            // Adobe Reader se cierra solo, esperar máximo 30 segundos
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            
            if (!finished) {
                log.warn("   ⚠️ Adobe Reader timeout");
                process.destroyForcibly();
                return false;
            }
            
            // Adobe Reader siempre retorna 0, esperar un poco y asumir éxito
            Thread.sleep(2000);
            log.info("   ✅ PDF enviado con Adobe Reader");
            return true;
            
        } catch (Exception e) {
            log.debug("   ⚠️ Error con Adobe Reader: {}", e.getMessage());
            return false;
        }
    }
    
        /**
     * Imprime PDF usando PowerShell y .NET Framework
     */
    private boolean printWithPowerShell(Path file) {
        try {
            log.info("   🔄 Método 3: PowerShell con .NET...");
            
            // Script PowerShell que usa Shell.Application para imprimir
            // Escapar comillas simples en el nombre de la impresora
            String safePrinterName = localPrinterName.replace("'", "''");
            String safeFilePath = file.toAbsolutePath().toString().replace("'", "''");
            
            String script = String.format(
                "$ErrorActionPreference = 'Stop'; " +
                "try { " +
                "  $printer = Get-Printer -Name '%s' -ErrorAction Stop; " +
                "  $shell = New-Object -ComObject Shell.Application; " +
                "  $folder = $shell.Namespace((Get-Item '%s').DirectoryName); " +
                "  $item = $folder.ParseName((Get-Item '%s').Name); " +
                "  $verb = $item.Verbs() | Where-Object {$_.Name -match 'Imprimir|Print'}; " +
                "  if ($verb) { " +
                "    $verb[0].DoIt(); " +
                "    Start-Sleep -Seconds 3; " +
                "    Write-Output 'SUCCESS'; " +
                "    exit 0; " +
                "  } else { " +
                "    Write-Error 'No se encontró verbo de impresión'; " +
                "    exit 1; " +
                "  } " +
                "} catch { " +
                "  Write-Error $_.Exception.Message; " +
                "  exit 1; " +
                "}",
                safePrinterName, safeFilePath, safeFilePath
            );
            
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-ExecutionPolicy", "Bypass",
                "-NoProfile",
                "-Command", script
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Capturar salida
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            
            if (!finished) {
                log.warn("   ⚠️ PowerShell timeout");
                process.destroyForcibly();
                return false;
            }
            
            int exitCode = process.exitValue();
            
            if (exitCode != 0 && output.length() > 0) {
                log.warn("   📄 Salida de PowerShell:");
                for (String line : output.toString().split("\n")) {
                    if (!line.trim().isEmpty()) {
                        log.warn("      {}", line.trim());
                    }
                }
            }
            
            if (exitCode == 0) {
                log.info("   ✅ PDF enviado con PowerShell");
                return true;
            } else {
                log.warn("   ⚠️ PowerShell falló (código: {})", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.warn("   ⚠️ Error con PowerShell: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Imprime PDF usando Ghostscript
     */
    private boolean printWithGhostscript(Path file) {
        try {
            // Buscar Ghostscript
            String gsPath = findGhostscript();
            if (gsPath == null) {
                log.debug("   ⏭️  Ghostscript no encontrado, saltando...");
                return false;
            }
            
            log.info("   🔄 Método 4: Ghostscript...");
            
            // Comando Ghostscript para imprimir
            String command = String.format(
                "\"%s\" -dPrinted -dBATCH -dNOPAUSE -dNOSAFER -q -dNumCopies=1 " +
                "-sDEVICE=mswinpr2 -sOutputFile=\"%%printer%%%s\" \"%s\"",
                gsPath, localPrinterName, file.toAbsolutePath()
            );
            
            Process process = Runtime.getRuntime().exec(command);
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            
            if (!finished) {
                log.warn("   ⚠️ Ghostscript timeout");
                process.destroyForcibly();
                return false;
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("   ✅ PDF enviado con Ghostscript");
                return true;
            } else {
                log.warn("   ⚠️ Ghostscript falló (código: {})", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.debug("   ⚠️ Error con Ghostscript: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Busca la instalación de Ghostscript
     */
    private String findGhostscript() {
        try {
            // Buscar en ubicaciones comunes
            String[] possibleDirs = {
                "C:\\Program Files\\gs",
                "C:\\Program Files (x86)\\gs"
            };
            
            for (String dir : possibleDirs) {
                File gsDir = new File(dir);
                if (gsDir.exists() && gsDir.isDirectory()) {
                    // Buscar subdirectorios (ej: gs9.56.1)
                    File[] versions = gsDir.listFiles();
                    if (versions != null) {
                        for (File version : versions) {
                            File gsExe = new File(version, "bin\\gswin64c.exe");
                            if (gsExe.exists()) {
                                return gsExe.getAbsolutePath();
                            }
                            gsExe = new File(version, "bin\\gswin32c.exe");
                            if (gsExe.exists()) {
                                return gsExe.getAbsolutePath();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignorar
        }
        return null;
    }
    
    /**
     * Obtiene el puerto de una impresora usando PowerShell
     * IMPORTANTE: Busca SOLO impresoras USB locales, no puertos de red
     */
    private String getPrinterPort(String printerName) {
        try {
            // Buscar SOLO impresoras con puerto USB
            String command = String.format(
                "powershell.exe -Command \"Get-Printer | Where-Object {$_.Name -eq '%s' -and ($_.PortName -like 'USB*' -or $_.Type -eq 'Local')} | Select-Object -First 1 -ExpandProperty PortName\"",
                printerName
            );
            
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String port = reader.readLine();
            process.waitFor();
            
            if (port != null && !port.trim().isEmpty()) {
                String portName = port.trim();
                log.debug("   Puerto detectado: {}", portName);
                
                // FILTRO: Ignorar puertos de red (IP_*)
                if (portName.startsWith("IP_")) {
                    log.warn("   ⚠️ Puerto {} es de RED, no USB. Buscando puerto USB alternativo...", portName);
                    
                    // Buscar puerto USB alternativo
                    String usbCommand = "powershell.exe -Command \"Get-Printer | Where-Object {$_.PortName -like 'USB*'} | Select-Object -First 1 -ExpandProperty PortName\"";
                    Process usbProcess = Runtime.getRuntime().exec(usbCommand);
                    BufferedReader usbReader = new BufferedReader(new InputStreamReader(usbProcess.getInputStream()));
                    String usbPort = usbReader.readLine();
                    usbProcess.waitFor();
                    
                    if (usbPort != null && !usbPort.trim().isEmpty()) {
                        log.info("   ✅ Puerto USB encontrado: {}", usbPort.trim());
                        return usbPort.trim();
                    }
                }
                
                return portName;
            }
            
        } catch (Exception e) {
            log.debug("Error obteniendo puerto de impresora", e);
        }
        
        // Fallback: intentar con el nombre de la impresora como share
        log.warn("   ⚠️ No se encontró puerto USB, usando fallback: \\\\localhost\\{}", printerName);
        return "\\\\localhost\\" + printerName;
    }
    
    /**
     * Configura el driver de la impresora para deshabilitar FAX
     * y evitar diálogos durante la impresión
     */
    private void configureDriverToDisableFax() {
        try {
            log.info("⚙️  Configurando driver para deshabilitar FAX...");
            
            // PASO 1: Deshabilitar servicio de FAX de Windows
            disableWindowsFaxService();
            
            // PASO 2: Deshabilitar comunicación bidireccional en el driver
            disableBidirectionalSupport();
            
            // PASO 3: Configurar driver para impresión silenciosa
            configureSilentPrinting();
            
            log.info("   ✅ Configuración aplicada exitosamente");
            
        } catch (Exception e) {
            log.warn("   ⚠️  Error configurando driver: {}", e.getMessage());
            log.warn("   💡 Si aparece el diálogo de FAX, configura manualmente:");
            log.warn("      1. Dispositivos e Impresoras → Click derecho en la impresora");
            log.warn("      2. Propiedades → Puertos → Deshabilitar 'comunicación bidireccional'");
        }
    }
    
    /**
     * Deshabilita el servicio de FAX de Windows
     */
    private void disableWindowsFaxService() {
        try {
            log.info("   🔄 Deshabilitando servicio de FAX de Windows...");
            
            // Verificar si el servicio existe
            String checkCommand = "sc query Fax";
            Process checkProcess = Runtime.getRuntime().exec(checkCommand);
            int checkResult = checkProcess.waitFor();
            
            if (checkResult == 0) {
                // El servicio existe, intentar detenerlo y deshabilitarlo
                log.info("      Servicio FAX detectado, procediendo a deshabilitar...");
                
                // Detener servicio
                String stopCommand = "sc stop Fax";
                Process stopProcess = Runtime.getRuntime().exec(stopCommand);
                stopProcess.waitFor();
                
                // Deshabilitar servicio
                String disableCommand = "sc config Fax start= disabled";
                Process disableProcess = Runtime.getRuntime().exec(disableCommand);
                int disableResult = disableProcess.waitFor();
                
                if (disableResult == 0) {
                    log.info("      ✅ Servicio FAX deshabilitado correctamente");
                } else {
                    log.warn("      ⚠️  No se pudo deshabilitar el servicio FAX (requiere permisos admin)");
                }
            } else {
                log.debug("      ℹ️  Servicio FAX no está instalado");
            }
            
        } catch (Exception e) {
            log.debug("      ⚠️  Error deshabilitando servicio FAX: {}", e.getMessage());
        }
    }
    
    /**
     * Deshabilita la comunicación bidireccional del driver de la impresora
     * usando PowerShell y el registro de Windows
     */
    private void disableBidirectionalSupport() {
        try {
            log.info("   🔄 Deshabilitando comunicación bidireccional...");
            
            // Método 1: Usar PowerShell para configurar el puerto
            String script = String.format(
                "$printer = Get-Printer -Name '%s' -ErrorAction SilentlyContinue; " +
                "if ($printer) { " +
                "  $port = Get-PrinterPort -Name $printer.PortName -ErrorAction SilentlyContinue; " +
                "  if ($port) { " +
                "    try { " +
                "      Set-PrinterPort -Name $port.Name -EnableBidirectional $false -ErrorAction Stop; " +
                "      Write-Output 'SUCCESS'; " +
                "    } catch { " +
                "      Write-Output 'FAILED'; " +
                "    } " +
                "  } " +
                "}",
                localPrinterName
            );
            
            String command = "powershell.exe -ExecutionPolicy Bypass -Command \"" + script + "\"";
            Process process = Runtime.getRuntime().exec(command);
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();
            
            if ("SUCCESS".equals(result)) {
                log.info("      ✅ Comunicación bidireccional deshabilitada");
            } else {
                log.warn("      ⚠️  No se pudo deshabilitar (puede requerir permisos admin)");
                
                // Método 2 (fallback): Modificar registro directamente
                disableBidirectionalViaRegistry();
            }
            
        } catch (Exception e) {
            log.debug("      ⚠️  Error: {}", e.getMessage());
        }
    }
    
    /**
     * Deshabilita bidireccional modificando el registro de Windows
     */
    private void disableBidirectionalViaRegistry() {
        try {
            log.info("      🔄 Intentando vía registro de Windows...");
            
            // Obtener el puerto de la impresora
            String port = getPrinterPort(localPrinterName);
            if (port == null) {
                return;
            }
            
            // Construir ruta del registro
            // HKLM\SYSTEM\CurrentControlSet\Control\Print\Monitors\Standard TCP/IP Port\Ports\<Puerto>
            String regPath = String.format(
                "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Print\\Monitors\\Standard TCP/IP Port\\Ports\\%s",
                port
            );
            
            // Establecer el valor EnableBidi a 0
            String regCommand = String.format(
                "reg add \"%s\" /v EnableBidi /t REG_DWORD /d 0 /f",
                regPath
            );
            
            Process process = Runtime.getRuntime().exec(regCommand);
            int result = process.waitFor();
            
            if (result == 0) {
                log.info("      ✅ Registro modificado correctamente");
            } else {
                log.debug("      ℹ️  No se pudo modificar el registro (puede no ser necesario)");
            }
            
        } catch (Exception e) {
            log.debug("      ⚠️  Error modificando registro: {}", e.getMessage());
        }
    }
    
    /**
     * Configura el driver para impresión silenciosa sin diálogos
     */
    private void configureSilentPrinting() {
        try {
            log.info("   🔄 Configurando impresión silenciosa...");
            
            // Configurar preferencias de impresión por defecto usando PowerShell
            String script = String.format(
                "$printer = Get-Printer -Name '%s' -ErrorAction SilentlyContinue; " +
                "if ($printer) { " +
                "  try { " +
                "    # Establecer como impresora por defecto temporalmente para configurar " +
                "    # Esto no la hace predeterminada permanentemente " +
                "    $printerConfig = Get-WmiObject -Query \"SELECT * FROM Win32_Printer WHERE Name='$($printer.Name)'\" -ErrorAction Stop; " +
                "    if ($printerConfig) { " +
                "      # Configurar para no mostrar diálogos " +
                "      $printerConfig.KeepPrintedJobs = $false; " +
                "      $printerConfig.Put() | Out-Null; " +
                "      Write-Output 'SUCCESS'; " +
                "    } " +
                "  } catch { " +
                "    Write-Output 'FAILED'; " +
                "  } " +
                "}",
                localPrinterName
            );
            
            String command = "powershell.exe -ExecutionPolicy Bypass -Command \"" + script + "\"";
            Process process = Runtime.getRuntime().exec(command);
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();
            
            if ("SUCCESS".equals(result)) {
                log.info("      ✅ Impresión silenciosa configurada");
            } else {
                log.debug("      ℹ️  Configuración adicional no aplicada (puede no ser necesaria)");
            }
            
        } catch (Exception e) {
            log.debug("      ⚠️  Error: {}", e.getMessage());
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
     * Detecta la extensión correcta del archivo según su contenido
     */
    private String detectFileExtension(byte[] data) {
        if (data == null || data.length < 4) {
            return ".dat";
        }
        
        // PDF: %PDF
        if (data[0] == 0x25 && data[1] == 0x50 && data[2] == 0x44 && data[3] == 0x46) {
            return ".pdf";
        }
        
        // PostScript: %!
        if (data[0] == 0x25 && data[1] == 0x21) {
            return ".ps";
        }
        
        // PCL
        if (data[0] == 0x1B && (data[1] == 0x45 || data[1] == 0x26)) {
            return ".pcl";
        }
        
        return ".dat";
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
    
    /**
     * Verifica que el archivo PDF sea válido y legible
     */
    private void verifyPdfFile(Path file) {
        try {
            if (!Files.exists(file)) {
                log.error("      ❌ El archivo PDF no existe: {}", file);
                return;
            }
            
            if (!Files.isReadable(file)) {
                log.error("      ❌ El archivo PDF no es legible (permisos?): {}", file);
                return;
            }
            
            long fileSize = Files.size(file);
            if (fileSize == 0) {
                log.error("      ❌ El archivo PDF está vacío");
                return;
            }
            
            // Verificar header PDF
            byte[] header = new byte[4];
            try (FileInputStream fis = new FileInputStream(file.toFile())) {
                fis.read(header);
            }
            
            boolean isValidPdf = header[0] == 0x25 && header[1] == 0x50 && 
                                header[2] == 0x44 && header[3] == 0x46; // %PDF
            
            if (!isValidPdf) {
                log.error("      ❌ El archivo no parece ser un PDF válido (header incorrecto)");
                log.debug("         Header: {} {} {} {}", 
                    String.format("0x%02X", header[0]),
                    String.format("0x%02X", header[1]),
                    String.format("0x%02X", header[2]),
                    String.format("0x%02X", header[3]));
                return;
            }
            
            log.debug("      ✅ Archivo PDF válido ({} bytes)", fileSize);
            
        } catch (Exception e) {
            log.error("      ❌ Error verificando archivo PDF: {}", e.getMessage());
        }
    }
    
    /**
     * Verifica que la impresora esté accesible y en estado correcto
     */
    private void verifyPrinterAccess() {
        try {
            log.debug("      🔍 Verificando estado de impresora...");
            
            String script = String.format(
                "$printer = Get-Printer -Name '%s' -ErrorAction Stop; " +
                "Write-Output \"Name: $($printer.Name)\"; " +
                "Write-Output \"Status: $($printer.PrinterStatus)\"; " +
                "Write-Output \"JobCount: $($printer.JobCount)\"; " +
                "Write-Output \"PortName: $($printer.PortName)\"; " +
                "Write-Output \"DriverName: $($printer.DriverName)\"",
                localPrinterName.replace("'", "''")
            );
            
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-ExecutionPolicy", "Bypass",
                "-NoProfile",
                "-Command", script
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("         {}", line.trim());
                }
            }
            
            process.waitFor(5, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            log.error("      ❌ Error verificando impresora: {}", e.getMessage());
        }
    }
}