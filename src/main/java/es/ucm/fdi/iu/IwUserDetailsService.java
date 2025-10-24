package es.ucm.fdi.iu;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import es.ucm.fdi.iu.model.User;

public class IwUserDetailsService implements UserDetailsService {

	private static Logger log = LogManager.getLogger(IwUserDetailsService.class);

    private EntityManager entityManager;
    
    @PersistenceContext
    public void setEntityManager(EntityManager em){
        this.entityManager = em;
    }

        public UserDetails loadUserByUsername(String username){
    	log.info("=== AUTHENTICATION ATTEMPT ===");
    	log.info("Attempting to load user: {}", username);
    	try {
	        User u = entityManager.createNamedQuery("User.byUsername", User.class)
                    .setParameter("username", username)
                    .getSingleResult();
	        
	        log.info("User found in database:");
	        log.info("  - ID: {}", u.getId());
	        log.info("  - Username: {}", u.getUsername());
	        log.info("  - Enabled: {}", u.isEnabled());
	        log.info("  - Password (encrypted): {}", u.getPassword());
	        log.info("  - Roles: {}", u.getRoles());
	        
	        // build UserDetails object
	        ArrayList<SimpleGrantedAuthority> roles = new ArrayList<>();
	        for (String r : u.getRoles().split("[,]")) {
	        	roles.add(new SimpleGrantedAuthority("ROLE_" + r));
		        log.info("Added role: ROLE_{}", r);
	        }
	        
	        log.info("UserDetails object created with {} roles", roles.size());
	        return new org.springframework.security.core.userdetails.User(
	        		u.getUsername(), u.getPassword(), roles); 
	    } catch (Exception e) {
    		log.error("Failed to find user: {}", username, e);
    		log.error("Error type: {}", e.getClass().getName());
    		log.error("Error message: {}", e.getMessage());
    		throw new UsernameNotFoundException(username);
    	}
    }
}