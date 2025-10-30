package es.ucm.fdi.iu;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tests password encoding and validation
 * This component runs at startup to verify password configuration
 * 
 * Only loads in server mode (NOT in usb-client)
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.mode", havingValue = "server", matchIfMissing = true)
public class PasswordTester implements CommandLineRunner {

    private static final Logger log = LogManager.getLogger(PasswordTester.class);

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== PASSWORD ENCODER TEST ===" );
        
        String rawPassword = "admin123";
        
        // Generate the correct encoded password
        log.info("Generating new encoded password for: {}", rawPassword);
        String newEncoded = passwordEncoder.encode(rawPassword);
        log.info("New encoded password: {}", newEncoded);
        log.info("Copy this password to update your database!");
        
        // Test the newly generated password
        boolean testMatches = passwordEncoder.matches(rawPassword, newEncoded);
        log.info("Test validation: {}", testMatches ? "SUCCESS" : "FAILED");
    }
}
