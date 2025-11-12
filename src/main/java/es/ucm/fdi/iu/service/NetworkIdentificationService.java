package es.ucm.fdi.iu.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio para identificar dispositivos de red de forma única
 * 
 * Funcionalidades:
 * - Obtener MAC Address de una IP
 * - Verificar identidad de dispositivos aunque cambien de IP
 */
@Service
@Slf4j
public class NetworkIdentificationService {

    /**
     * Obtiene la MAC Address de una IP usando ARP
     * 
     * IMPORTANTE: Esto funciona SOLO si:
     * 1. La IP está en la misma red local (no enrutada)
     * 2. El dispositivo ha respondido recientemente (está en caché ARP)
     * 
     * @param ip Dirección IP del dispositivo
     * @return MAC Address en formato AA:BB:CC:DD:EE:FF o null si no se encuentra
     */
    public String getMacAddressFromIP(String ip) {
        try {
            // Primero hacer ping para asegurar que esté en caché ARP
            InetAddress address = InetAddress.getByName(ip);
            boolean reachable = address.isReachable(2000);
            
            if (!reachable) {
                log.debug("IP {} no es alcanzable, no se puede obtener MAC", ip);
                return null;
            }
            
            // Detectar sistema operativo
            String os = System.getProperty("os.name").toLowerCase();
            
            String command;
            if (os.contains("win")) {
                // Windows: arp -a
                command = "arp -a " + ip;
            } else {
                // Linux/Unix: arp -n
                command = "arp -n " + ip;
            }
            
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            String line;
            Pattern macPattern = Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})");
            
            while ((line = reader.readLine()) != null) {
                Matcher matcher = macPattern.matcher(line);
                if (matcher.find()) {
                    String mac = matcher.group();
                    // Normalizar formato a AA:BB:CC:DD:EE:FF
                    mac = mac.toUpperCase().replace("-", ":");
                    log.debug("MAC Address obtenida para {}: {}", ip, mac);
                    return mac;
                }
            }
            
            reader.close();
            process.waitFor();
            
        } catch (Exception e) {
            log.error("Error obteniendo MAC Address de {}: {}", ip, e.getMessage());
        }
        
        return null;
    }

    /**
     * Busca una IP por MAC Address en la red local
     * Escanea la tabla ARP del sistema
     * 
     * @param macAddress MAC a buscar (formato AA:BB:CC:DD:EE:FF o aa-bb-cc-dd-ee-ff)
     * @return IP encontrada o null
     */
    public String findIPByMacAddress(String macAddress) {
        try {
            // Normalizar MAC para comparación
            String normalizedMac = macAddress.toUpperCase().replace("-", ":");
            
            String os = System.getProperty("os.name").toLowerCase();
            String command = os.contains("win") ? "arp -a" : "arp -n";
            
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            String line;
            Pattern ipPattern = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");
            Pattern macPattern = Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})");
            
            while ((line = reader.readLine()) != null) {
                Matcher macMatcher = macPattern.matcher(line);
                if (macMatcher.find()) {
                    String foundMac = macMatcher.group().toUpperCase().replace("-", ":");
                    
                    if (foundMac.equals(normalizedMac)) {
                        // Encontrada la MAC, extraer IP de la misma línea
                        Matcher ipMatcher = ipPattern.matcher(line);
                        if (ipMatcher.find()) {
                            String foundIp = ipMatcher.group();
                            log.info("✓ IP encontrada para MAC {}: {}", macAddress, foundIp);
                            return foundIp;
                        }
                    }
                }
            }
            
            reader.close();
            process.waitFor();
            
        } catch (Exception e) {
            log.error("Error buscando IP por MAC {}: {}", macAddress, e.getMessage());
        }
        
        return null;
    }

    /**
     * Verifica si una IP corresponde a una MAC específica
     * Útil para confirmar que una IP encontrada es realmente la impresora correcta
     */
    public boolean verifyIPMatchesMAC(String ip, String expectedMac) {
        String actualMac = getMacAddressFromIP(ip);
        
        if (actualMac == null || expectedMac == null) {
            return false;
        }
        
        // Normalizar ambas para comparación
        String normalizedActual = actualMac.toUpperCase().replace("-", ":");
        String normalizedExpected = expectedMac.toUpperCase().replace("-", ":");
        
        boolean matches = normalizedActual.equals(normalizedExpected);
        
        if (matches) {
            log.info("✓ IP {} confirmada para MAC {}", ip, expectedMac);
        } else {
            log.warn("✗ IP {} NO corresponde a MAC {} (encontrada: {})", ip, expectedMac, actualMac);
        }
        
        return matches;
    }

    /**
     * Valida formato de MAC Address
     */
    public boolean isValidMacAddress(String mac) {
        if (mac == null || mac.isEmpty()) {
            return false;
        }
        
        // Formatos válidos: AA:BB:CC:DD:EE:FF o AA-BB-CC-DD-EE-FF
        Pattern pattern = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");
        return pattern.matcher(mac).matches();
    }
}
