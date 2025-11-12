package es.ucm.fdi.iu.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

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
     * Obtiene MAC Address de una impresora via SNMP
     * Útil para impresoras en redes enrutadas (diferentes VLANs)
     * 
     * OIDs comunes para impresoras:
     * - 1.3.6.1.2.1.2.2.1.6.1 = ifPhysAddress (MAC de la interfaz de red)
     * - 1.3.6.1.2.1.43.5.1.1.17.1 = prtGeneralSerialNumber (puede contener MAC)
     * 
     * @param ip Dirección IP de la impresora
     * @param community Community string SNMP (por defecto "public")
     * @return MAC Address en formato AA:BB:CC:DD:EE:FF o null
     */
    public String getMacAddressViaSNMP(String ip, String community) {
        if (community == null || community.isEmpty()) {
            community = "public";
        }
        
        Snmp snmp = null;
        try {
            log.debug("🔍 Intentando SNMP para {} con community '{}'", ip, community);
            
            // Crear transporte UDP
            DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping();
            transport.listen();
            
            // Crear objeto SNMP
            snmp = new Snmp(transport);
            
            // Configurar target (impresora)
            Address targetAddress = GenericAddress.parse("udp:" + ip + "/161");
            CommunityTarget target = new CommunityTarget();
            target.setCommunity(new OctetString(community));
            target.setAddress(targetAddress);
            target.setRetries(2);
            target.setTimeout(3000); // 3 segundos
            target.setVersion(SnmpConstants.version2c);
            
            // OID para MAC address (ifPhysAddress de la primera interfaz)
            OID oid = new OID("1.3.6.1.2.1.2.2.1.6.1");
            
            // Crear PDU (Protocol Data Unit)
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(oid));
            pdu.setType(PDU.GET);
            
            // Enviar request
            ResponseEvent response = snmp.send(pdu, target);
            
            if (response != null && response.getResponse() != null) {
                PDU responsePDU = response.getResponse();
                
                if (responsePDU.getErrorStatus() == PDU.noError) {
                    VariableBinding vb = responsePDU.get(0);
                    
                    if (vb != null && vb.getVariable() != null) {
                        // Convertir bytes a MAC address
                        byte[] macBytes = vb.getVariable().toBytes();
                        
                        if (macBytes != null && macBytes.length == 6) {
                            StringBuilder macAddress = new StringBuilder();
                            for (int i = 0; i < macBytes.length; i++) {
                                if (i > 0) macAddress.append(":");
                                macAddress.append(String.format("%02X", macBytes[i] & 0xFF));
                            }
                            
                            String mac = macAddress.toString();
                            log.info("✅ MAC obtenida via SNMP para {}: {}", ip, mac);
                            return mac;
                        } else {
                            log.warn("⚠️ Respuesta SNMP no contiene MAC válida para {}", ip);
                        }
                    }
                } else {
                    log.warn("⚠️ Error SNMP para {}: {}", ip, responsePDU.getErrorStatusText());
                }
            } else {
                log.warn("⚠️ Sin respuesta SNMP de {} (timeout o puerto 161 cerrado)", ip);
            }
            
        } catch (Exception e) {
            log.debug("❌ Error SNMP para {}: {}", ip, e.getMessage());
        } finally {
            if (snmp != null) {
                try {
                    snmp.close();
                } catch (Exception e) {
                    log.debug("Error cerrando SNMP: {}", e.getMessage());
                }
            }
        }
        
        return null;
    }
    
    /**
     * Obtiene MAC Address intentando múltiples métodos:
     * 1. ARP (para dispositivos en la misma red)
     * 2. SNMP con community "public"
     * 3. SNMP con community "private"
     * 
     * @param ip Dirección IP del dispositivo
     * @return MAC Address o null si no se puede obtener
     */
    public String getMacAddressMultiMethod(String ip) {
        log.debug("🔍 Iniciando captura de MAC para {} con múltiples métodos", ip);
        
        // Método 1: ARP (rápido, solo funciona en misma red)
        String mac = getMacAddressFromIP(ip);
        if (mac != null) {
            log.info("✅ MAC obtenida via ARP: {}", mac);
            return mac;
        }
        
        // Método 2: SNMP con community "public"
        mac = getMacAddressViaSNMP(ip, "public");
        if (mac != null) {
            log.info("✅ MAC obtenida via SNMP (public): {}", mac);
            return mac;
        }
        
        // Método 3: SNMP con community "private"
        mac = getMacAddressViaSNMP(ip, "private");
        if (mac != null) {
            log.info("✅ MAC obtenida via SNMP (private): {}", mac);
            return mac;
        }
        
        log.warn("❌ No se pudo obtener MAC para {} con ningún método", ip);
        return null;
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
