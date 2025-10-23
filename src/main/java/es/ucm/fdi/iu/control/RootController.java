package es.ucm.fdi.iu.control;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.persistence.EntityManager;

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

    @GetMapping("/login")
    public String login(Model model) {
        return "login";
    }
}
