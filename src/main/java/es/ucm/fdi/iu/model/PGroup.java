package es.ucm.fdi.iu.model;

import lombok.*;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A group of printers.
 *
 * Allows managing of groups of printers as a whole. A single printer
 * may be part of several groups.
 */
@Entity
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PGroup implements Transferable<PGroup.Transfer> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
    @EqualsAndHashCode.Include
	private long id;
    @ManyToOne
    private User instance;

    private String name;

    @ManyToMany
    private List<Printer> printers = new ArrayList<>();

        @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Transfer {
        private long id;
        private String name;
        private List<Long> printers;
    }

    @Override
    public Transfer toTransfer() {
        List<Long> ps = printers.stream().map(Printer::getId)
            .collect(Collectors.toList());
        return new Transfer(id, name, ps);
    }
}
