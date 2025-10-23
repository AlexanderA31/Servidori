package es.ucm.fdi.iu.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import es.ucm.fdi.iu.model.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;
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
 */
@RestController
@CrossOrigin
@RequestMapping("api")
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
}
