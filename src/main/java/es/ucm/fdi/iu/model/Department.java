package es.ucm.fdi.iu.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Departamento o Grupo
 * Contiene computadoras e impresoras.
 * Las computadoras del grupo solo pueden imprimir en las impresoras del mismo grupo.
 */
@Entity
@Data
@NoArgsConstructor
@NamedQueries({
    @NamedQuery(name="Department.all",
            query="SELECT d FROM Department d"),
    @NamedQuery(name="Department.byName",
            query="SELECT d FROM Department d WHERE d.name = :name"),
    @NamedQuery(name="Department.byComputerMac",
            query="SELECT d FROM Department d JOIN d.computers c WHERE c.macAddress = :macAddress")
})
public class Department implements Transferable<Department.Transfer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    private long id;

    @ManyToOne
    private User instance;

    /**
     * Nombre del departamento/grupo (ej: "Ventas", "Diseño", "Administración")
     */
    @Column(nullable = false, unique = true)
    private String name;

    /**
     * Descripción del departamento
     */
    private String description;

    /**
     * Ubicación física
     */
    private String location;

    /**
     * Edificio donde se encuentra el departamento
     */
    private String building;

    /**
     * Color para identificación visual (hex)
     */
    private String color;

    /**
     * Computadoras que pertenecen a este departamento
     */
    @OneToMany(mappedBy = "department", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Computer> computers = new ArrayList<>();

    /**
     * Impresoras asignadas a este departamento
     */
    @ManyToMany
    @JoinTable(
        name = "department_printer",
        joinColumns = @JoinColumn(name = "department_id"),
        inverseJoinColumns = @JoinColumn(name = "printer_id")
    )
    private List<Printer> printers = new ArrayList<>();

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Transfer {
        private long id;
        private String name;
        private String description;
        private String location;
        private String building;
        private String color;
        private int totalComputers;
        private int totalPrinters;
        private List<ComputerInfo> computers;
        private List<PrinterInfo> printers;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ComputerInfo {
        private long id;
        private String macAddress;
        private String name;
        private boolean authorized;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PrinterInfo {
        private long id;
        private String alias;
        private String ip;
        private String model;
    }

    @Override
    public Transfer toTransfer() {
        List<ComputerInfo> compList = computers.stream()
                .map(c -> new ComputerInfo(c.getId(), c.getMacAddress(), c.getName(), c.isAuthorized()))
                .collect(Collectors.toList());
        
        List<PrinterInfo> printList = printers.stream()
                .map(p -> new PrinterInfo(p.getId(), p.getAlias(), p.getIp(), p.getModel()))
                .collect(Collectors.toList());
        
        return new Transfer(
            id, 
            name, 
            description, 
            location,
            building,
            color,
            computers.size(),
            printers.size(),
            compList,
            printList
        );
    }
}
