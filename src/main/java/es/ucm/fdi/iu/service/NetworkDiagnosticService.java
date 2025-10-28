package es.ucm.fdi.iu.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Servicio de diagnóstico de conectividad de red
 * Útil para verificar si hay enrutamiento entre VLANs diferentes
 */
@Service
@Slf4j
public class NetworkDiagnosticService {

    /**
     * Diagnostica la conectividad hacia una red específica
     */
    public NetworkDiagnosticResult diagnoseNetwork(String cidrRange) {
        NetworkDiagnosticResult result = new NetworkDiagnosticResult();
        result.setNetworkRange(cidrRange);
        result.setStartTime(System.currentTimeMillis());
        
        log.info("🔍 Iniciando diagnóstico de red: {}", cidrRange);
        
        try {
            // 1. Obtener información de la red local
            result.setLocalIP(getLocalIPAddress());
            result.setLocalNetwork(getLocalNetwork());
            result.setLocalGateway(getDefaultGateway());
            
            // 2. Verificar si la red objetivo es diferente a la local
            result.setSameNetwork(isInSameNetwork(result.getLocalIP(), cidrRange));
            
            // 3. Probar conectividad a la puerta de enlace de la red objetivo
            String targetGateway = getNetworkGateway(cidrRange);
            result.setTargetGateway(targetGateway);
            result.setGatewayReachable(testConnectivity(targetGateway, 80, 1000));
            
            // 4. Escanear algunas IPs de muestra en la red objetivo
            List<String> sampleIPs = getSampleIPs(cidrRange, 5);
            int reachableCount = 0;
            
            for (String ip : sampleIPs) {
                // Probar múltiples métodos
                boolean reachable = testConnectivity(ip, 80, 500) || 
                                  testConnectivity(ip, 445, 500) ||
                                  testConnectivity(ip, 631, 500);
                if (reachable) reachableCount++;
            }
            
            result.setTestedHosts(sampleIPs.size());
            result.setReachableHosts(reachableCount);
            
            // 5. Verificar si hay ruta hacia la red
            result.setHasRoute(hasRouteToNetwork(cidrRange));
            
            // 6. Generar recomendaciones
            result.setRecommendations(generateRecommendations(result));
            
        } catch (Exception e) {
            log.error("Error durante diagnóstico: {}", e.getMessage());
            result.setError(e.getMessage());
        }
        
        result.setEndTime(System.currentTimeMillis());
        result.setDurationMs(result.getEndTime() - result.getStartTime());
        
        log.info("✅ Diagnóstico completado en {}ms", result.getDurationMs());
        return result;
    }
    
