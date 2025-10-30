package es.ucm.fdi.iu.control;

import es.ucm.fdi.iu.model.PGroup;
import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.model.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 *  Allows user management, and generating random values for
 *  users.
 *
 *  Access to this end-point is authenticated.
 */
@Controller
@ConditionalOnProperty(name = "app.mode", havingValue = "server", matchIfMissing = true)
public class RootController {

	private static final Logger log = LogManager.getLogger(RootController.class);

	@Autowired
	private EntityManager entityManager;

                @GetMapping("/")
    public String index(Model model, HttpSession session) {
        // Simplemente servir el index.html estático (ya tiene todo lo necesario)
        return "forward:/index.html";
    }

    @GetMapping("/login")
    public String login(Model model) {
        return "login";
    }
}
