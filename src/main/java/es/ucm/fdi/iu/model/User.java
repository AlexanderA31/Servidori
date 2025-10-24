package es.ucm.fdi.iu.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An authorized user of the system.
 */
@Entity
@Table(name = "user_table")
@Data
@NoArgsConstructor
@NamedQueries({
        @NamedQuery(name="User.byUsername",
                query="SELECT u FROM User u "
                        + "WHERE u.username = :username AND u.enabled = true"),
        @NamedQuery(name="User.hasUsername",
                query="SELECT COUNT(u) "
                        + "FROM User u "
                        + "WHERE u.username = :username")
})
public class User implements Transferable<User.AdminTransfer> {

    public enum Role {
        USER,			// used for logged-in, non-priviledged users
        ADMIN,			// used for setup, user-management
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gen")
    @SequenceGenerator(name = "gen", sequenceName = "gen")
	private long id;

    @Column(nullable = false)
    private String username;
    @Column(nullable = false)
    private String password;

    private boolean enabled;
    private String roles; // split by ',' to separate roles

    @OneToMany(mappedBy = "user", orphanRemoval = true)
    private List<Token> tokens = new ArrayList<>();

    @OneToMany(mappedBy = "instance", orphanRemoval = true)
    private List<Job> jobs = new ArrayList<>();
    @OneToMany(mappedBy = "instance", orphanRemoval = true)
    private List<Printer> printers = new ArrayList<>();
    @OneToMany(mappedBy = "instance", orphanRemoval = true)
    private List<PGroup> groups = new ArrayList<>();

    /**
     * Checks whether this user has a given role.
     * @param role to check
     * @return true iff this user has that role.
     */
    public boolean hasRole(Role role) {
        String roleName = role.name();
        return Arrays.stream(roles.split(","))
                .anyMatch(r -> r.equals(roleName));
    }

        @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AdminTransfer {
        private long id;
        private String username;
        private boolean enabled;
        private int tokens;
        private int jobs;
        private int printers;
        private int groups;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Transfer {
        private String token;
        private List<Job.Transfer> jobs;
        private List<Printer.Transfer> printers;
        private List<PGroup.Transfer> groups;
    }

    @Override
    public AdminTransfer toTransfer() {
        return new AdminTransfer(id, username, enabled,
                tokens.size(), jobs.size(), printers.size(), groups.size());
    }

    public Transfer toTransfer(String token) {
        return new Transfer(token,
                jobs.stream().map(Transferable::toTransfer).collect(Collectors.toList()),
                printers.stream().map(Transferable::toTransfer).collect(Collectors.toList()),
                groups.stream().map(Transferable::toTransfer).collect(Collectors.toList())
        );
    }
}

