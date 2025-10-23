package es.ucm.fdi.iu.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.*;
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

    @OneToMany(mappedBy = "printer", orphanRemoval = true)
    private List<Job> queue = new ArrayList<>();
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
    public static class Transfer {
        private long id;
        private String alias;
        private String model;
        private String location;
        private String ip;
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
                id, alias, model, location, ip, gs, qs, currentStatus());
    }
}
