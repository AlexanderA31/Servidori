package es.ucm.fdi.iu.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import es.ucm.fdi.iu.model.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * General API manager.
 * No authentication is needed, but valid token prefixes are required for all
 * operations except "login", which itself requires valid username & password.
 * Most operations return the requesting user's full view of the system.
 * Note that users can typically not view other user's data.
 * 
 * Only loads in server mode (NOT in usb-client)
 */
@RestController
@CrossOrigin
@RequestMapping("api")
@ConditionalOnProperty(name = "app.mode", havingValue = "server", matchIfMissing = true)
public class ApiController {

    private static final Logger log = LogManager.getLogger(AdminController.class);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Environment env;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity handleException(ApiException e) {
        // log exception
        return ResponseEntity
                .status(e instanceof ApiAuthException ?
                        HttpStatus.FORBIDDEN :
                        HttpStatus.BAD_REQUEST)
                .body(e.getMessage());
    }

    @ResponseStatus(value=HttpStatus.BAD_REQUEST, reason="Invalid request")  // 401
    public static class ApiException extends RuntimeException {
        public ApiException(String text, Throwable cause) {
            super(text, cause);
            if (cause != null) {
                log.warn(text, cause);
            } else {
                log.info(text);
            }
        }
    }

    @ResponseStatus(value=HttpStatus.FORBIDDEN, reason="Not authorized")  // 403
    public static class ApiAuthException extends ApiException {
        public ApiAuthException(String text) {
            super(text, null);
            log.info(text);
        }
    }

    private Token resolveTokenOrBail(String tokenKey) {
        List<Token> results = entityManager.createQuery(
                "from Token where key = :key", Token.class)
                .setParameter("key", tokenKey)
                .getResultList();
        if ( ! results.isEmpty()) {
            return results.get(0);
        } else {
            throw new ApiException("Invalid token", null);
        }
    }

    /**
     * Returns true if a given string can be parsed as a Long
     */
    private static boolean canParseAsLong(String s) {
        try {
            Long.valueOf(s);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    /**
     * Tries to take and validate a field from a JsonNode
     */
    private static String check(boolean mandatory, JsonNode source, String fieldName,
                              Predicate<String> validTest, String ifInvalid, Consumer<String> ifValid) {
        if (source.has(fieldName)) {
            String s = source.get(fieldName).asText();
            if (validTest.test(s)) {
                if (ifValid != null) ifValid.accept(s);
                return s;
            } else {
                throw new ApiException("While validating " + fieldName + ": " + ifInvalid, null);
            }
        } else if (mandatory) {
            throw new ApiException("Field " + fieldName + " MUST be present, but was missing", null);
        } else {
            return null;
        }
    }

    private static String checkOptional(JsonNode source, String fieldName,
          Predicate<String> validTest, String ifInvalid, Consumer<String> ifValid) {
        return check(false, source, fieldName, validTest, ifInvalid, ifValid);
    }
    private static String checkMandatory(JsonNode source, String fieldName,
          Predicate<String> validTest, String ifInvalid, Consumer<String> ifValid) {
        return check(true, source, fieldName, validTest, ifInvalid, ifValid);
    }

    // see https://stackoverflow.com/a/15875500/15472:
    // 3x (0 to 255, non-0-prefixed, dot-separated) + 1x 0-255, non-0-prefixed
    private static final String IP_V4_ADDRESS_REGEX =
            "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
            "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";

    /**
     * Validates an IP address
     * @param ip
     * @return false if malformed or invalid single-machine destination
     */
    private static boolean isValidIp(String ip) {

        Pattern pattern = Pattern.compile(IP_V4_ADDRESS_REGEX);
        Matcher matcher = pattern.matcher(ip);
        if ( ! matcher.find()) return false;

        // incomplete - see https://en.wikipedia.org/w/index.php?title=IPv4&section=6
        if (ip.startsWith("0.0.0.")                 // only valid as source
                || ip.equals("255.255.255.255")     // broadcast
                || ip.startsWith("127.0.0.")        // loopback
            ) return false;
        return true;
    }

    /**
     * Generates random tokens. From https://stackoverflow.com/a/44227131/15472
     * @param byteLength
     * @return
     */
    public static String generateRandomBase64Token(int byteLength) {
        SecureRandom secureRandom = new SecureRandom();
        byte[] token = new byte[byteLength];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token); //base64 encoding
    }

    /**
     * Logs out, essentially invalidating an existing token.
     */
    @PostMapping("/{token}/logout")
    @Transactional
    public void logout(
            @PathVariable String token) {
        log.info(token + "/logout");
        Token t = resolveTokenOrBail(token);
        entityManager.remove(t);
    }


    /**
     * Requests a token from the system. Provides a user to do so, for which only the
     * password and username are looked at
     * @param data attempting to log in.
     * @throws JsonProcessingException
     */
    @PostMapping("/login")
    @Transactional
    public User.Transfer login(
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info("/login/" + new ObjectMapper().writeValueAsString(data));

        String username = checkMandatory(data, "username",
                d->!d.isEmpty(), "cannot be empty", null);
        String password = checkMandatory(data, "password",
                d->!d.isEmpty(), "cannot be empty", null);

        List<User> results = entityManager.createQuery(
                "from User where username = :username", User.class)
                .setParameter("username", username)
                .getResultList();
        // only expecting one, because uid is unique
        User u = results.isEmpty() ? null : results.get(0);

        if (u == null ||
              (! passwordEncoder.matches(password, u.getPassword()) &&
               ! password.equals(env.getProperty("es.ucm.fdi.master-key")))) {
            throw new ApiAuthException("Invalid username or password");
        }

        Token token = new Token();
        token.setUser(u);
        token.setKey(generateRandomBase64Token(6));
        entityManager.persist(token);
        return u.toTransfer(token.getKey());
    }


    @PostMapping("/{token}/adduser")
    @Transactional
    public List<User.AdminTransfer> addUser(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/adduser/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! u.hasRole(User.Role.ADMIN)) {
            throw new ApiException("Only admins can do this", null);
        }

        User o = new User();
        o.setEnabled(true);
        o.setRoles("" + User.Role.USER);
        checkMandatory(data, "username",
                d -> !d.isEmpty(), "cannot be empty",
                o::setUsername);
        checkMandatory(data, "password",
                d->!d.isEmpty(), "cannot be empty",
                d->o.setPassword(passwordEncoder.encode(d)));
        entityManager.persist(o);
        entityManager.flush();
        return generateUserList();
    }

