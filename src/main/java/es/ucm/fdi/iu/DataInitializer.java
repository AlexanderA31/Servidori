package es.ucm.fdi.iu;

import es.ucm.fdi.iu.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initializes database with default data if needed
 */
@Component
@Order(1)
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LogManager.getLogger(DataInitializer.class);

    @PersistenceContext
    private EntityManager entityManager;
    
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        // Check if admin user exists
        Long userCount = entityManager.createQuery(
            "SELECT COUNT(u) FROM User u WHERE u.username = :username", Long.class)
            .setParameter("username", "admin")
            .getSingleResult();

        if (userCount == 0) {
            log.info("Creating default admin user...");
            
            String rawPassword = "admin123";
            String encodedPassword = passwordEncoder.encode(rawPassword);
            
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(encodedPassword);
            admin.setEnabled(true);
            admin.setRoles("ADMIN,USER");
            
            log.info("Admin password encoded: {}", encodedPassword);
            
            entityManager.persist(admin);
            entityManager.flush();
            
            // Verify the password works
            boolean passwordWorks = passwordEncoder.matches(rawPassword, encodedPassword);
            log.info("Password verification: {}", passwordWorks ? "SUCCESS" : "FAILED");
            
            log.info("Admin user created successfully");
            log.info("  Username: admin");
            log.info("  Password: admin123");
            log.info("  Encoded: {}", encodedPassword);
        } else {
            log.info("Admin user already exists, skipping initialization");
            
            // Log existing user info
            try {
                User existingAdmin = entityManager.createQuery(
                    "SELECT u FROM User u WHERE u.username = :username", User.class)
                    .setParameter("username", "admin")
                    .getSingleResult();
                
                log.info("Existing admin user details:");
                log.info("  ID: {}", existingAdmin.getId());
                log.info("  Username: {}", existingAdmin.getUsername());
                log.info("  Enabled: {}", existingAdmin.isEnabled());
                log.info("  Password: {}", existingAdmin.getPassword());
                log.info("  Roles: {}", existingAdmin.getRoles());
            } catch (Exception e) {
                log.error("Error retrieving existing admin user", e);
            }
        }
    }
}
