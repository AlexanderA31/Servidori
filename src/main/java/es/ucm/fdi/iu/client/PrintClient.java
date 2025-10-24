package es.ucm.fdi.iu.client;

import es.ucm.fdi.iu.util.NetworkUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Cliente de impresión para ser usado en las computadoras de la red.
 * Identifica la computadora por su MAC address y envía trabajos a las impresoras asignadas.
 */
@Slf4j
public class PrintClient {

    private final String serverUrl;
    private final String macAddress;
    private String authToken;

    /**
     * Constructor del cliente
     * @param serverUrl URL del servidor (ej: http://192.168.1.100:8080)
     */
    public PrintClient(String serverUrl) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.macAddress = NetworkUtils.getLocalMacAddress();
        
        if (this.macAddress == null) {
            throw new RuntimeException("No se pudo obtener la dirección MAC de esta computadora");
        }
        
        log.info("Cliente de impresión iniciado. MAC: {}", this.macAddress);
    }

    /**
     * Constructor con MAC address específica
     */
    public PrintClient(String serverUrl, String macAddress) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.macAddress = NetworkUtils.normalizeMacAddress(macAddress);
        log.info("Cliente de impresión iniciado. MAC: {}", this.macAddress);
    }

    /**
     * Establece el token de autenticación
     */
    public void setAuthToken(String token) {
        this.authToken = token;
    }

    /**
     * Verifica si esta computadora está autorizada
     */
    public boolean isAuthorized() throws IOException {
        String url = serverUrl + "/api/network-print/check-auth/" + macAddress;
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        addAuthHeader(conn);
        
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            String response = readResponse(conn);
            return response.contains("\"authorized\":true");
        }
        
        return false;
    }

    /**
     * Obtiene las impresoras asignadas a esta computadora
     */
    public String getAssignedPrinters() throws IOException {
        String url = serverUrl + "/api/network-print/assignments/" + macAddress;
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        addAuthHeader(conn);
        
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            return readResponse(conn);
        }
        
        throw new IOException("Error al obtener impresoras: " + responseCode);
    }

    /**
     * Obtiene la impresora por defecto
     */
    public String getDefaultPrinter() throws IOException {
        String url = serverUrl + "/api/network-print/default-printer/" + macAddress;
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        addAuthHeader(conn);
        
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            return readResponse(conn);
        }
        
        return null;
    }

    /**
     * Imprime un documento en una impresora específica usando protocolo RAW
     * @param printerId ID de la impresora
     * @param file Archivo a imprimir
     * @return Respuesta del servidor
     */
    public String printDocument(long printerId, File file) throws IOException {
        String url = serverUrl + "/api/network-print/print/raw";
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        addAuthHeader(conn);
        
        try (OutputStream out = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true)) {
            
            // MAC Address
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"macAddress\"\r\n\r\n");
            writer.append(macAddress).append("\r\n");
            
            // Printer ID
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"printerId\"\r\n\r\n");
            writer.append(String.valueOf(printerId)).append("\r\n");
            
            // File
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                  .append(file.getName()).append("\"\r\n");
            writer.append("Content-Type: application/octet-stream\r\n\r\n");
            writer.flush();
            
            // Escribir contenido del archivo
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            out.flush();
            
            writer.append("\r\n");
            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();
        }
        
        int responseCode = conn.getResponseCode();
        String response = readResponse(conn);
        
        if (responseCode == 200) {
            log.info("Documento enviado exitosamente: {}", file.getName());
            return response;
        } else {
            throw new IOException("Error al imprimir: " + response);
        }
    }

    /**
     * Imprime usando el método IPP
     */
    public String printDocumentIPP(long printerId, File file, int copies) throws IOException {
        String url = serverUrl + "/api/network-print/print/ipp";
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        addAuthHeader(conn);
        
        try (OutputStream out = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true)) {
            
            // MAC Address
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"macAddress\"\r\n\r\n");
            writer.append(macAddress).append("\r\n");
            
            // Printer ID
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"printerId\"\r\n\r\n");
            writer.append(String.valueOf(printerId)).append("\r\n");
            
            // Copies
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"copies\"\r\n\r\n");
            writer.append(String.valueOf(copies)).append("\r\n");
            
            // File
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                  .append(file.getName()).append("\"\r\n");
            writer.append("Content-Type: application/octet-stream\r\n\r\n");
            writer.flush();
            
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            out.flush();
            
            writer.append("\r\n");
            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();
        }
        
        int responseCode = conn.getResponseCode();
        String response = readResponse(conn);
        
        if (responseCode == 200) {
            log.info("Documento enviado exitosamente: {}", file.getName());
            return response;
        } else {
            throw new IOException("Error al imprimir: " + response);
        }
    }

    /**
     * Obtiene estadísticas de esta computadora
     */
    public String getStats() throws IOException {
        String url = serverUrl + "/api/network-print/stats/" + macAddress;
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        addAuthHeader(conn);
        
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            return readResponse(conn);
        }
        
        throw new IOException("Error al obtener estadísticas: " + responseCode);
    }

    /**
     * Obtiene información de esta computadora
     */
    public String getComputerInfo() throws IOException {
        String url = serverUrl + "/api/network-print/computers/" + macAddress;
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        addAuthHeader(conn);
        
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            return readResponse(conn);
        }
        
        throw new IOException("Error al obtener información: " + responseCode);
    }

    /**
     * Registra esta computadora en el servidor
     */
    public String registerComputer(String name, String location) throws IOException {
        String url = serverUrl + "/api/network-print/computers";
        
        String hostname = NetworkUtils.getLocalHostname();
        
        String jsonPayload = String.format(
            "{\"macAddress\":\"%s\",\"name\":\"%s\",\"hostname\":\"%s\",\"location\":\"%s\"}",
            macAddress, name, hostname, location
        );
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        addAuthHeader(conn);
        
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        
        int responseCode = conn.getResponseCode();
        String response = readResponse(conn);
        
        if (responseCode == 200) {
            log.info("Computadora registrada exitosamente: {}", name);
            return response;
        } else {
            throw new IOException("Error al registrar computadora: " + response);
        }
    }

    private void addAuthHeader(HttpURLConnection conn) {
        if (authToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    public String getMacAddress() {
        return macAddress;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    /**
     * Ejemplo de uso del cliente
     */
    public static void main(String[] args) {
        try {
            // Crear cliente
            PrintClient client = new PrintClient("http://192.168.1.100:8080");
            
            System.out.println("=== CLIENTE DE IMPRESIÓN EN RED ===");
            System.out.println("MAC Address: " + client.getMacAddress());
            System.out.println("Hostname: " + NetworkUtils.getLocalHostname());
            System.out.println("IP Local: " + NetworkUtils.getLocalIpAddress());
            System.out.println();
            
            // Verificar autorización
            boolean authorized = client.isAuthorized();
            System.out.println("¿Autorizado?: " + authorized);
            
            if (authorized) {
                // Obtener impresoras asignadas
                System.out.println("\nImpresoras asignadas:");
                System.out.println(client.getAssignedPrinters());
                
                // Obtener estadísticas
                System.out.println("\nEstadísticas:");
                System.out.println(client.getStats());
                
                // Imprimir un documento
                // File doc = new File("documento.pdf");
                // String result = client.printDocument(1L, doc);
                // System.out.println("\nResultado de impresión:");
                // System.out.println(result);
            } else {
                System.out.println("\nComputadora no autorizada. Contacte al administrador.");
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
