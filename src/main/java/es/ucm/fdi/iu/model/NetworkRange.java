package es.ucm.fdi.iu.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Representa un rango de red (VLAN) para escanear en busca de impresoras
 */
@Entity
@Data
@NoArgsConstructor
@NamedQueries({
    @NamedQuery(name="NetworkRange.all",
            query="SELECT n FROM NetworkRange n ORDER BY n.name"),
    @NamedQuery(name="NetworkRange.active",
            query="SELECT n FROM NetworkRange n WHERE n.active = true ORDER BY n.name")
})
public class NetworkRange implements Transferable<NetworkRange.Transfer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private long id;

    @ManyToOne
    private User instance;

    /**
     * Nombre descriptivo de la red (ej: "VLAN Producción", "Red Principal")
     */
    @Column(nullable = false)
    private String name;

    /**
     * Rango de red en notación CIDR (ej: "192.168.1.0/24")
     */
    @Column(nullable = false)
    private String cidrRange;

    /**
     * Descripción de la red/VLAN
     */
    private String description;

    /**
     * VLAN ID (opcional)
     */
    private Integer vlanId;

    /**
     * Si está activa para escaneo
     */
    private boolean active;

    /**
     * Última vez que se escaneó esta red
     */
    private java.time.LocalDateTime lastScan;

    /**
     * Número de impresoras encontradas en último escaneo
     */
    private int lastFoundPrinters;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Transfer {
        private long id;
        private String name;
        private String cidrRange;
        private String description;
        private Integer vlanId;
        private boolean active;
        private java.time.LocalDateTime lastScan;
        private int lastFoundPrinters;
        private int estimatedHosts;
    }

    @Override
    public Transfer toTransfer() {
        int hosts = calculateHosts(cidrRange);
        return new Transfer(
            id, 
            name, 
            cidrRange, 
            description, 
            vlanId, 
            active,
            lastScan,
            lastFoundPrinters,
            hosts
        );
    }

    /**
     * Calcula el número de hosts en un rango CIDR
     */
    private int calculateHosts(String cidr) {
        try {
            String[] parts = cidr.split("/");
            int prefix = Integer.parseInt(parts[1]);
            return (int) Math.pow(2, 32 - prefix) - 2; // -2 para red y broadcast
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Valida si el formato CIDR es correcto
     */
    public static boolean isValidCIDR(String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) return false;

            // Validar IP
            String[] octets = parts[0].split("\\.");
            if (octets.length != 4) return false;
            
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) return false;
            }

            // Validar prefijo
            int prefix = Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= 32;
        } catch (Exception e) {
            return false;
        }
    }
}
