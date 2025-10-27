package es.ucm.fdi.iu.service;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.MalformedURLException;
import java.util.*;

/**
 * Servicio SMB/CIFS en Java puro
 * 
 * REEMPLAZA SambaService con implementación Java pura usando JCIFS-NG
 * Permite:
 * - Conectarse a recursos compartidos SMB
 * - Descubrir impresoras compartidas en red Windows
 * - Enviar trabajos a impresoras SMB
 * - Gestionar credenciales SMB
 * 
 * No requiere Samba instalado en el servidor
 */
@Service
@Slf4j
public class SmbShareService {

    private CIFSContext defaultContext;
    
    public SmbShareService() {
        try {
            // Configurar contexto CIFS por defecto
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.minVersion", "SMB202");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            props.setProperty("jcifs.resolveOrder", "DNS");
            
            PropertyConfiguration config = new PropertyConfiguration(props);
            this.defaultContext = new BaseContext(config);
            
            log.info("Servicio SMB inicializado correctamente");
        } catch (Exception e) {
            log.error("Error al inicializar contexto SMB", e);
        }
    }

    /**
     * Información de un recurso compartido SMB
     */
    public static class SmbShareInfo {
        private String name;
        private String path;
        private String server;
        private String type;
        private boolean available;
        
        // Getters y Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        
        public String getServer() { return server; }
        public void setServer(String server) { this.server = server; }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
    }

    /**
     * Descubre recursos compartidos en un servidor SMB
     * 
     * @param serverIp IP o nombre del servidor
     * @param username Usuario (puede ser null para acceso anónimo)
     * @param password Contraseña (puede ser null)
     * @return Lista de recursos compartidos
     */
    public List<SmbShareInfo> discoverShares(String serverIp, String username, String password) {
        List<SmbShareInfo> shares = new ArrayList<>();
        
        try {
            log.info("Descubriendo recursos compartidos en {}", serverIp);
            
            // Crear contexto con credenciales si se proporcionan
            CIFSContext context = defaultContext;
            if (username != null && password != null) {
                NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(
                    null, username, password
                );
                context = defaultContext.withCredentials(auth);
            }
            
            // Conectar al servidor
            String smbUrl = "smb://" + serverIp + "/";
            SmbFile server = new SmbFile(smbUrl, context);
            
            // Listar recursos compartidos
            SmbFile[] files = server.listFiles();
            
            for (SmbFile file : files) {
                try {
                    SmbShareInfo share = new SmbShareInfo();
                    share.setName(file.getName().replace("/", ""));
                    share.setPath(file.getPath());
                    share.setServer(serverIp);
                    share.setAvailable(file.exists());
                    
                    // Determinar tipo
                    int type = file.getType();
                    if (type == SmbFile.TYPE_PRINTER) {
                        share.setType("PRINTER");
                        log.info("✓ Impresora SMB encontrada: {}", share.getName());
                    } else if (type == SmbFile.TYPE_SHARE) {
                        share.setType("SHARE");
                    } else {
                        share.setType("OTHER");
                    }
                    
                    shares.add(share);
                    
                } catch (Exception e) {
                    log.debug("No se pudo acceder a recurso: {}", file.getName());
                }
            }
            
            log.info("Encontrados {} recursos compartidos en {}", shares.size(), serverIp);
            
        } catch (Exception e) {
            log.error("Error al descubrir recursos compartidos: {}", e.getMessage());
        }
        
        return shares;
    }

    /**
     * Descubre impresoras compartidas específicamente
     */
    public List<SmbShareInfo> discoverPrinters(String serverIp, String username, String password) {
        List<SmbShareInfo> allShares = discoverShares(serverIp, username, password);
        return allShares.stream()
            .filter(share -> "PRINTER".equals(share.getType()))
            .toList();
    }

