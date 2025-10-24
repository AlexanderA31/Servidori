package es.ucm.fdi.iu.control;

import es.ucm.fdi.iu.model.PGroup;
import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.model.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpSession;

/**
 *  Allows user management, and generating random values for
 *  users.
 *
 *  Access to this end-point is authenticated.
 */
@Controller
public class RootController {

	private static final Logger log = LogManager.getLogger(RootController.class);

	@Autowired
	private EntityManager entityManager;

        @GetMapping("/")
    public String index(Model model, HttpSession session) {
        User currentUser = (User) session.getAttribute("u");
        
        if (currentUser != null && currentUser.hasRole(User.Role.ADMIN)) {
            // Si es admin, redirigir al panel de administración
            return "redirect:/admin/";
        }
        
        // Si no está logueado, mostrar página de login
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login(Model model) {
        return "login";
    }
}
