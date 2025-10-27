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
 * A printer.
 *
 * printers have a unique alias, a model, a location, an IP,
 * a print-queue with jobs, and a state.
 * the state can be printing (because queue not empty and not blocked), paused
 * (because queue empty and not blocked), out of paper, or out of ink
 * (the last two mean that it is blocked, and thus neither printing or paused)
 */
@Entity
@Data
@NoArgsConstructor
public class Printer implements Transferable<Printer.Transfer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
	private long id;
    @ManyToOne
    private User instance;

        @ManyToMany(mappedBy = "printers")
    private List<PGroup> groups = new ArrayList<>();

        private String alias;
    private String model;
    private String location;
    private String ip;
    
    // URI del dispositivo (opcional, manual override)
    // Ej: ipp://192.168.1.100/ipp/print, socket://192.168.1.100:9100
    private String deviceUri;
    
        // Puerto de conexión a la impresora física (por defecto: 9100 para RAW, 631 para IPP, 515 para LPD)
    private Integer port;
    
    // Puerto IPP dedicado del servidor (8631, 8632, 8633, etc.)
    // Este es el puerto donde los clientes se conectan a ESTE servidor
    private Integer ippPort;
    
    // Protocolo de comunicación: RAW, IPP, LPD, SMB
    // RAW = Puerto 9100 (más común y rápido)
    // IPP = Internet Printing Protocol (puerto 631)
    // LPD = Line Printer Daemon (puerto 515)
    // SMB = Samba/Windows Share
    private String protocol;
    
        // @Deprecated - Ya no se usan CUPS/Samba nativos
    @Deprecated
    private String driver;
    
    // Compartido vía Samba para clientes Windows
    private boolean sharedViaSamba = false;
    
    @Deprecated
    private boolean addedToCups = false;
    @Deprecated
    private String sambaShareName;

            @OneToMany(mappedBy = "printer", orphanRemoval = true)
    private List<Job> queue = new ArrayList<>();
    
    @ManyToMany(mappedBy = "printers")
    private List<Department> departments = new ArrayList<>();
    
    private int ink;
    private int paper;

    public enum Status {
        PRINTING,
        NO_INK,
        NO_PAPER,
        PAUSED
    }

    public Status currentStatus() {
        if (paper == 0) return Status.NO_PAPER;
        if (ink == 0) return Status.NO_INK;
        if (queue.isEmpty()) return Status.PAUSED;
        return Status.PRINTING;
    }

                    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Transfer {
        private long id;
        private String alias;
        private String model;
        private String location;
        private String ip;
        private String deviceUri;
        private String driver;
        private Integer port;
        private Integer ippPort;
        private String protocol;
        private boolean sharedViaSamba;
        private boolean addedToCups;
        private String sambaShareName;
        private List<Long> groups;
        private List<Long> queue;
        private Status status;
    }

            @Override
    public Transfer toTransfer() {
        List<Long> gs = groups.stream().map(PGroup::getId)
                .collect(Collectors.toList());
        List<Long> qs = queue.stream().map(Job::getId)
                .collect(Collectors.toList());
        return new Transfer(
                id, alias, model, location, ip, deviceUri, driver, port, ippPort, protocol,
                sharedViaSamba, addedToCups, sambaShareName, gs, qs, currentStatus());
    }
}
