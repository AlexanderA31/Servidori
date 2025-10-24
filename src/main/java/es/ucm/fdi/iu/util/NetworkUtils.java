package es.ucm.fdi.iu.util;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * Utilidades para obtener información de red de la computadora local
 */
@Slf4j
public class NetworkUtils {

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
     * Obtiene la dirección IP local
     */
    public static String getLocalIpAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.error("Error al obtener IP: {}", e.getMessage());
            return "0.0.0.0";
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
