package es.ucm.fdi.iu.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * Utilidades para obtener información de red de la computadora local
 */
@Slf4j
@Component
public class NetworkUtils {
    
    private static String configuredServerDomain;
    
    @Value("${app.server.domain:}")
    public void setServerDomain(String domain) {
        configuredServerDomain = domain;
        if (domain != null && !domain.isEmpty()) {
            log.info("⚙️ Dominio del servidor configurado: {}", domain);
        }
    }
    
    /**
     * Obtiene el host del servidor (dominio o IP)
     * Prioriza el dominio configurado sobre la detección automática de IP
     */
    public static String getServerHost() {
        // Si hay un dominio configurado, usarlo
        if (configuredServerDomain != null && !configuredServerDomain.isEmpty()) {
            // Extraer solo el host sin http:// o https://
            String host = configuredServerDomain
                .replace("http://", "")
                .replace("https://", "")
                .split("/")[0]; // Quitar cualquier path
            return host;
        }
        
        // Fallback: detectar IP automáticamente
        log.warn("⚠️ No hay dominio configurado (app.server.domain), usando IP detectada");
        return getServerIpAddress();
    }

    /**
     * Obtiene la dirección MAC de la computadora local
     * @return dirección MAC en formato XX:XX:XX:XX:XX:XX
     */
    public static String getLocalMacAddress() {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            NetworkInterface ni = NetworkInterface.getByInetAddress(localHost);
            
            if (ni != null) {
                byte[] hardwareAddress = ni.getHardwareAddress();
                if (hardwareAddress != null) {
                    return formatMacAddress(hardwareAddress);
                }
            }
            
            // Intento alternativo: buscar la primera interfaz de red activa
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface netInterface = networkInterfaces.nextElement();
                if (!netInterface.isLoopback() && netInterface.isUp()) {
                    byte[] mac = netInterface.getHardwareAddress();
                    if (mac != null && mac.length == 6) {
                        return formatMacAddress(mac);
                    }
                }
            }
            
        } catch (UnknownHostException | SocketException e) {
            log.error("Error al obtener MAC address: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * Obtiene todas las direcciones MAC disponibles en el sistema
     */
    public static String[] getAllMacAddresses() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            java.util.List<String> macAddresses = new java.util.ArrayList<>();
            
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface netInterface = networkInterfaces.nextElement();
                byte[] mac = netInterface.getHardwareAddress();
                
                if (mac != null && mac.length == 6) {
                    macAddresses.add(formatMacAddress(mac));
                }
            }
            
            return macAddresses.toArray(new String[0]);
            
        } catch (SocketException e) {
            log.error("Error al obtener direcciones MAC: {}", e.getMessage());
            return new String[0];
        }
    }

    /**
     * Formatea un array de bytes de MAC address al formato estándar
     */
    private static String formatMacAddress(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X", mac[i]));
            if (i < mac.length - 1) {
                sb.append(":");
            }
        }
        return sb.toString();
    }

    /**
     * Obtiene el hostname de la computadora local
     */
    public static String getLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("Error al obtener hostname: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * Obtiene la dirección IP local (método antiguo - puede devolver 127.0.x.x)
     * @deprecated Usar getServerIpAddress() en su lugar
     */
    @Deprecated
    public static String getLocalIpAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.error("Error al obtener IP: {}", e.getMessage());
            return "0.0.0.0";
        }
    }

    /**
     * Obtiene la dirección IP real del servidor (evita 127.0.x.x)
     * Prioriza interfaces Ethernet sobre WiFi
     */
    public static String getServerIpAddress() {
        try {
            // Primero intentar obtener IP no-loopback de todas las interfaces
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            
            // Lista de IPs encontradas, priorizando ethX sobre wlanX
            String firstEthernet = null;
            String firstWireless = null;
            String firstOther = null;
            
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface netInterface = networkInterfaces.nextElement();
                
                // Saltar interfaces down o loopback
                if (!netInterface.isUp() || netInterface.isLoopback()) {
                    continue;
                }
                
                Enumeration<InetAddress> inetAddresses = netInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    
                    // Solo IPv4, no loopback, no link-local
                    if (!inetAddress.isLoopbackAddress() && 
                        !inetAddress.isLinkLocalAddress() && 
                        inetAddress.getAddress().length == 4) {
                        
                        String ip = inetAddress.getHostAddress();
                        String interfaceName = netInterface.getName().toLowerCase();
                        
                        // Priorizar Ethernet (eth, ens, enp)
                        if (interfaceName.startsWith("eth") || 
                            interfaceName.startsWith("ens") || 
                            interfaceName.startsWith("enp")) {
                            if (firstEthernet == null) {
                                firstEthernet = ip;
                            }
                        }
                        // WiFi (wlan, wlp)
                        else if (interfaceName.startsWith("wlan") || 
                                 interfaceName.startsWith("wlp")) {
                            if (firstWireless == null) {
                                firstWireless = ip;
                            }
                        }
                        // Otras interfaces
                        else {
                            if (firstOther == null) {
                                firstOther = ip;
                            }
                        }
                    }
                }
            }
            
            // Devolver la mejor IP encontrada
            if (firstEthernet != null) {
                log.info("IP del servidor detectada (Ethernet): {}", firstEthernet);
                return firstEthernet;
            }
            if (firstWireless != null) {
                log.info("IP del servidor detectada (WiFi): {}", firstWireless);
                return firstWireless;
            }
            if (firstOther != null) {
                log.info("IP del servidor detectada (Otra interfaz): {}", firstOther);
                return firstOther;
            }
            
            // Fallback al método tradicional
            String fallbackIp = InetAddress.getLocalHost().getHostAddress();
            log.warn("No se encontró IP de red, usando fallback: {}", fallbackIp);
            return fallbackIp;
            
        } catch (Exception e) {
            log.error("Error al obtener IP del servidor: {}", e.getMessage());
            return "localhost";
        }
    }

    /**
     * Valida formato de dirección MAC
     */
    public static boolean isValidMacAddress(String mac) {
        if (mac == null) return false;
        
        // Soporta formatos: XX:XX:XX:XX:XX:XX, XX-XX-XX-XX-XX-XX, XXXXXXXXXXXX
        String pattern = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$|^[0-9A-Fa-f]{12}$";
        return mac.matches(pattern);
    }

    /**
     * Normaliza una dirección MAC al formato estándar
     */
    public static String normalizeMacAddress(String mac) {
        if (mac == null) return null;
        
        String cleaned = mac.replaceAll("[\\s.:-]", "").toUpperCase();
        
        if (cleaned.length() == 12) {
            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < cleaned.length(); i += 2) {
                if (i > 0) formatted.append(":");
                formatted.append(cleaned.substring(i, i + 2));
            }
            return formatted.toString();
        }
        
        return mac.toUpperCase();
    }
}
