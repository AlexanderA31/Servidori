package es.ucm.fdi.iu.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Representa una computadora autorizada en la red identificada por su dirección MAC.
 * Solo las computadoras registradas pueden conectarse y usar las impresoras asignadas.
 */
@Entity
@Data
@NoArgsConstructor
@NamedQueries({
    @NamedQuery(name="Computer.byMacAddress",
            query="SELECT c FROM Computer c WHERE c.macAddress = :macAddress"),
    @NamedQuery(name="Computer.existsByMac",
            query="SELECT COUNT(c) FROM Computer c WHERE c.macAddress = :macAddress")
})
public class Computer implements Transferable<Computer.Transfer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private long id;

    @ManyToOne
    private User instance;

    /**
     * Dirección MAC de la computadora (identificador único)
     * Formato: XX:XX:XX:XX:XX:XX
     * Este es el identificador de autenticación de la computadora
     */
    @Column(unique = true, nullable = false, length = 17)
    private String macAddress;

    /**
     * Nombre descriptivo de la computadora
     */
    @Column(nullable = false)
    private String name;

    /**
     * Hostname de la computadora en la red
     */
    private String hostname;

    /**
     * Ubicación física de la computadora
     */
    private String location;

    /**
     * Indica si la computadora está autorizada para conectarse
     */
    private boolean authorized;

    /**
     * Última fecha/hora de conexión
     */
    private LocalDateTime lastConnection;

    /**
     * Departamento al que pertenece esta computadora
     */
    @ManyToOne
    @JoinColumn(name = "department_id")
    private Department department;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Transfer {
        private long id;
        private String macAddress;
        private String name;
        private String hostname;
        private String location;
        private boolean authorized;
        private LocalDateTime lastConnection;
        private String departmentName;
        private Long departmentId;
    }

    @Override
    public Transfer toTransfer() {
        return new Transfer(
            id, 
            macAddress, 
            name, 
            hostname, 
            location, 
            authorized, 
            lastConnection,
            department != null ? department.getName() : "Sin departamento",
            department != null ? department.getId() : null
        );
    }

    /**
     * Normaliza la dirección MAC al formato estándar XX:XX:XX:XX:XX:XX
     */
    public void normalizeMacAddress() {
        if (macAddress != null) {
            // Eliminar espacios, guiones y puntos
            String cleaned = macAddress.replaceAll("[\\s.:-]", "");
            // Convertir a mayúsculas
            cleaned = cleaned.toUpperCase();
            // Agregar : cada 2 caracteres
            if (cleaned.length() == 12) {
                StringBuilder formatted = new StringBuilder();
                for (int i = 0; i < cleaned.length(); i += 2) {
                    if (i > 0) formatted.append(":");
                    formatted.append(cleaned.substring(i, i + 2));
                }
                macAddress = formatted.toString();
            }
        }
    }

    @PrePersist
    @PreUpdate
    private void beforeSave() {
        normalizeMacAddress();
    }
}
