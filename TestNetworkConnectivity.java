import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;

/**
 * Script de diagnóstico para verificar conectividad a la red 172.18.0.0/22
 * Ejecutar desde el servidor 10.1.16.31
 * 
 * Compilar: javac TestNetworkConnectivity.java
 * Ejecutar: java TestNetworkConnectivity
 */
public class TestNetworkConnectivity {

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("DIAGNÓSTICO DE CONECTIVIDAD A RED 172.18.0.0/22");
        System.out.println("==============================================\n");
        
        // IPs de prueba en la red 172.18.0.0/22
        String[] testIPs = {
            "172.18.0.1",   // Gateway
            "172.18.0.10",
            "172.18.0.20",
            "172.18.1.1",
            "172.18.1.10"
        };
        
        System.out.println("1. VERIFICANDO ALCANZABILIDAD (ICMP):");
        System.out.println("--------------------------------------");
        for (String ip : testIPs) {
            testReachability(ip);
        }
        
        System.out.println("\n2. VERIFICANDO PUERTOS DE IMPRESORAS:");
        System.out.println("--------------------------------------");
        for (String ip : testIPs) {
            testPrinterPorts(ip);
        }
        
        System.out.println("\n3. VERIFICANDO SNMP (puerto 161):");
        System.out.println("--------------------------------------");
        for (String ip : testIPs) {
            testSNMP(ip);
        }
        
        System.out.println("\n4. VERIFICANDO RUTA DE RED:");
        System.out.println("--------------------------------------");
        getLocalInterfaces();
        testRouting();
        
        System.out.println("\n==============================================");
        System.out.println("DIAGNÓSTICO COMPLETADO");
        System.out.println("==============================================");
    }
    
    /**
     * Prueba ICMP (ping)
     */
    private static void testReachability(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            boolean reachable = address.isReachable(2000);
            System.out.printf("  [%s] %s - %s%n", 
                reachable ? "✓" : "✗", 
                ip, 
                reachable ? "ALCANZABLE" : "NO ALCANZABLE");
        } catch (IOException e) {
            System.out.printf("  [✗] %s - ERROR: %s%n", ip, e.getMessage());
        }
    }
    
    /**
     * Prueba puertos de impresoras (9100, 631, 515)
     */
    private static void testPrinterPorts(String ip) {
        int[] ports = {9100, 631, 515, 161}; // RAW, IPP, LPD, SNMP
        String[] protocols = {"RAW", "IPP", "LPD", "SNMP"};
        
        StringBuilder result = new StringBuilder("  " + ip + " -> ");
        boolean foundOpen = false;
        
        for (int i = 0; i < ports.length; i++) {
            if (isPortOpen(ip, ports[i], 1000)) {
                result.append(protocols[i]).append(":").append(ports[i]).append(" ✓  ");
                foundOpen = true;
            }
        }
        
        if (!foundOpen) {
            result.append("Ningún puerto abierto");
        }
        
        System.out.println(result.toString());
    }
    
    /**
     * Prueba SNMP específicamente
     */
    private static void testSNMP(String ip) {
        if (isPortOpen(ip, 161, 1000)) {
            System.out.printf("  [✓] %s - Puerto SNMP 161 ABIERTO%n", ip);
        } else {
            System.out.printf("  [✗] %s - Puerto SNMP 161 CERRADO/FILTRADO%n", ip);
        }
    }
    
    /**
     * Verifica si un puerto TCP está abierto
     */
    private static boolean isPortOpen(String ip, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeout);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Obtiene interfaces de red locales
     */
    private static void getLocalInterfaces() {
        System.out.println("Interfaces de red del servidor:");
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isUp() && !ni.isLoopback()) {
                    System.out.printf("  - %s (%s)%n", ni.getName(), ni.getDisplayName());
                    java.util.Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            System.out.printf("    IP: %s%n", addr.getHostAddress());
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.out.println("  ERROR: " + e.getMessage());
        }
    }
    
    /**
     * Verifica routing
     */
    private static void testRouting() {
        System.out.println("\nVerificando gateway hacia 172.18.0.1:");
        try {
            InetAddress target = InetAddress.getByName("172.18.0.1");
            System.out.println("  Target: " + target.getHostAddress());
            System.out.println("  Hostname: " + target.getHostName());
            
            // Intentar resolución DNS inversa
            System.out.println("  Canonical: " + target.getCanonicalHostName());
            
        } catch (UnknownHostException e) {
            System.out.println("  ERROR: " + e.getMessage());
        }
    }
    
    /**
     * Escaneo paralelo rápido de un rango
     */
    public static void scanRange(String baseIP, int startHost, int endHost) {
        System.out.println("\nESCANEANDO RANGO: " + baseIP + "." + startHost + "-" + endHost);
        System.out.println("----------------------------------------");
        
        ExecutorService executor = Executors.newFixedThreadPool(50);
        
        for (int i = startHost; i <= endHost; i++) {
            final String ip = baseIP + "." + i;
            executor.submit(() -> {
                // Primero intentar puertos
                for (int port : new int[]{9100, 631, 515, 161}) {
                    if (isPortOpen(ip, port, 500)) {
                        System.out.printf("[ENCONTRADO] %s puerto %d abierto%n", ip, port);
                    }
                }
            });
        }
        
        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        System.out.println("Escaneo completado");
    }
}