    /**
     * Envía un archivo a una impresora SMB
     * 
     * @param printerPath Ruta SMB de la impresora (ej: //servidor/impresora)
     * @param filePath Archivo local a imprimir
     * @param username Usuario SMB
     * @param password Contraseña SMB
     * @return true si se envió exitosamente
     */
    public boolean printToSmbPrinter(String printerPath, String filePath, 
                                     String username, String password) {
        try {
            log.info("Imprimiendo archivo {} a impresora SMB {}", filePath, printerPath);
            
            // Crear contexto con credenciales
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(
                null, username, password
            );
            CIFSContext context = defaultContext.withCredentials(auth);
            
            // Normalizar ruta SMB
            if (!printerPath.startsWith("smb://")) {
                printerPath = "smb://" + printerPath.replace("\\", "/");
            }
            
            // Conectar a la impresora
            SmbFile printer = new SmbFile(printerPath, context);
            
            if (!printer.exists()) {
                log.error("La impresora SMB no existe: {}", printerPath);
                return false;
            }
            
            // Leer archivo local
            File file = new File(filePath);
            if (!file.exists()) {
                log.error("El archivo no existe: {}", filePath);
                return false;
            }
            
            // Crear archivo temporal en la impresora
            String jobName = file.getName() + "_" + System.currentTimeMillis();
            SmbFile printJob = new SmbFile(printer, jobName);
            
            // Copiar archivo a la impresora
            try (FileInputStream fis = new FileInputStream(file);
                 SmbFileOutputStream sfos = new SmbFileOutputStream(printJob)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                
                while ((bytesRead = fis.read(buffer)) != -1) {
                    sfos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                
                log.info("✓ Archivo enviado exitosamente: {} bytes", totalBytes);
                return true;
            }
            
        } catch (Exception e) {
            log.error("Error al imprimir en impresora SMB: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Verifica si una impresora SMB está disponible
     */
    public boolean isPrinterAvailable(String printerPath, String username, String password) {
        try {
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(
                null, username, password
            );
            CIFSContext context = defaultContext.withCredentials(auth);
            
            if (!printerPath.startsWith("smb://")) {
                printerPath = "smb://" + printerPath.replace("\\", "/");
            }
            
            SmbFile printer = new SmbFile(printerPath, context);
            boolean available = printer.exists() && printer.getType() == SmbFile.TYPE_PRINTER;
            
            log.info("Impresora SMB {}: {}", printerPath, available ? "disponible" : "no disponible");
            return available;
            
        } catch (Exception e) {
            log.error("Error al verificar impresora SMB: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Lista archivos en un recurso compartido
     */
    public List<String> listFiles(String sharePath, String username, String password) {
        List<String> files = new ArrayList<>();
        
        try {
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(
                null, username, password
            );
            CIFSContext context = defaultContext.withCredentials(auth);
            
            if (!sharePath.startsWith("smb://")) {
                sharePath = "smb://" + sharePath.replace("\\", "/");
            }
            
            SmbFile share = new SmbFile(sharePath, context);
            
            if (share.exists() && share.isDirectory()) {
                SmbFile[] fileList = share.listFiles();
                for (SmbFile file : fileList) {
                    files.add(file.getName());
                }
                
                log.info("Encontrados {} archivos en {}", files.size(), sharePath);
            }
            
        } catch (Exception e) {
            log.error("Error al listar archivos SMB: {}", e.getMessage());
        }
        
        return files;
    }

    /**
     * Escanea una red buscando servidores SMB
     * 
     * @param networkRange Rango de red en formato CIDR (ej: 192.168.1.0/24)
     * @return Lista de IPs que responden a SMB
     */
    public List<String> scanNetworkForSmbServers(String networkRange) {
        List<String> servers = new ArrayList<>();
        
        try {
            log.info("Escaneando red {} buscando servidores SMB", networkRange);
            
            // Generar IPs del rango
            List<String> ips = generateIPsFromCIDR(networkRange);
            
            // Escanear en paralelo (limitado a 20 threads)
            int threads = Math.min(20, ips.size());
            java.util.concurrent.ExecutorService executor = 
                java.util.concurrent.Executors.newFixedThreadPool(threads);
            
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            
            for (String ip : ips) {
                futures.add(executor.submit(() -> {
                    try {
                        // Intentar conectar al puerto SMB (445)
                        java.net.Socket socket = new java.net.Socket();
                        socket.connect(new java.net.InetSocketAddress(ip, 445), 500);
                        socket.close();
                        
                        log.debug("Servidor SMB detectado en {}", ip);
                        return ip;
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }
            
            // Recoger resultados
            for (java.util.concurrent.Future<String> future : futures) {
                try {
                    String ip = future.get(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (ip != null) {
                        servers.add(ip);
                    }
                } catch (Exception e) {
                    // Ignorar timeouts
                }
            }
            
            executor.shutdown();
            
            log.info("✓ Encontrados {} servidores SMB en la red", servers.size());
            
        } catch (Exception e) {
            log.error("Error al escanear red: {}", e.getMessage());
        }
        
        return servers;
    }

    /**
     * Descubre impresoras en toda una red
     */
    public List<SmbShareInfo> discoverPrintersInNetwork(String networkRange, 
                                                        String username, String password) {
        List<SmbShareInfo> allPrinters = new ArrayList<>();
        
        // Buscar servidores SMB
        List<String> servers = scanNetworkForSmbServers(networkRange);
        
        // Para cada servidor, buscar impresoras
        for (String server : servers) {
            try {
                List<SmbShareInfo> printers = discoverPrinters(server, username, password);
                allPrinters.addAll(printers);
            } catch (Exception e) {
                log.debug("No se pudieron obtener impresoras de {}", server);
            }
        }
        
        log.info("Total de impresoras SMB descubiertas: {}", allPrinters.size());
        return allPrinters;
    }

    /**
     * Genera lista de IPs desde un rango CIDR
     */
    private List<String> generateIPsFromCIDR(String cidr) {
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
            
            // Limitar a máximo 254 hosts para evitar escaneos muy largos
            int maxHosts = Math.min(broadcast - network - 1, 254);
            
            for (int i = 1; i <= maxHosts; i++) {
                int ip = network + i;
                String ipStr = String.format("%d.%d.%d.%d",
                    (ip >> 24) & 0xFF,
                    (ip >> 16) & 0xFF,
                    (ip >> 8) & 0xFF,
                    ip & 0xFF);
                ips.add(ipStr);
            }
            
        } catch (Exception e) {
            log.error("Error generando IPs desde CIDR: {}", e.getMessage());
        }
        
        return ips;
    }

    /**
     * Valida credenciales SMB en un servidor
     */
    public boolean validateCredentials(String serverIp, String username, String password) {
        try {
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(
                null, username, password
            );
            CIFSContext context = defaultContext.withCredentials(auth);
            
            String smbUrl = "smb://" + serverIp + "/";
            SmbFile server = new SmbFile(smbUrl, context);
            
            // Intentar listar recursos
            server.listFiles();
            
            log.info("✓ Credenciales válidas para {}", serverIp);
            return true;
            
        } catch (SmbAuthException e) {
            log.warn("Credenciales inválidas para {}", serverIp);
            return false;
        } catch (Exception e) {
            log.error("Error al validar credenciales: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Obtiene información detallada de un servidor SMB
     */
    public Map<String, Object> getServerInfo(String serverIp, String username, String password) {
        Map<String, Object> info = new HashMap<>();
        
        try {
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(
                null, username, password
            );
            CIFSContext context = defaultContext.withCredentials(auth);
            
            String smbUrl = "smb://" + serverIp + "/";
            SmbFile server = new SmbFile(smbUrl, context);
            
            info.put("server", serverIp);
            info.put("available", server.exists());
            info.put("serverName", server.getServer());
            
            // Contar recursos por tipo
            SmbFile[] shares = server.listFiles();
            int printers = 0;
            int fileShares = 0;
            
            for (SmbFile share : shares) {
                if (share.getType() == SmbFile.TYPE_PRINTER) {
                    printers++;
                } else if (share.getType() == SmbFile.TYPE_SHARE) {
                    fileShares++;
                }
            }
            
            info.put("printers", printers);
            info.put("fileShares", fileShares);
            info.put("totalShares", shares.length);
            
            log.info("Información de servidor {}: {} impresoras, {} recursos", 
                serverIp, printers, fileShares);
            
        } catch (Exception e) {
            log.error("Error al obtener información del servidor: {}", e.getMessage());
            info.put("error", e.getMessage());
        }
        
        return info;
    }
}