    @PostMapping("/{token}/setuser")
    @Transactional
    public List<User.AdminTransfer> setUser(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/setuser/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! u.hasRole(User.Role.ADMIN)) {
            throw new ApiException("Only admins can do this", null);
        }
        if ( ! data.has("id") || ! data.get("id").canConvertToLong()) {
            throw new ApiException("No ID for user to set: " + data.get("id"), null);
        }
        User o = entityManager.find(User.class, data.get("id").asLong());
        if (o == null) {
            throw new ApiException("No such user: " + data.get("id"), null);
        }

        checkOptional(data, "enabled",
                d->("true".equals(d) || "false".equals(d)), "must be 'true' or 'false'",
                d->o.setEnabled("true".equals(d)));
        checkOptional(data, "username",
                d->!d.isEmpty(), "cannot be empty",
                o::setUsername);
        checkOptional(data, "password",
                d->!d.isEmpty(), "cannot be empty",
                d->o.setPassword(passwordEncoder.encode(d)));
        entityManager.flush();
        return generateUserList();
    }


    @PostMapping("/{token}/rmuser")
    @Transactional
    public List<User.AdminTransfer> rmUser(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/rmuser/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! u.hasRole(User.Role.ADMIN)) {
            throw new ApiException("Only admins can do this", null);
        }
        if ( ! data.has("id") || ! data.get("id").canConvertToLong()) {
            throw new ApiException("No ID for user to remove: " + data.get("id"), null);
        }
        User o = entityManager.find(User.class, data.get("id").asLong());
        if (o == null) {
            throw new ApiException("No such user: " + data.get("id"), null);
        }

        entityManager.remove(o);
        entityManager.flush();
        return generateUserList();
    }



    @PostMapping("/{token}/addprinter")
    @Transactional
    public User.Transfer addPrinter(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/addprinter/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        Printer p = new Printer();
        p.setInstance(u);

        checkMandatory(data, "alias",
                d->!d.isEmpty(), "cannot be empty",
                p::setAlias);
        checkOptional(data, "model",
                d->!d.isEmpty(), "cannot be empty",
                p::setModel);
        checkOptional(data, "location",
                d->!d.isEmpty(), "cannot be empty",
                p::setLocation);
        checkOptional(data, "ip",
                ApiController::isValidIp, "is not a valid IP",
                p::setIp);
        if (data.has("queue") && data.get("queue").isArray()) {
            List<Job> nextJobs = new ArrayList<>();
            Iterator<JsonNode> it = data.get("queue").elements();
            while (it.hasNext()) {
                long id = it.next().asLong();
                Job j = entityManager.find(Job.class, id);
                if (j == null || j.getInstance().getId() != u.getId()) {
                    throw new ApiException("No such job: " + id, null);
                }
                j.setPrinter(p);
                nextJobs.add(j);
            }
            p.getQueue().clear();
            p.getQueue().addAll(nextJobs);
        }

        if (data.has("status")) {
            String statusText = data.get("status").asText().toUpperCase();
            try {
                switch (Printer.Status.valueOf(statusText)) {
                    case NO_INK:
                        p.setInk(0); break;
                    case NO_PAPER:
                        p.setPaper(0); break;
                    case PRINTING:
                    case PAUSED:
                        p.setInk(1);
                        p.setPaper(1); break;
                }
            } catch (IllegalArgumentException iae) {
                throw new ApiException("not a valid status: " + statusText, iae);
            }
        }

        if (data.has("groups") && data.get("groups").isArray()) {
            Iterator<JsonNode> it = data.get("groups").elements();
            while (it.hasNext()) {
                long id = it.next().asLong();
                PGroup g = entityManager.find(PGroup.class, id);
                if (g == null || g.getInstance().getId() != u.getId()) {
                    throw new ApiException("No such group: " + id, null);
                }
                if ( ! p.getGroups().contains(g)) {
                    p.getGroups().add(g);
                    g.getPrinters().add(p);
                }
            }
        }

                // Asignar puerto IPP único y dedicado
        Integer maxPort = entityManager.createQuery(
            "SELECT MAX(p.ippPort) FROM Printer p", Integer.class)
            .getSingleResult();
        int nextPort = (maxPort != null) ? maxPort + 1 : 8631;
        p.setIppPort(nextPort);
        log.info("Puerto IPP {} asignado a impresora {}", nextPort, p.getAlias());
        
        entityManager.persist(p);
        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/setprinter")
    @Transactional
    public User.Transfer setPrinter(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/setprinter/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! data.has("id") || ! data.get("id").canConvertToLong()) {
            throw new ApiException("No ID for printer to set: " + data.get("id"), null);
        }

        Printer p = entityManager.find(Printer.class, data.get("id").asLong());
        if (p == null || p.getInstance().getId() != u.getId()) {
            throw new ApiException("No such printer: " + data.get("id"), null);
        }
        checkOptional(data, "alias",
                d->!d.isEmpty(), "cannot be empty",
                p::setAlias);
        checkOptional(data, "model",
                d->!d.isEmpty(), "cannot be empty",
                p::setModel);
        checkOptional(data, "location",
                d->!d.isEmpty(), "cannot be empty",
                p::setLocation);
        checkOptional(data, "ip",
                ApiController::isValidIp, "is not a valid IP",
                p::setIp);

        if (data.has("queue") && data.get("queue").isArray()) {
            List<Job> nextJobs = new ArrayList<>();
            Iterator<JsonNode> it = data.get("queue").elements();
            while (it.hasNext()) {
                long id = it.next().asLong();
                Job j = entityManager.find(Job.class, id);
                if (j == null || j.getInstance().getId() != u.getId()) {
                    throw new ApiException("No such job: " + id, null);
                }
                j.setPrinter(p);
                nextJobs.add(j);
            }
            p.getQueue().clear();
            p.getQueue().addAll(nextJobs);
        }

        if (data.has("groups") && data.get("groups").isArray()) {
            Set<PGroup> nextGroups = new HashSet<>();
            Iterator<JsonNode> it = data.get("groups").elements();
            while (it.hasNext()) {
                long id = it.next().asLong();
                PGroup g = entityManager.find(PGroup.class, id);
                if (g == null || g.getInstance().getId() != u.getId()) {
                    throw new ApiException("No such group: " + id, null);
                }
                nextGroups.add(g);
            }

            // remove from groups where it was before, but is now no longer
            Set<PGroup> groupsToRemoveFrom = new HashSet<>(p.getGroups());
            groupsToRemoveFrom.removeAll(nextGroups);

            // add to groups where it should be, but was not there before
            Set<PGroup> groupsToAddTo = new HashSet<>(nextGroups);
            groupsToAddTo.removeAll(p.getGroups());

            for (PGroup g: groupsToRemoveFrom) {
                p.getGroups().remove(g);
                g.getPrinters().remove(p);
            }
            for (PGroup g: groupsToAddTo) {
                p.getGroups().add(g);
                g.getPrinters().add(p);
            }
        }

        if (data.has("status")) {
            String statusText = data.get("status").asText().toUpperCase();
            try {
                switch (Printer.Status.valueOf(statusText)) {
                    case NO_INK:
                        p.setInk(0); break;
                    case NO_PAPER:
                        p.setPaper(0); break;
                    case PRINTING:
                    case PAUSED:
                        p.setInk(1);
                        p.setPaper(1); break;
                }
            } catch (IllegalArgumentException iae) {
                throw new ApiException("not a valid status: " + statusText, iae);
            }
        }

        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/rmprinter")
    @Transactional
    public User.Transfer rmPrinter(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/rmprinter/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! data.has("id") || ! data.get("id").canConvertToLong()) {
            throw new ApiException("No ID for printer to remove: " + data.get("id"), null);
        }

        Printer p = entityManager.find(Printer.class, data.get("id").asLong());
        if (p == null || p.getInstance().getId() != u.getId()) {
            throw new ApiException("No such printer: " + data.get("id"), null);
        }
        for (PGroup g : p.getGroups()) {
            g.getPrinters().remove(p);
        }
        entityManager.remove(p);
        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/addgroup")
    @Transactional
    public User.Transfer addGroup(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/addgroup/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        PGroup g = new PGroup();
        g.setInstance(u);

        checkMandatory(data, "name",
                d->!d.isEmpty(), "cannot be empty",
                g::setName);
        if (data.has("printers") && data.get("printers").isArray()) {
            List<Printer> nextPrinters = new ArrayList<>();
            Iterator<JsonNode> it = data.get("printers").elements();
            while (it.hasNext()) {
                long id = it.next().asLong();
                Printer p = entityManager.find(Printer.class, id);
                if (p == null || p.getInstance().getId() != u.getId()) {
                    throw new ApiException("No such printer: " + id, null);
                }
                nextPrinters.add(p);
            }
            g.getPrinters().clear();
            g.getPrinters().addAll(nextPrinters);
        }

        entityManager.persist(g);
        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/setgroup")
    @Transactional
    public User.Transfer setGroup(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/setgroup/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! data.has("id") || ! data.get("id").canConvertToLong()) {
            throw new ApiException("No ID for group to set: " + data.get("id"), null);
        }

        PGroup g = entityManager.find(PGroup.class, data.get("id").asLong());
        if (g == null || g.getInstance().getId() != u.getId()) {
            throw new ApiException("No such group: " + data.get("id"), null);
        }
        checkMandatory(data, "name",
                d->!d.isEmpty(), "cannot be empty",
                g::setName);
        if (data.has("printers") && data.get("printers").isArray()) {
            List<Printer> nextPrinters = new ArrayList<>();
            Iterator<JsonNode> it = data.get("printers").elements();
            while (it.hasNext()) {
                long id = it.next().asLong();
                Printer p = entityManager.find(Printer.class, id);
                if (p == null || p.getInstance().getId() != u.getId()) {
                    throw new ApiException("No such printer: " + id, null);
                }
                nextPrinters.add(p);
            }
            g.getPrinters().clear();
            g.getPrinters().addAll(nextPrinters);
        }

        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/rmgroup")
    @Transactional
    public User.Transfer rmGroup(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/rmgroup/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! data.has("id") || ! data.get("id").canConvertToLong()) {
            throw new ApiException("No ID for group to remove: " + data.get("id"), null);
        }

        PGroup g = entityManager.find(PGroup.class, data.get("id").asLong());
        if (g == null || g.getInstance().getId() != u.getId()) {
            throw new ApiException("No such group: " + data.get("id"), null);
        }
        for (Printer p : g.getPrinters()) {
            p.getGroups().remove(p);
        }
        entityManager.remove(g);
        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/addjob")
    @Transactional
    public User.Transfer addJob(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/addjob/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        Job j = new Job();
        j.setInstance(u);

        checkMandatory(data, "fileName",
                d->d.endsWith(".pdf"), "cannot be empty or non-pdf",
                j::setFileName);
        String pid = checkMandatory(data, "printer",
                ApiController::canParseAsLong, "is not a valid ID",
                null);
        Printer p = entityManager.find(Printer.class, Long.parseLong(pid));
        if (p == null || p.getInstance().getId() != u.getId()) {
            throw new ApiException("No such printer: " + pid, null);
        }
        j.setPrinter(p);
        p.getQueue().add(j);

        checkMandatory(data, "owner",
                d->!d.isEmpty(), "cannot be empty",
                j::setOwner);
        entityManager.persist(j);
        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/setjob")
    @Transactional
    public User.Transfer setJob(
            @PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/setjob/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! data.has("id") || ! data.get("id").canConvertToLong()) {
            throw new ApiException("No ID for job to set: " + data.get("id"), null);
        }
        String jid = data.get("id").asText();
        Job j = entityManager.find(Job.class, Long.valueOf(jid));
        if (j == null || j.getInstance().getId() != u.getId()) {
            throw new ApiException("No such job: " + jid, null);
        }

        checkOptional(data, "fileName",
                d->d.endsWith(".pdf"), "cannot be empty or non-pdf",
                j::setFileName);
        String pid = checkOptional(data, "printer",
                ApiController::canParseAsLong, "is not a valid ID",
                null);
        if (pid != null) {
            Printer p = entityManager.find(Printer.class, Long.parseLong(pid));
            if (p == null || p.getInstance().getId() != u.getId()) {
                throw new ApiException("No such printer: " + pid, null);
            }
            j.setPrinter(p);
            p.getQueue().add(j);
        }

        checkOptional(data, "owner",
                d->!d.isEmpty(), "cannot be empty",
                j::setOwner);
        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/rmjob")
    @Transactional
    public User.Transfer rmJob(@PathVariable String token,
            @RequestBody JsonNode data) throws JsonProcessingException {
        log.info(token + "/rmjob/" + new ObjectMapper().writeValueAsString(data));
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! data.has("id") || ! data.get("id").canConvertToLong()) {
            throw new ApiException("No ID for job to set: " + data.get("id"), null);
        }
        String jid = data.get("id").asText();
        Job j = entityManager.find(Job.class, Long.valueOf(jid));
        if (j == null || j.getInstance().getId() != u.getId()) {
            throw new ApiException("No such job: " + jid, null);
        }

        entityManager.remove(j);
        entityManager.flush();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/list")
    public User.Transfer list(@PathVariable String token) {
        log.info(token + "/list");
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();
        return u.toTransfer(t.getKey());
    }

    @PostMapping("/{token}/ulist")
    public List<User.AdminTransfer> ulist(@PathVariable String token) {
        log.info(token + "/list");
        Token t = resolveTokenOrBail(token);
        User u = t.getUser();

        if ( ! u.hasRole(User.Role.ADMIN)) {
            throw new ApiException("Only admins can do this", null);
        }
        return generateUserList();
    }

        private List<User.AdminTransfer> generateUserList() {
        List<User.AdminTransfer> result = new ArrayList<>();
        for (User o : entityManager.createQuery(
                "SELECT u FROM User u", User.class).getResultList()) {
            result.add(o.toTransfer());
        }
        return result;
    }
    
            /**
     * Endpoint público para registrar impresoras compartidas automáticamente
     * desde scripts de clientes (sin autenticación)
     */
    @PostMapping("/register-shared-printer")
    @Transactional
    public Map<String, Object> registerSharedPrinter(@RequestBody Map<String, String> printerData) {
        Map<String, Object> response = new HashMap<>();
                try {
            // Buscar cualquier usuario administrador
            List<User> adminUsers = entityManager.createQuery(
                "SELECT u FROM User u WHERE u.roles LIKE '%ADMIN%'", User.class)
                .setMaxResults(1)
                .getResultList();
            
            User user = null;
            if (!adminUsers.isEmpty()) {
                user = adminUsers.get(0);
            } else {
                // Si no hay admin, buscar cualquier usuario
                List<User> anyUser = entityManager.createQuery(
                    "SELECT u FROM User u", User.class)
                    .setMaxResults(1)
                    .getResultList();
                if (!anyUser.isEmpty()) {
                    user = anyUser.get(0);
                }
            }
            
            if (user == null) {
                response.put("success", false);
                response.put("error", "No hay usuarios en el sistema");
                return response;
            }
            
            String alias = printerData.get("alias");
            String model = printerData.get("model");
            String ip = printerData.get("ip");
            String location = printerData.getOrDefault("location", "Computadora compartida");
            String protocol = printerData.getOrDefault("protocol", "IPP");
            Integer port = Integer.parseInt(printerData.getOrDefault("port", "631"));
            
            // Verificar si ya existe una impresora con esa IP
            List<Printer> existing = entityManager.createQuery(
                "SELECT p FROM Printer p WHERE p.ip = :ip", Printer.class)
                .setParameter("ip", ip)
                .getResultList();
            
            if (!existing.isEmpty()) {
                response.put("success", false);
                response.put("error", "Ya existe una impresora registrada con esa IP");
                response.put("existingPrinter", existing.get(0).getAlias());
                return response;
            }
            
                                                // Crear nueva impresora
            Printer printer = new Printer();
            printer.setAlias(alias);
            printer.setModel(model);
            printer.setLocation(location);
            printer.setIp(ip);
            printer.setProtocol(protocol);
            printer.setPort(port);
            
            // TODAS las impresoras (incluyendo USB compartidas) usan puerto 863X del servidor
            // El servidor actúa como intermediario:
            // - Cliente se conecta al servidor en puerto 863X
            // - Servidor detecta si es USB compartida y reenvía a cliente USB en puerto 631
            // - Si es impresora de red, la envía directamente a la impresora
            
            // Asignar puerto IPP único y dedicado para TODAS las impresoras
            Integer maxPort = entityManager.createQuery(
                "SELECT MAX(p.ippPort) FROM Printer p", Integer.class)
                .getSingleResult();
            int nextPort = (maxPort != null) ? maxPort + 1 : 8631;
            printer.setIppPort(nextPort);
            
            boolean isSharedUSB = location != null && location.contains("Compartida-USB");
            if (isSharedUSB) {
                log.info("✅ Impresora USB compartida registrada: {} con puerto IPP {} (servidor actúa como intermediario a {}:631)", 
                    alias, nextPort, ip);
            } else {
                log.info("✅ Impresora de red registrada: {} con puerto IPP {}", 
                    alias, nextPort);
            }
            
            printer.setDeviceUri("ipp://" + ip + ":" + port + "/printers/" + alias.replace(" ", "_"));
            printer.setInstance(user);
            printer.setInk(100);
            printer.setPaper(100);
            
            entityManager.persist(printer);
            entityManager.flush();
            
                        response.put("success", true);
            response.put("message", "Impresora registrada exitosamente");
            response.put("printerId", printer.getId());
            response.put("printerName", printer.getAlias());
            response.put("ippPort", nextPort);  // DEVOLVER EL PUERTO ASIGNADO
            response.put("serverIp", es.ucm.fdi.iu.util.NetworkUtils.getServerIpAddress());
            
            log.info("✅ Impresora compartida registrada automáticamente: {} (IP: {}, Puerto IPP: {})", alias, ip, nextPort);
            
        } catch (Exception e) {
            log.error("❌ Error registrando impresora compartida", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }
    
                /**
     * Endpoint público para descargar el script de compartir impresora Windows
     */
    @GetMapping("/download/share-windows-script")
    public ResponseEntity<String> downloadShareWindowsScript() {
        try {
            // Intentar desde el directorio del proyecto
            java.nio.file.Path scriptPath = java.nio.file.Paths.get("scripts/compartir-impresora-windows.bat");
            
            // Si no existe, intentar desde resources
            if (!java.nio.file.Files.exists(scriptPath)) {
                try {
                    java.io.InputStream is = getClass().getResourceAsStream("/scripts/compartir-impresora-windows.bat");
                    if (is != null) {
                        String script = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.info("Sirviendo script de compartir desde classpath ({} bytes)", script.length());
                        
                        return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=compartir-impresora-windows.bat")
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .body(script);
                    }
                } catch (Exception ex) {
                    log.debug("No se pudo cargar desde classpath: {}", ex.getMessage());
                }
                
                log.error("Script de compartir no encontrado");
                return ResponseEntity.notFound().build();
            }
            
            String script = new String(java.nio.file.Files.readAllBytes(scriptPath), java.nio.charset.StandardCharsets.UTF_8);
            log.info("Sirviendo script de compartir: {} bytes", script.length());
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=compartir-impresora-windows.bat")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("Error leyendo script de compartir", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
        /**
     * Endpoint público para descargar el cliente USB (JAR ejecutable)
     */
    @GetMapping("/download/usb-client")
    public ResponseEntity<byte[]> downloadUsbClient() {
        try {
            // El JAR se encuentra en target/ después de compilar
            java.nio.file.Path jarPath = java.nio.file.Paths.get("target/iu-0.0.1-SNAPSHOT.jar");
            
            if (!java.nio.file.Files.exists(jarPath)) {
                log.error("Cliente USB (JAR) no encontrado en: {}", jarPath.toAbsolutePath());
                return ResponseEntity.notFound().build();
            }
            
            byte[] jarBytes = java.nio.file.Files.readAllBytes(jarPath);
            log.info("Sirviendo cliente USB: {} bytes", jarBytes.length);
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=usb-client.jar")
                .header("Content-Type", "application/java-archive")
                .body(jarBytes);
        } catch (Exception e) {
            log.error("Error leyendo cliente USB (JAR)", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Endpoint público para descargar el script de compartir impresora Linux
     */
    @GetMapping("/download/share-linux-script")
    public ResponseEntity<String> downloadShareLinuxScript() {
        try {
            // Intentar desde el directorio del proyecto
            java.nio.file.Path scriptPath = java.nio.file.Paths.get("scripts/compartir-impresora-linux.sh");
            
            // Si no existe, intentar desde resources
            if (!java.nio.file.Files.exists(scriptPath)) {
                try {
                    java.io.InputStream is = getClass().getResourceAsStream("/scripts/compartir-impresora-linux.sh");
                    if (is != null) {
                        String script = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.info("Sirviendo script de compartir Linux desde classpath ({} bytes)", script.length());
                        
                        return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=compartir-impresora-linux.sh")
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .body(script);
                    }
                } catch (Exception ex) {
                    log.debug("No se pudo cargar desde classpath: {}", ex.getMessage());
                }
                
                log.error("Script de compartir Linux no encontrado");
                return ResponseEntity.notFound().build();
            }
            
            String script = new String(java.nio.file.Files.readAllBytes(scriptPath), java.nio.charset.StandardCharsets.UTF_8);
            log.info("Sirviendo script de compartir Linux: {} bytes", script.length());
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=compartir-impresora-linux.sh")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("Error leyendo script de compartir Linux", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
            /**
     * Endpoint público para descargar el script de instalación para Linux
     */
    @GetMapping("/download/install-linux-script")
    public ResponseEntity<String> downloadInstallLinuxScript() {
        try {
            // Intentar desde el directorio del proyecto
            java.nio.file.Path scriptPath = java.nio.file.Paths.get("scripts/instalar-impresora-ipp.sh");
            
            // Si no existe, intentar desde resources
            if (!java.nio.file.Files.exists(scriptPath)) {
                try {
                    java.io.InputStream is = getClass().getResourceAsStream("/scripts/instalar-impresora-ipp.sh");
                    if (is != null) {
                        String script = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.info("Sirviendo script de instalación Linux desde classpath ({} bytes)", script.length());
                        
                        return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=instalar-impresora-ipp.sh")
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .body(script);
                    }
                } catch (Exception ex) {
                    log.debug("No se pudo cargar desde classpath: {}", ex.getMessage());
                }
                
                log.error("Script de instalación Linux no encontrado");
                return ResponseEntity.notFound().build();
            }
            
            String script = new String(java.nio.file.Files.readAllBytes(scriptPath), java.nio.charset.StandardCharsets.UTF_8);
            log.info("Sirviendo script de instalación Linux: {} bytes", script.length());
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=instalar-impresora-ipp.sh")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("Error leyendo script de instalación Linux", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Endpoint público para descargar el script de desinstalación/descompartir para Windows
     */
    @GetMapping("/download/uninstall-printershare-script")
    public ResponseEntity<String> downloadUninstallPrintershareScript() {
        try {
            // Intentar desde el directorio del proyecto
            java.nio.file.Path scriptPath = java.nio.file.Paths.get("scripts/desinstalar-printershare.bat");
            
            // Si no existe, intentar desde resources
            if (!java.nio.file.Files.exists(scriptPath)) {
                try {
                    java.io.InputStream is = getClass().getResourceAsStream("/scripts/desinstalar-printershare.bat");
                    if (is != null) {
                        String script = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        log.info("Sirviendo script de desinstalación desde classpath ({} bytes)", script.length());
                        
                        return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=desinstalar-printershare.bat")
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .body(script);
                    }
                } catch (Exception ex) {
                    log.debug("No se pudo cargar desde classpath: {}", ex.getMessage());
                }
                
                log.error("Script de desinstalación no encontrado");
                return ResponseEntity.notFound().build();
            }
            
            String script = new String(java.nio.file.Files.readAllBytes(scriptPath), java.nio.charset.StandardCharsets.UTF_8);
            log.info("Sirviendo script de desinstalación: {} bytes", script.length());
            
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=desinstalar-printershare.bat")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(script);
        } catch (Exception e) {
            log.error("Error leyendo script de desinstalación", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
        /**
     * Endpoint público para obtener todos los departamentos
     * Para usar en el landing page (sin autenticación)
     */
    @GetMapping("/departments")
    public Map<String, Object> getAllDepartments() {
        log.info("Public API: /api/departments");
        
        List<Map<String, String>> departmentsList = new ArrayList<>();
        List<Department> departments = entityManager.createQuery(
                "SELECT d FROM Department d ORDER BY d.name", Department.class)
                .getResultList();
        
        for (Department d : departments) {
            Map<String, String> deptData = new HashMap<>();
            deptData.put("id", String.valueOf(d.getId()));
            deptData.put("name", d.getName());
            deptData.put("description", d.getDescription() != null ? d.getDescription() : "");
            deptData.put("location", d.getLocation() != null ? d.getLocation() : "");
            deptData.put("color", d.getColor() != null ? d.getColor() : "#667eea");
            deptData.put("totalPrinters", String.valueOf(d.getPrinters().size()));
            departmentsList.add(deptData);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("departments", departmentsList);
        response.put("total", departmentsList.size());
        
        log.info("Returning {} departments", departmentsList.size());
        return response;
    }
    
    /**
     * Endpoint público para obtener todas las impresoras del sistema
     * Para usar en el landing page (sin autenticación)
     * Devuelve la IP del servidor (fija) y el nombre de cada impresora
     */
    @GetMapping("/printers")
    public Map<String, Object> getAllPrinters() {
        log.info("Public API: /api/printers");
        
                // Obtener IP del servidor (FIJA para todas las impresoras)
        String serverIp = es.ucm.fdi.iu.util.NetworkUtils.getServerIpAddress();
        
        List<Map<String, String>> printersList = new ArrayList<>();
        List<Printer> printers = entityManager.createQuery(
                "SELECT p FROM Printer p ORDER BY p.alias", Printer.class)
                .getResultList();
        
                                                                        for (Printer p : printers) {
            Map<String, String> printerData = new HashMap<>();
            printerData.put("id", String.valueOf(p.getId()));
            printerData.put("alias", p.getAlias());
            printerData.put("model", p.getModel() != null ? p.getModel() : "");
            
            // Location (IMPORTANTE para detectar impresoras USB compartidas)
            String location = p.getLocation() != null ? p.getLocation() : "";
            printerData.put("location", location);
            boolean isSharedUSB = location.contains("Compartida-USB");
            printerData.put("isSharedUSB", String.valueOf(isSharedUSB));
            
            // Puerto IPP dedicado de esta impresora en el SERVIDOR
            int ippPort = p.getIppPort() != null ? p.getIppPort() : 8631;
            printerData.put("ippPort", String.valueOf(ippPort));
            
            // IP física de la impresora (para información)
            printerData.put("printerIp", p.getIp() != null ? p.getIp() : "");
            
            // TODAS las conexiones van AL SERVIDOR en su puerto dedicado
            // El servidor se encarga de reenviar a donde corresponda:
            // - USB compartida: servidor reenvía a cliente USB en puerto 631
            // - Red normal: servidor reenvía a impresora directamente
            String safeName = p.getAlias().replace(" ", "_");
            String ippUri = String.format("ipp://%s:%d/printers/%s", serverIp, ippPort, safeName);
            printerData.put("ippUri", ippUri);
            printerData.put("connectionType", "server");
            
            if (isSharedUSB) {
                log.debug("Impresora USB: {} - Clientes se conectan a servidor {}:{} (servidor reenvía a {}:631)", 
                    p.getAlias(), serverIp, ippPort, p.getIp());
            }
            
            printersList.add(printerData);
        }
        
                // Construir respuesta completa
        Map<String, Object> response = new HashMap<>();
        response.put("serverIp", serverIp);
        response.put("port", 8631);
        response.put("printers", printersList);
        response.put("total", printersList.size());
        
                log.info("Returning {} printers (server IP: {})", printersList.size(), serverIp);
        return response;
    }
    
    /**
     * Endpoint público para obtener impresoras de un departamento específico
     * Para usar en el landing page (sin autenticación)
     */
    @GetMapping("/departments/{departmentId}/printers")
    public Map<String, Object> getPrintersByDepartment(@PathVariable Long departmentId) {
        log.info("Public API: /api/departments/{}/printers", departmentId);
        
        Department department = entityManager.find(Department.class, departmentId);
        
        if (department == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Departamento no encontrado");
            errorResponse.put("total", 0);
            errorResponse.put("printers", new ArrayList<>());
            return errorResponse;
        }
        
        // Obtener IP del servidor (FIJA para todas las impresoras)
        String serverIp = es.ucm.fdi.iu.util.NetworkUtils.getServerIpAddress();
        
        List<Map<String, String>> printersList = new ArrayList<>();
        List<Printer> printers = department.getPrinters();
        
        for (Printer p : printers) {
            Map<String, String> printerData = new HashMap<>();
            printerData.put("id", String.valueOf(p.getId()));
            printerData.put("alias", p.getAlias());
            printerData.put("model", p.getModel() != null ? p.getModel() : "");
            
            // Location (IMPORTANTE para detectar impresoras USB compartidas)
            String location = p.getLocation() != null ? p.getLocation() : "";
            printerData.put("location", location);
            boolean isSharedUSB = location.contains("Compartida-USB");
            printerData.put("isSharedUSB", String.valueOf(isSharedUSB));
            
            // Puerto IPP dedicado de esta impresora en el SERVIDOR
            int ippPort = p.getIppPort() != null ? p.getIppPort() : 8631;
            printerData.put("ippPort", String.valueOf(ippPort));
            
            // IP física de la impresora (para información)
            printerData.put("printerIp", p.getIp() != null ? p.getIp() : "");
            
            // TODAS las conexiones van AL SERVIDOR en su puerto dedicado
            String safeName = p.getAlias().replace(" ", "_");
            String ippUri = String.format("ipp://%s:%d/printers/%s", serverIp, ippPort, safeName);
            printerData.put("ippUri", ippUri);
            printerData.put("connectionType", "server");
            
            printersList.add(printerData);
        }
        
        // Construir respuesta completa
        Map<String, Object> response = new HashMap<>();
        response.put("serverIp", serverIp);
        response.put("departmentId", departmentId);
        response.put("departmentName", department.getName());
        response.put("printers", printersList);
        response.put("total", printersList.size());
        
        log.info("Returning {} printers for department '{}' (server IP: {})", 
                 printersList.size(), department.getName(), serverIp);
        return response;
    }
}