    /**
     * Obtiene la IP local del servidor
     */
    private String getLocalIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error obteniendo IP local: {}", e.getMessage());
        }
        return "desconocida";
    }
    
    /**
     * Obtiene la red local en formato CIDR
     */
    private String getLocalNetwork() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    InetAddress inetAddr = addr.getAddress();
                    if (inetAddr instanceof Inet4Address && !inetAddr.isLoopbackAddress()) {
                        short prefix = addr.getNetworkPrefixLength();
                        return inetAddr.getHostAddress() + "/" + prefix;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error obteniendo red local: {}", e.getMessage());
        }
        return "desconocida";
    }
    
    /**
     * Intenta obtener la puerta de enlace predeterminada
     * NOTA: En Java no hay API nativa, usamos heurística
     */
    private String getDefaultGateway() {
        try {
            String localIP = getLocalIPAddress();
            if (!localIP.equals("desconocida")) {
                String[] parts = localIP.split("\\.");
                // Asumir que el gateway es .1 en la red
                return parts[0] + "." + parts[1] + "." + parts[2] + ".1";
            }
        } catch (Exception e) {
            log.error("Error obteniendo gateway: {}", e.getMessage());
        }
        return "desconocida";
    }
    
    /**
     * Obtiene la IP del gateway de una red (asume .1)
     */
    private String getNetworkGateway(String cidr) {
        try {
            String[] parts = cidr.split("/")[0].split("\\.");
            return parts[0] + "." + parts[1] + "." + parts[2] + ".1";
        } catch (Exception e) {
            return "desconocida";
        }
    }
    
    /**
     * Verifica si una IP local está en la misma red que un CIDR
     */
    private boolean isInSameNetwork(String ip, String cidr) {
        try {
            String[] cidrParts = cidr.split("/");
            String networkIP = cidrParts[0];
            int prefix = Integer.parseInt(cidrParts[1]);
            
            long ipValue = ipToLong(ip);
            long networkValue = ipToLong(networkIP);
            long mask = (-1L << (32 - prefix)) & 0xFFFFFFFFL;
            
            return (ipValue & mask) == (networkValue & mask);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Verifica si hay ruta hacia una red
     */
    private boolean hasRouteToNetwork(String cidr) {
        // Probar conectividad al gateway de la red
        String gateway = getNetworkGateway(cidr);
        return testConnectivity(gateway, 80, 1000) || 
               testConnectivity(gateway, 443, 1000);
    }
    
    /**
     * Prueba conectividad a un host:puerto
     */
    private boolean testConnectivity(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Obtiene algunas IPs de muestra de un rango CIDR
     */
    private List<String> getSampleIPs(String cidr, int count) {
        List<String> samples = new ArrayList<>();
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
            int totalHosts = broadcast - network - 1;
            
            // Muestrear IPs distribuidas uniformemente
            int step = Math.max(1, totalHosts / count);
            
            for (int i = 0; i < count && (network + 1 + (i * step)) < broadcast; i++) {
                int ipInt = network + 1 + (i * step);
                String ip = String.format("%d.%d.%d.%d",
                    (ipInt >> 24) & 0xFF,
                    (ipInt >> 16) & 0xFF,
                    (ipInt >> 8) & 0xFF,
                    ipInt & 0xFF);
                samples.add(ip);
            }
        } catch (Exception e) {
            log.error("Error generando IPs de muestra: {}", e.getMessage());
        }
        return samples;
    }
    
    /**
     * Convierte IP a long
     */
    private long ipToLong(String ip) {
        String[] octets = ip.split("\\.");
        return (Long.parseLong(octets[0]) << 24) +
               (Long.parseLong(octets[1]) << 16) +
               (Long.parseLong(octets[2]) << 8) +
               Long.parseLong(octets[3]);
    }
    
    /**
     * Genera recomendaciones basadas en los resultados
     */
    private List<String> generateRecommendations(NetworkDiagnosticResult result) {
        List<String> recommendations = new ArrayList<>();
        
        if (result.isSameNetwork()) {
            recommendations.add("✅ Estás en la misma red. El escaneo debería funcionar sin problemas.");
        } else {
            recommendations.add("⚠️ Estás en una red diferente (posible VLAN).");
            
            if (!result.isGatewayReachable()) {
                recommendations.add("❌ No se puede alcanzar el gateway de la red objetivo.");
                recommendations.add("💡 Verifica que tu router/switch tenga configurado el enrutamiento entre VLANs.");
                recommendations.add("💡 Comandos útiles:");
                recommendations.add("   - Windows: route print");
                recommendations.add("   - Linux: ip route show");
            } else {
                recommendations.add("✅ El gateway es alcanzable.");
            }
            
            if (!result.isHasRoute()) {
                recommendations.add("❌ No hay ruta configurada hacia la red " + result.getNetworkRange());
                recommendations.add("💡 Opciones:");
                recommendations.add("   1. Agregar ruta estática en tu computadora");
                recommendations.add("   2. Configurar enrutamiento en el router/switch");
                recommendations.add("   3. Usar un router con soporte multi-VLAN");
            }
            
            if (result.getReachableHosts() == 0) {
                recommendations.add("❌ No se detectaron hosts activos en la red objetivo.");
                recommendations.add("💡 Posibles causas:");
                recommendations.add("   - Firewall bloqueando el tráfico entre VLANs");
                recommendations.add("   - Hosts apagados o en modo de ahorro de energía");
                recommendations.add("   - SNMP deshabilitado en las impresoras");
                recommendations.add("💡 Soluciones:");
                recommendations.add("   1. Habilita SNMP en las impresoras (comunidad: public)");
                recommendations.add("   2. Configura reglas de firewall para permitir:");
                recommendations.add("      - SNMP (UDP 161)");
                recommendations.add("      - IPP (TCP 631)");
                recommendations.add("      - SMB (TCP 445)");
            } else {
                recommendations.add("✅ Se detectaron " + result.getReachableHosts() + " hosts activos.");
                recommendations.add("💡 El escaneo debería funcionar con estos hosts.");
            }
        }
        
        return recommendations;
    }
    
    /**
     * Clase de resultado de diagnóstico
     */
    public static class NetworkDiagnosticResult {
        private String networkRange;
        private String localIP;
        private String localNetwork;
        private String localGateway;
        private String targetGateway;
        private boolean sameNetwork;
        private boolean gatewayReachable;
        private boolean hasRoute;
        private int testedHosts;
        private int reachableHosts;
        private List<String> recommendations;
        private String error;
        private long startTime;
        private long endTime;
        private long durationMs;
        
        // Getters y Setters
        public String getNetworkRange() { return networkRange; }
        public void setNetworkRange(String networkRange) { this.networkRange = networkRange; }
        
        public String getLocalIP() { return localIP; }
        public void setLocalIP(String localIP) { this.localIP = localIP; }
        
        public String getLocalNetwork() { return localNetwork; }
        public void setLocalNetwork(String localNetwork) { this.localNetwork = localNetwork; }
        
        public String getLocalGateway() { return localGateway; }
        public void setLocalGateway(String localGateway) { this.localGateway = localGateway; }
        
        public String getTargetGateway() { return targetGateway; }
        public void setTargetGateway(String targetGateway) { this.targetGateway = targetGateway; }
        
        public boolean isSameNetwork() { return sameNetwork; }
        public void setSameNetwork(boolean sameNetwork) { this.sameNetwork = sameNetwork; }
        
        public boolean isGatewayReachable() { return gatewayReachable; }
        public void setGatewayReachable(boolean gatewayReachable) { this.gatewayReachable = gatewayReachable; }
        
        public boolean isHasRoute() { return hasRoute; }
        public void setHasRoute(boolean hasRoute) { this.hasRoute = hasRoute; }
        
        public int getTestedHosts() { return testedHosts; }
        public void setTestedHosts(int testedHosts) { this.testedHosts = testedHosts; }
        
        public int getReachableHosts() { return reachableHosts; }
        public void setReachableHosts(int reachableHosts) { this.reachableHosts = reachableHosts; }
        
        public List<String> getRecommendations() { return recommendations; }
        public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
        
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    }
}
