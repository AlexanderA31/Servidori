package es.ucm.fdi.iu.model;

import javax.persistence.*;
import lombok.*;

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
public class Job implements Transferable<Job.Transfer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
	private long id;
    @ManyToOne
    private User instance;
    @ManyToOne
    private Printer printer;
    private String owner;
    private String fileName;

    @Getter
    @AllArgsConstructor
    public static class Transfer {
        private long id;
        private long printer;
        private String owner;
        private String fileName;
    }

    @Override
    public Transfer toTransfer() {
        return new Job.Transfer(id, printer.getId(), owner, fileName);
    }
}
