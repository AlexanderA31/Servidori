package es.ucm.fdi.iu;

import java.io.IOException;

import jakarta.persistence.EntityManager;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import es.ucm.fdi.iu.model.User;

/**
 * Called when a user is first authenticated (via login).
 * Called from SecurityConfig; see https://stackoverflow.com/a/53353324
 * 
 * Adds a "u" variable to the session when a user is first authenticated.
 * Important: the user is retrieved from the database, but is not refreshed at each request. 
 * You should refresh the user's information if anything important changes; for example, after
 * updating the user's profile.
 */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    @Autowired 
    private HttpSession session;
    
    @Autowired
    private EntityManager entityManager;    
    
	private static Logger log = LogManager.getLogger(LoginSuccessHandler.class);
	
        /**
     * Called whenever a user authenticates correctly.
     * Redirige según el rol del usuario:
     * - ADMIN → /admin/
     * - USER → /home
     */
    @Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {
	    log.info("=== LOGIN SUCCESS ===");
	    String username = ((org.springframework.security.core.userdetails.User)
				authentication.getPrincipal()).getUsername();
	    
	    log.info("User authenticated successfully: {}", username);
	    log.info("Session ID: {}", session.getId());
	    log.info("Authorities: {}", authentication.getAuthorities());
	    
	    // add a 'u' session variable, accessible from thymeleaf via ${session.u}
	    log.info("Storing user info for {} in session {}", username, session.getId());
	    
	    User u = null;
		try {
			u = entityManager.createNamedQuery("User.byUsername", User.class)
			        .setParameter("username", username)
			        .getSingleResult();		
			session.setAttribute("u", u);
			log.info("User object stored in session successfully");
		} catch (Exception e) {
			log.error("Error storing user in session", e);
		}

				// Redirigir según el rol del usuario
		String redirectUrl = "/admin/"; // Por defecto al admin (todos los usuarios pueden acceder)
		
		if (u != null && u.hasRole(User.Role.ADMIN)) {
			log.info("Redirecting ADMIN user to: {}", redirectUrl);
		} else {
			log.info("Redirecting USER to: {}", redirectUrl);
		}
		
		response.sendRedirect(redirectUrl);
	}
}
