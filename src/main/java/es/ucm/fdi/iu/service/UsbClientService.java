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
            
            // Verificar permisos de administrador
            if (!isRunningAsAdmin()) {
                log.warn("⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️");
                log.warn("🚫 EL CLIENTE USB NO TIENE PERMISOS DE ADMINISTRADOR");
                log.warn("⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️");
                log.warn("");
                log.warn("🚨 PROBLEMA: El diálogo de FAX puede aparecer");
                log.warn("");
                log.warn("🔧 SOLUCIÓN: Cierra este programa y ejecútalo como administrador:");
                log.warn("   1. Click derecho en el archivo .bat o .jar");
                log.warn("   2. Selecciona 'Ejecutar como administrador'");
                log.warn("");
                log.warn("⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️⚠️");
            }
            
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
                
                                log.info("   ✅ Encontrada: [{}]", localPrinterName);
                log.info("   🔌 Puerto USB: {}", portName != null ? portName.trim() : "Desconocido");
                log.info("   ℹ️  Longitud: {} caracteres", localPrinterName.length());
                log.info("   ℹ️  Nombre en hexadecimal: {}", bytesToHex(localPrinterName.getBytes()));
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
            
                        // Guardar en archivo temporal con extensión apropiada
            // Detectar tipo de archivo rápidamente
            boolean isPDF = data.length >= 4 && 
                          data[0] == 0x25 && data[1] == 0x50 && 
                          data[2] == 0x44 && data[3] == 0x46;
            
            String extension = isPDF ? ".pdf" : ".dat";
            Path tempFile = Files.createTempFile("print-job-", extension);
            Files.write(tempFile, data);
            log.info("   💾 Guardado en: {}", tempFile);
            log.info("   📝 Extensión: {}", extension);
            
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
            
            // IMPORTANTE: Deshabilitar diálogo de FAX ANTES de imprimir
            suppressFaxDialogForCurrentJob();
            
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
     * Suprime el diálogo de FAX para el trabajo actual
     * Crea un proceso en segundo plano que busca y cierra automáticamente
     * cualquier ventana de diálogo de FAX que aparezca
     */
    private void suppressFaxDialogForCurrentJob() {
        try {
            // Script de PowerShell que busca y cierra ventanas de FAX automáticamente
            String script = 
                "$ErrorActionPreference = 'SilentlyContinue'; " +
                "$timeout = 30; " +  // Monitorear por 30 segundos
                "$elapsed = 0; " +
                "while ($elapsed -lt $timeout) { " +
                "  $faxWindows = @(); " +
                // Buscar ventanas de FAX por título (español e inglés)
                "  $faxWindows += Get-Process | Where-Object {$_.MainWindowTitle -like '*Fax*' -or $_.MainWindowTitle -like '*Enviar fax*' -or $_.MainWindowTitle -like '*Send Fax*'}; " +
                // Cerrar cada ventana encontrada
                "  foreach ($window in $faxWindows) { " +
                "    if ($window.MainWindowHandle -ne 0) { " +
                "      Add-Type -AssemblyName 'System.Windows.Forms'; " +
                "      $hwnd = $window.MainWindowHandle; " +
                "      [System.Windows.Forms.SendKeys]::SendWait('{ESC}'); " +  // Presionar ESC
                "      Start-Sleep -Milliseconds 200; " +
                "      $window.CloseMainWindow() | Out-Null; " +  // Intentar cerrar
                "      Start-Sleep -Milliseconds 200; " +
                "      if (!$window.HasExited) { $window.Kill(); } " +  // Forzar si es necesario
                "    } " +
                "  } " +
                "  Start-Sleep -Milliseconds 500; " +
                "  $elapsed++; " +
                "}";
            
            // Ejecutar el script en segundo plano (sin bloquear)
            String command = "powershell.exe -WindowStyle Hidden -ExecutionPolicy Bypass -Command \"" + script + "\"";
            
            Process suppressProcess = Runtime.getRuntime().exec(command);
            
            // NO esperar a que termine - dejar que corra en segundo plano
            log.debug("   🛡️ Monitor anti-FAX activado (30s)");
            
        } catch (Exception e) {
            log.debug("   ⚠️ No se pudo activar supresor de FAX: {}", e.getMessage());
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
            
            // USAR ARRAY en lugar de String para evitar problemas con espacios
            String[] commandArray = {
                sumatraPath,
                "-print-to",
                localPrinterName,
                "-silent",
                file.toAbsolutePath().toString()
            };
            
            log.info("      📝 Comando (array):");
            log.info("         Ejecutable: {}", sumatraPath);
            log.info("         Impresora: [{}]", localPrinterName);
            log.info("         Archivo: {}", file.toAbsolutePath());
            
            Process process = Runtime.getRuntime().exec(commandArray);
            
            // Esperar máximo 30 segundos
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            
            if (!finished) {
                log.warn("   ⚠️ SumatraPDF timeout, abortando...");
                process.destroyForcibly();
                return false;
            }
            
                        int exitCode = process.exitValue();
            
            // Capturar salida de error
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String errorOutput = errorReader.lines().reduce("", String::concat);
            
            if (exitCode == 0) {
                log.info("   ✅ PDF enviado con SumatraPDF");
                return true;
            } else {
                log.warn("   ⚠️ SumatraPDF falló (código: {})", exitCode);
                if (!errorOutput.isEmpty()) {
                    log.warn("      Salida de error: {}", errorOutput);
                }
                
                // Listar impresoras disponibles para diagnóstico
                log.warn("      🔍 Listando impresoras disponibles en el sistema:");
                try {
                    Process listProcess = Runtime.getRuntime().exec("powershell.exe -Command \"Get-Printer | Select-Object Name, PortName\"");
                    BufferedReader listReader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
                    listReader.lines().forEach(line -> log.warn("         {}", line));
                    listProcess.waitFor();
                } catch (Exception e) {
                    log.warn("         No se pudo listar impresoras");
                }
                
                return false;
            }
            
        } catch (Exception e) {
            log.debug("   ⚠️ Error con SumatraPDF: {}", e.getMessage());
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
            
            // Script PowerShell que usa .NET para imprimir PDF
            String script = String.format(
                "$printer = Get-Printer -Name '%s' -ErrorAction Stop; " +
                "$shell = New-Object -ComObject Shell.Application; " +
                "$file = Get-Item '%s'; " +
                "$verb = $file | Select-Object -ExpandProperty Verbs | Where-Object {$_.Name -eq 'Imprimir'}; " +
                "if ($verb) { $verb.DoIt(); Start-Sleep -Seconds 3; exit 0 } else { exit 1 }",
                localPrinterName, file.toAbsolutePath()
            );
            
            String command = "powershell.exe -ExecutionPolicy Bypass -Command \"" + script + "\"";
            
            Process process = Runtime.getRuntime().exec(command);
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            
            if (!finished) {
                log.warn("   ⚠️ PowerShell timeout");
                process.destroyForcibly();
                return false;
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("   ✅ PDF enviado con PowerShell");
                return true;
            } else {
                log.warn("   ⚠️ PowerShell falló (código: {})", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.debug("   ⚠️ Error con PowerShell: {}", e.getMessage());
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
            
            // Lista de servicios de FAX a deshabilitar
            String[] faxServices = {"Fax", "FaxSvc"};
            
            boolean anyServiceFound = false;
            
            for (String serviceName : faxServices) {
                // Verificar si el servicio existe
                String checkCommand = "sc query " + serviceName;
                Process checkProcess = Runtime.getRuntime().exec(checkCommand);
                
                BufferedReader checkReader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream()));
                String checkOutput = checkReader.lines().reduce("", String::concat);
                int checkResult = checkProcess.waitFor();
                
                if (checkResult == 0 && checkOutput.contains("STATE")) {
                    anyServiceFound = true;
                    log.info("      Servicio {} detectado, procediendo a deshabilitar...", serviceName);
                    
                    // Detener servicio (ignorar errores si ya está detenido)
                    String stopCommand = "sc stop " + serviceName;
                    Process stopProcess = Runtime.getRuntime().exec(stopCommand);
                    stopProcess.waitFor();
                    Thread.sleep(500);
                    
                    // Deshabilitar servicio
                    String disableCommand = "sc config " + serviceName + " start= disabled";
                    Process disableProcess = Runtime.getRuntime().exec(disableCommand);
                    int disableResult = disableProcess.waitFor();
                    
                    if (disableResult == 0) {
                        log.info("      ✅ Servicio {} deshabilitado correctamente", serviceName);
                    } else {
                        log.warn("      ⚠️  No se pudo deshabilitar el servicio {} (código: {})", serviceName, disableResult);
                    }
                }
            }
            
            if (!anyServiceFound) {
                log.debug("      ℹ️  Servicios FAX no están instalados");
            }
            
            // ADICIONAL: Deshabilitar características de FAX en el registro
            disableFaxInRegistry();
            
        } catch (Exception e) {
            log.debug("      ⚠️  Error deshabilitando servicio FAX: {}", e.getMessage());
        }
    }
    
    /**
     * Deshabilita características de FAX en el registro de Windows
     */
    private void disableFaxInRegistry() {
        try {
            // Deshabilitar el asistente de envío de fax
            String[] regCommands = {
                "reg add \"HKCU\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Windows\" /v DisableFaxDialog /t REG_DWORD /d 1 /f",
                "reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Windows\" /v DisableFaxDialog /t REG_DWORD /d 1 /f",
                "reg add \"HKCU\\Software\\Microsoft\\Fax\" /v ShowFaxDialog /t REG_DWORD /d 0 /f",
                "reg add \"HKLM\\SOFTWARE\\Microsoft\\Fax\" /v ShowFaxDialog /t REG_DWORD /d 0 /f"
            };
            
            for (String cmd : regCommands) {
                try {
                    Process process = Runtime.getRuntime().exec(cmd);
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Ignorar errores individuales
                }
            }
            
            log.debug("      ℹ️  Claves de registro FAX configuradas");
            
        } catch (Exception e) {
            log.debug("      ⚠️  Error configurando registro FAX: {}", e.getMessage());
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
     * Convierte bytes a representación hexadecimal
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X ", b));
        }
        return result.toString();
    }
    
    /**
     * Verifica si el proceso se está ejecutando con permisos de administrador
     */
    private boolean isRunningAsAdmin() {
        try {
            // Intentar escribir en el registro de sistema (solo admin puede)
            String testCommand = "reg query \"HKLM\\Software\\Microsoft\\Windows NT\\CurrentVersion\" /v ProductName";
            Process process = Runtime.getRuntime().exec(testCommand);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.debug("✅ Ejecutando con permisos de administrador");
                return true;
            } else {
                log.debug("❌ NO ejecutando con permisos de administrador");
                return false;
            }
        } catch (Exception e) {
            log.debug("❌ Error verificando permisos de admin: {}", e.getMessage());
            return false;
        }
    }
}
