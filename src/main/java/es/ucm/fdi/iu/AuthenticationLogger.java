package es.ucm.fdi.iu;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Logs authentication failures with detailed information
 */
@Component
public class AuthenticationLogger extends SimpleUrlAuthenticationFailureHandler {
    
    private static final Logger log = LogManager.getLogger(AuthenticationLogger.class);
    
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                       AuthenticationException exception) throws IOException, jakarta.servlet.ServletException {
        log.error("=== AUTHENTICATION FAILED ===" );
        log.error("Username attempted: {}", request.getParameter("username"));
        log.error("Password provided: {} characters", 
            request.getParameter("password") != null ? request.getParameter("password").length() : 0);
        log.error("Exception type: {}", exception.getClass().getName());
        log.error("Exception message: {}", exception.getMessage());
        log.error("Remote address: {}", request.getRemoteAddr());
        log.error("Session ID: {}", request.getSession().getId());
        
        if (exception.getCause() != null) {
            log.error("Caused by: {}", exception.getCause().getMessage());
        }
        
        super.setDefaultFailureUrl("/login?error");
        super.onAuthenticationFailure(request, response, exception);
    }
}
