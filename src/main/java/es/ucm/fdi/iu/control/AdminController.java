package es.ucm.fdi.iu.control;

import es.ucm.fdi.iu.model.PGroup;
import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.model.User;
import es.ucm.fdi.iu.model.Department;
import es.ucm.fdi.iu.model.Computer;
import es.ucm.fdi.iu.model.NetworkRange;
import es.ucm.fdi.iu.model.Job;
import es.ucm.fdi.iu.service.PrinterDiscoveryService;
import es.ucm.fdi.iu.service.PrinterAutoConfigService;
import es.ucm.fdi.iu.service.PrintQueueService;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.net.InetAddress;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;

/**
 *  Allows user management, and generating random values for
 *  users.
 *
 *  Access to this end-point is authenticated.
 */
@Controller
@ConditionalOnProperty(name = "app.mode", havingValue = "server", matchIfMissing = true)
@RequestMapping("/admin")
public class AdminController {

	private static final Logger log = LogManager.getLogger(AdminController.class);

	@Autowired
	private EntityManager entityManager;

		@Autowired
	private PasswordEncoder passwordEncoder;
	
	    @Autowired
    private PrinterDiscoveryService printerDiscoveryService;
    
        @Autowired
    private PrinterAutoConfigService printerAutoConfigService;
    
    @Autowired
    private es.ucm.fdi.iu.service.SambaAutoConfigService sambaAutoConfigService;
    
        @Autowired
    private es.ucm.fdi.iu.service.IppPrintService ippPrintService;
    
        @Autowired
    private PrintQueueService printQueueService;
    
        @Autowired
    private es.ucm.fdi.iu.service.NetworkDiagnosticService networkDiagnosticService;
    
    @Autowired
    private es.ucm.fdi.iu.service.MultiPortIppServerService multiPortIppServerService;

                                                                    // ========== DASHBOARD PRINCIPAL ==========
    
    @GetMapping({"/", ""})
    @Transactional
    public String dashboard(Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
                                    // Obtener IP del servidor
            String serverIp = es.ucm.fdi.iu.util.NetworkUtils.getServerIpAddress();
            model.addAttribute("serverIp", serverIp);
            
            // Puerto IPP (8631 para evitar conflicto con CUPS nativo)
            model.addAttribute("ippPort", 8631);
            
            // Estadísticas generales
            long totalDepartments = entityManager.createQuery(
                    "SELECT COUNT(d) FROM Department d", Long.class)
                    .getSingleResult();
            
            long totalComputers = entityManager.createQuery(
                    "SELECT COUNT(c) FROM Computer c", Long.class)
                    .getSingleResult();
            
                        long unassignedComputers = entityManager.createQuery(
                    "SELECT COUNT(c) FROM Computer c WHERE c.department IS NULL", Long.class)
                    .getSingleResult();
            
                        long totalPrinters = entityManager.createQuery(
                    "SELECT COUNT(p) FROM Printer p", Long.class)
                    .getSingleResult();
            
            model.addAttribute("totalDepartments", totalDepartments);
            model.addAttribute("totalDepartmentsCount", totalDepartments);
            model.addAttribute("totalComputers", totalComputers);
            model.addAttribute("unassignedComputers", unassignedComputers);
            model.addAttribute("unassignedComputersCount", unassignedComputers);
            model.addAttribute("totalPrinters", totalPrinters);
            model.addAttribute("totalPrintersCount", totalPrinters);
            model.addAttribute("currentUri", request.getRequestURI());
            
            log.info("Dashboard loaded successfully");
            return "admin-dashboard";
        } catch (Exception e) {
            log.error("Error loading dashboard", e);
            model.addAttribute("error", "Error al cargar el dashboard: " + e.getMessage());
            return "error";
        }
    }
    
    // ========== SECCIÓN: DEPARTAMENTOS ==========
    
                        @GetMapping("/departments")
    @Transactional
    public String departments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            // Contar total de departamentos (para estadísticas)
            long totalDepartmentsCount = entityManager.createQuery(
                    "SELECT COUNT(d) FROM Department d", Long.class)
                    .getSingleResult();
            
            // Cargar departamentos con paginación
            Pageable pageable = PageRequest.of(page, size);
            List<Department> allDepartments = entityManager.createNamedQuery(
                    "Department.all", Department.class)
                    .getResultList();
            
            // Protección contra listas vacías o páginas fuera de rango
            int start = Math.min((int) pageable.getOffset(), allDepartments.size());
            int end = Math.min((start + pageable.getPageSize()), allDepartments.size());
            List<Department> departmentsPage = start < allDepartments.size() ? allDepartments.subList(start, end) : java.util.Collections.emptyList();
            Page<Department> departments = new PageImpl<>(departmentsPage, pageable, allDepartments.size());
            
            // Computadoras sin asignar
            List<Computer> unassignedComputers = entityManager.createQuery(
                    "SELECT c FROM Computer c WHERE c.department IS NULL", Computer.class)
                    .getResultList();
            
            // Todas las impresoras
            List<Printer> allPrinters = entityManager.createQuery(
                    "SELECT p FROM Printer p", Printer.class)
                    .getResultList();
            
            // Rangos de red
            List<NetworkRange> networkRanges = new java.util.ArrayList<>();
            try {
                networkRanges = entityManager.createNamedQuery(
                        "NetworkRange.all", NetworkRange.class)
                        .getResultList();
            } catch (Exception e) {
                log.warn("No se pudieron cargar rangos de red: {}", e.getMessage());
            }
            
                        model.addAttribute("departments", departments);
            model.addAttribute("totalDepartmentsCount", totalDepartmentsCount);
            model.addAttribute("unassignedComputers", unassignedComputers);
            model.addAttribute("allPrinters", allPrinters);
            model.addAttribute("networkRanges", networkRanges);
            model.addAttribute("currentUri", request.getRequestURI());
            
            log.info("Departments section loaded");
            return "admin-departments";
        } catch (Exception e) {
            log.error("Error loading departments", e);
            model.addAttribute("error", "Error al cargar departamentos: " + e.getMessage());
            return "error";
        }
    }
    
    // ========== SECCIÓN: IMPRESORAS ==========
    
                        @GetMapping("/printers")
    @Transactional
    public String printers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            // Contar total de impresoras (para estadísticas)
            long totalPrintersCount = entityManager.createQuery(
                    "SELECT COUNT(p) FROM Printer p", Long.class)
                    .getSingleResult();
            
            // Cargar todas las impresoras con paginación
            Pageable pageable = PageRequest.of(page, size);
            List<Printer> allPrintersList = entityManager.createQuery(
                    "SELECT p FROM Printer p ORDER BY p.alias", Printer.class)
                    .getResultList();
            
            // Protección contra listas vacías o páginas fuera de rango
            int start = Math.min((int) pageable.getOffset(), allPrintersList.size());
            int end = Math.min((start + pageable.getPageSize()), allPrintersList.size());
            List<Printer> printersPage = start < allPrintersList.size() ? allPrintersList.subList(start, end) : java.util.Collections.emptyList();
            Page<Printer> allPrinters = new PageImpl<>(printersPage, pageable, allPrintersList.size());
            
            // Rangos de red
            List<NetworkRange> networkRanges = new java.util.ArrayList<>();
            try {
                networkRanges = entityManager.createNamedQuery(
                        "NetworkRange.all", NetworkRange.class)
                        .getResultList();
            } catch (Exception e) {
                log.warn("No se pudieron cargar rangos de red: {}", e.getMessage());
            }
            
            // Datos para el sidebar
            List<Department> departments = entityManager.createNamedQuery(
                    "Department.all", Department.class)
                    .getResultList();
            
            List<Computer> unassignedComputers = entityManager.createQuery(
                    "SELECT c FROM Computer c WHERE c.department IS NULL", Computer.class)
                    .getResultList();
            
                                                model.addAttribute("allPrinters", allPrinters);
            model.addAttribute("totalPrintersCount", totalPrintersCount);
            model.addAttribute("networkRanges", networkRanges);
            model.addAttribute("departments", departments);  // PARA EL SIDEBAR
            model.addAttribute("unassignedComputers", unassignedComputers);  // PARA EL SIDEBAR
            model.addAttribute("currentUri", request.getRequestURI());
            
            log.info("Printers section loaded with {} printers", totalPrintersCount);
            return "admin-printers";
        } catch (Exception e) {
            log.error("Error loading printers", e);
            model.addAttribute("error", "Error al cargar impresoras: " + e.getMessage());
            return "error";
        }
    }
    
    // ========== SECCIÓN: COLAS DE IMPRESIÓN ==========
    
    @GetMapping("/printqueues")
    @Transactional
    public String printQueues(Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            // Cargar todas las impresoras con sus colas
            List<Printer> printersWithJobs = entityManager.createQuery(
                    "SELECT DISTINCT p FROM Printer p LEFT JOIN FETCH p.queue ORDER BY p.alias", Printer.class)
                    .getResultList();
            
            // Filtrar solo las impresoras que tienen trabajos
            printersWithJobs = printersWithJobs.stream()
                    .filter(p -> p.getQueue() != null && !p.getQueue().isEmpty())
                    .collect(java.util.stream.Collectors.toList());
            
            // Todas las impresoras (para el modal de agregar trabajo)
            List<Printer> allPrinters = entityManager.createQuery(
                    "SELECT p FROM Printer p ORDER BY p.alias", Printer.class)
                    .getResultList();
            
            // Estadísticas
            long totalJobs = entityManager.createQuery(
                    "SELECT COUNT(j) FROM Job j", Long.class)
                    .getSingleResult();
            
            long activePrinters = printersWithJobs.size();
            long pendingJobs = totalJobs;
            
                        // Datos para el sidebar
            List<Department> departments = entityManager.createNamedQuery(
                    "Department.all", Department.class)
                    .getResultList();
            
            List<Computer> unassignedComputers = entityManager.createQuery(
                    "SELECT c FROM Computer c WHERE c.department IS NULL", Computer.class)
                    .getResultList();
            
            // Contadores para el sidebar
            long totalDepartmentsCount = entityManager.createQuery(
                    "SELECT COUNT(d) FROM Department d", Long.class)
                    .getSingleResult();
            
            long totalPrintersCount = entityManager.createQuery(
                    "SELECT COUNT(p) FROM Printer p", Long.class)
                    .getSingleResult();
            
            long unassignedComputersCount = entityManager.createQuery(
                    "SELECT COUNT(c) FROM Computer c WHERE c.department IS NULL", Long.class)
                    .getSingleResult();
            
            model.addAttribute("printersWithJobs", printersWithJobs);
            model.addAttribute("allPrinters", allPrinters);
            model.addAttribute("totalJobs", totalJobs);
            model.addAttribute("activePrinters", activePrinters);
            model.addAttribute("pendingJobs", pendingJobs);
            model.addAttribute("departments", departments);
            model.addAttribute("unassignedComputers", unassignedComputers);
            model.addAttribute("totalDepartmentsCount", totalDepartmentsCount);
            model.addAttribute("totalPrintersCount", totalPrintersCount);
            model.addAttribute("unassignedComputersCount", unassignedComputersCount);
            model.addAttribute("currentUri", request.getRequestURI());
            
            log.info("Print queues section loaded with {} active printers", activePrinters);
            return "admin-printqueues";
        } catch (Exception e) {
            log.error("Error loading print queues", e);
            model.addAttribute("error", "Error al cargar colas de impresión: " + e.getMessage());
            return "error";
        }
    }
    
        @PostMapping("/printqueues/add-job")
    @Transactional
    public String addJob(
            @RequestParam Long printerId,
            @RequestParam String fileName,
            @RequestParam String owner,
            @RequestParam(required = false) MultipartFile file,
            HttpSession session,
            RedirectAttributes ra) {
        try {
            User user = (User) session.getAttribute("u");
            Printer printer = entityManager.find(Printer.class, printerId);
            
            if (printer == null) {
                ra.addFlashAttribute("error", "Impresora no encontrada");
                return "redirect:/admin/printqueues";
            }
            
            // Obtener datos del archivo si se proporcionó
            byte[] fileData = null;
            if (file != null && !file.isEmpty()) {
                fileData = file.getBytes();
                log.info("📎 Archivo recibido: {} ({} bytes)", file.getOriginalFilename(), fileData.length);
            }
            
            // Usar el servicio de colas para agregar el trabajo
            Job job = printQueueService.addJob(printer, fileName, owner, user, fileData);
            
            ra.addFlashAttribute("success", "Trabajo agregado a la cola de " + printer.getAlias() + 
                " (ID: " + job.getId() + ")");
        } catch (Exception e) {
            log.error("Error adding job", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/printqueues";
    }
    
        @PostMapping("/printqueues/cancel-job")
    @Transactional
    public String cancelJob(@RequestParam Long jobId, RedirectAttributes ra) {
        try {
            boolean cancelled = printQueueService.cancelJob(jobId);
            if (cancelled) {
                ra.addFlashAttribute("success", "Trabajo cancelado exitosamente");
            } else {
                ra.addFlashAttribute("error", "No se pudo cancelar el trabajo");
            }
        } catch (Exception e) {
            log.error("Error canceling job", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/printqueues";
    }
    
        @PostMapping("/printqueues/clear-queue")
    @Transactional
    public String clearQueue(@RequestParam Long printerId, RedirectAttributes ra) {
        try {
            int cleared = printQueueService.clearQueue(printerId);
            if (cleared > 0) {
                ra.addFlashAttribute("success", "Cola limpiada: " + cleared + " trabajo(s) eliminado(s)");
            } else {
                ra.addFlashAttribute("info", "No había trabajos en la cola");
            }
        } catch (Exception e) {
            log.error("Error clearing queue", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/printqueues";
    }
    
    @PostMapping("/printqueues/clear-all")
    @Transactional
    public String clearAllQueues(RedirectAttributes ra) {
        try {
            int totalDeleted = entityManager.createQuery("DELETE FROM Job").executeUpdate();
            ra.addFlashAttribute("success", "Todas las colas limpiadas: " + totalDeleted + " trabajo(s) eliminado(s)");
        } catch (Exception e) {
            log.error("Error clearing all queues", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/printqueues";
    }
    
    @PostMapping("/printqueues/move-to-top")
    @Transactional
    public String moveJobToTop(@RequestParam Long jobId, RedirectAttributes ra) {
        try {
            Job job = entityManager.find(Job.class, jobId);
            if (job != null && job.getPrinter() != null) {
                Printer printer = job.getPrinter();
                List<Job> queue = printer.getQueue();
                
                if (queue.remove(job)) {
                    queue.add(0, job);
                    entityManager.flush();
                    ra.addFlashAttribute("success", "Trabajo movido al inicio de la cola");
                } else {
                    ra.addFlashAttribute("error", "No se pudo mover el trabajo");
                }
            } else {
                ra.addFlashAttribute("error", "Trabajo o impresora no encontrado");
            }
        } catch (Exception e) {
            log.error("Error moving job to top", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/printqueues";
    }
    
    // ========== SECCIÓN: COMPUTADORAS ==========
    
                        @GetMapping("/computers")
    @Transactional
    public String computers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            Pageable pageable = PageRequest.of(page, size);
            
            // Contar totales (para estadísticas)
            long totalComputersCount = entityManager.createQuery(
                    "SELECT COUNT(c) FROM Computer c", Long.class)
                    .getSingleResult();
            
            long unassignedComputersCount = entityManager.createQuery(
                    "SELECT COUNT(c) FROM Computer c WHERE c.department IS NULL", Long.class)
                    .getSingleResult();
            
            long assignedComputersCount = entityManager.createQuery(
                    "SELECT COUNT(c) FROM Computer c WHERE c.department IS NOT NULL", Long.class)
                    .getSingleResult();
            
            // Cargar todas las computadoras
            List<Computer> allComputersList = entityManager.createQuery(
                    "SELECT c FROM Computer c ORDER BY c.name", Computer.class)
                    .getResultList();
            
                        // Computadoras sin asignar con paginación
            List<Computer> unassignedComputersList = entityManager.createQuery(
                    "SELECT c FROM Computer c WHERE c.department IS NULL ORDER BY c.name", Computer.class)
                    .getResultList();
            
            int startUnassigned = Math.min((int) pageable.getOffset(), unassignedComputersList.size());
            int endUnassigned = Math.min((startUnassigned + pageable.getPageSize()), unassignedComputersList.size());
            List<Computer> unassignedPage = startUnassigned < unassignedComputersList.size() ? unassignedComputersList.subList(startUnassigned, endUnassigned) : java.util.Collections.emptyList();
            Page<Computer> unassignedComputers = new PageImpl<>(unassignedPage, pageable, unassignedComputersList.size());
            
            // Computadoras por departamento con paginación
            List<Computer> assignedComputersList = entityManager.createQuery(
                    "SELECT c FROM Computer c WHERE c.department IS NOT NULL ORDER BY c.department.name, c.name", Computer.class)
                    .getResultList();
            
            int startAssigned = Math.min((int) pageable.getOffset(), assignedComputersList.size());
            int endAssigned = Math.min((startAssigned + pageable.getPageSize()), assignedComputersList.size());
            List<Computer> assignedPage = startAssigned < assignedComputersList.size() ? assignedComputersList.subList(startAssigned, endAssigned) : java.util.Collections.emptyList();
            Page<Computer> assignedComputers = new PageImpl<>(assignedPage, pageable, assignedComputersList.size());
            
            List<Computer> allComputers = allComputersList;
            
            // Cargar departamentos para asignación Y para el sidebar
            List<Department> departments = entityManager.createNamedQuery(
                    "Department.all", Department.class)
                    .getResultList();
            
            // Cargar todas las impresoras PARA EL SIDEBAR
            List<Printer> allPrinters = entityManager.createQuery(
                    "SELECT p FROM Printer p", Printer.class)
                    .getResultList();
            
                        model.addAttribute("allComputers", allComputers);
            model.addAttribute("unassignedComputers", unassignedComputers);
            model.addAttribute("assignedComputers", assignedComputers);
            model.addAttribute("unassignedComputersCount", unassignedComputersCount);
            model.addAttribute("assignedComputersCount", assignedComputersCount);
            model.addAttribute("departments", departments);
            model.addAttribute("allPrinters", allPrinters);  // PARA EL SIDEBAR
            model.addAttribute("currentUri", request.getRequestURI());
            
            log.info("Computers section loaded with {} computers", allComputers.size());
            return "admin-computers";
        } catch (Exception e) {
            log.error("Error loading computers", e);
            model.addAttribute("error", "Error al cargar computadoras: " + e.getMessage());
            return "error";
        }
    }
    
        // ========== SECCIÓN: REDES / VLANs ========== (DESHABILITADA)
    
    /*
    @GetMapping("/network")
    @Transactional
    public String network(Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            // Cargar rangos de red
            List<NetworkRange> networkRanges = new java.util.ArrayList<>();
            try {
                networkRanges = entityManager.createNamedQuery(
                        "NetworkRange.all", NetworkRange.class)
                        .getResultList();
            } catch (Exception e) {
                log.warn("No se pudieron cargar rangos de red: {}", e.getMessage());
            }
            
            // Obtener IP del servidor
            String serverIp = "localhost";
            try {
                serverIp = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                log.warn("No se pudo obtener la IP del servidor: {}", e.getMessage());
            }
            
            model.addAttribute("networkRanges", networkRanges);
            model.addAttribute("serverIp", serverIp);
            model.addAttribute("currentUri", request.getRequestURI());
            
                        log.info("Network section loaded with {} ranges", networkRanges.size());
            return "admin-network";
        } catch (Exception e) {
            log.error("Error loading network section", e);
            model.addAttribute("error", "Error al cargar configuración de red: " + e.getMessage());
                        return "error";
        }
    }
    */
    
        // ========== SECCIÓN: COMPARTIR IMPRESORAS ==========
    
    @GetMapping("/share-printers")
    @Transactional
    public String sharePrinters(Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User currentUser = (User) session.getAttribute("u");
            model.addAttribute("currentUri", request.getRequestURI());
            
            log.info("Share printers section loaded");
            return "admin-share-printers";
        } catch (Exception e) {
            log.error("Error loading share printers section", e);
            model.addAttribute("error", "Error al cargar la sección: " + e.getMessage());
            return "error";
        }
    }
    
    // ========== SECCIÓN: USUARIOS ==========
    
    @GetMapping("/users")
    @Transactional
    public String users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            Pageable pageable = PageRequest.of(page, size);
            
            // Cargar todos los usuarios con paginación
            List<User> allUsersList = entityManager.createQuery(
                    "SELECT u FROM User u ORDER BY u.username", User.class)
                    .getResultList();
            
            // Aplicar paginación
            int start = Math.min((int) pageable.getOffset(), allUsersList.size());
            int end = Math.min((start + pageable.getPageSize()), allUsersList.size());
            List<User> usersPage = start < allUsersList.size() ? allUsersList.subList(start, end) : java.util.Collections.emptyList();
            Page<User> allUsers = new PageImpl<>(usersPage, pageable, allUsersList.size());
            
            // Contar usuarios por rol
            long totalUsers = allUsersList.size();
            long adminUsers = allUsersList.stream()
                    .filter(u -> u.getRoles() != null && u.getRoles().contains("ADMIN"))
                    .count();
            long techUsers = allUsersList.stream()
                    .filter(u -> u.getRoles() != null && !u.getRoles().contains("ADMIN"))
                    .count();
            
            model.addAttribute("allUsers", allUsers);
            model.addAttribute("totalUsers", totalUsers);
            model.addAttribute("adminUsers", adminUsers);
            model.addAttribute("techUsers", techUsers);
            model.addAttribute("currentUserId", currentUser != null ? currentUser.getId() : -1);
            model.addAttribute("currentUri", request.getRequestURI());
            
            log.info("Users section loaded with {} users", totalUsers);
            return "admin-users";
        } catch (Exception e) {
            log.error("Error loading users section", e);
            model.addAttribute("error", "Error al cargar usuarios: " + e.getMessage());
            return "error";
        }
    }

    // ========== GESTIÓN DE DEPARTAMENTOS ==========
    
    @PostMapping("/create-department")
    @Transactional
    public String createDepartment(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String color,
            HttpSession session,
            RedirectAttributes ra) {
        try {
            User user = (User) session.getAttribute("u");
            
            Department dept = new Department();
            dept.setName(name);
            dept.setDescription(description);
            dept.setLocation(location);
            dept.setColor(color != null && !color.isEmpty() ? color : "#667eea");
            dept.setInstance(user);
            
            entityManager.persist(dept);
            ra.addFlashAttribute("success", "Departamento creado: " + name);
        } catch (Exception e) {
            log.error("Error creating department", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/departments";
    }
    
    @PostMapping("/add-computer-to-dept")
    @Transactional
    public String addComputerToDepartment(
            @RequestParam long departmentId,
            @RequestParam String macAddress,
            @RequestParam String name,
            @RequestParam(required = false) String hostname,
            HttpSession session,
            RedirectAttributes ra) {
        try {
            User user = (User) session.getAttribute("u");
            Department dept = entityManager.find(
                    Department.class, departmentId);
            
            if (dept == null) {
                ra.addFlashAttribute("error", "Departamento no encontrado");
                return "redirect:/admin/departments";
            }
            
            Computer computer = new Computer();
            computer.setMacAddress(macAddress);
            computer.setName(name);
            computer.setHostname(hostname);
            computer.setAuthorized(true);
            computer.setDepartment(dept);
            computer.setInstance(user);
            
                        entityManager.persist(computer);
            ra.addFlashAttribute("success", "Computadora agregada al departamento");
        } catch (Exception e) {
            log.error("Error adding computer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/computers";
    }
    
    @PostMapping("/assign-printer-to-dept")
    @Transactional
    public String assignPrinterToDepartment(
            @RequestParam long departmentId,
            @RequestParam long printerId,
            RedirectAttributes ra) {
        try {
            Department dept = entityManager.find(
                    Department.class, departmentId);
            Printer printer = entityManager.find(Printer.class, printerId);
            
            if (dept == null || printer == null) {
                ra.addFlashAttribute("error", "Departamento o impresora no encontrado");
                return "redirect:/admin/departments";
            }
            
            if (!dept.getPrinters().contains(printer)) {
                dept.getPrinters().add(printer);
                ra.addFlashAttribute("success", "Impresora asignada al departamento");
            } else {
                ra.addFlashAttribute("error", "La impresora ya está asignada");
            }
        } catch (Exception e) {
            log.error("Error assigning printer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/departments";
    }
    
    @PostMapping("/remove-printer-from-dept")
    @Transactional
    public String removePrinterFromDepartment(
            @RequestParam long departmentId,
            @RequestParam long printerId,
            RedirectAttributes ra) {
        try {
            Department dept = entityManager.find(
                    Department.class, departmentId);
            Printer printer = entityManager.find(Printer.class, printerId);
            
            if (dept != null && printer != null) {
                dept.getPrinters().remove(printer);
                ra.addFlashAttribute("success", "Impresora removida del departamento");
            }
        } catch (Exception e) {
            log.error("Error removing printer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/departments";
    }
    
        @PostMapping("/remove-computer-from-dept")
    @Transactional
    public String removeComputerFromDepartment(
            @RequestParam long computerId,
            RedirectAttributes ra) {
        try {
            Computer computer = entityManager.find(
                    Computer.class, computerId);
            if (computer != null) {
                computer.setDepartment(null);
                ra.addFlashAttribute("success", "Computadora removida del departamento");
            }
        } catch (Exception e) {
            log.error("Error removing computer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/computers";
    }
    
    @PostMapping("/add-computer")
    @Transactional
    public String addComputer(
            @RequestParam String macAddress,
            @RequestParam String name,
            @RequestParam(required = false) String hostname,
            @RequestParam(required = false) Long departmentId,
            HttpSession session,
            RedirectAttributes ra) {
        try {
            User user = (User) session.getAttribute("u");
            
            Computer computer = new Computer();
            computer.setMacAddress(macAddress);
            computer.setName(name);
            computer.setHostname(hostname);
            computer.setAuthorized(true);
            computer.setInstance(user);
            
            if (departmentId != null) {
                Department dept = entityManager.find(Department.class, departmentId);
                if (dept != null) {
                    computer.setDepartment(dept);
                }
            }
            
            entityManager.persist(computer);
            ra.addFlashAttribute("success", "Computadora agregada: " + name);
        } catch (Exception e) {
            log.error("Error adding computer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/computers";
    }
    
    @PostMapping("/edit-computer")
    @Transactional
    public String editComputer(
            @RequestParam Long id,
            @RequestParam String macAddress,
            @RequestParam String name,
            @RequestParam(required = false) String hostname,
            RedirectAttributes ra) {
        try {
            Computer computer = entityManager.find(Computer.class, id);
            if (computer != null) {
                computer.setMacAddress(macAddress);
                computer.setName(name);
                computer.setHostname(hostname);
                ra.addFlashAttribute("success", "Computadora actualizada");
            } else {
                ra.addFlashAttribute("error", "Computadora no encontrada");
            }
        } catch (Exception e) {
            log.error("Error editing computer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/computers";
    }
    
    @PostMapping("/delete-computer")
    @Transactional
    public String deleteComputer(@RequestParam Long id, RedirectAttributes ra) {
        try {
            Computer computer = entityManager.find(Computer.class, id);
            if (computer != null) {
                String computerName = computer.getName();
                entityManager.remove(computer);
                ra.addFlashAttribute("success", "Computadora eliminada: " + computerName);
            } else {
                ra.addFlashAttribute("error", "Computadora no encontrada");
            }
        } catch (Exception e) {
            log.error("Error deleting computer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/computers";
    }
    
    @PostMapping("/assign-computer-to-dept")
    @Transactional
    public String assignComputerToDepartment(
            @RequestParam Long computerId,
            @RequestParam Long departmentId,
            RedirectAttributes ra) {
        try {
            Computer computer = entityManager.find(Computer.class, computerId);
            Department dept = entityManager.find(Department.class, departmentId);
            
            if (computer != null && dept != null) {
                computer.setDepartment(dept);
                ra.addFlashAttribute("success", "Computadora asignada a " + dept.getName());
            } else {
                ra.addFlashAttribute("error", "Computadora o departamento no encontrado");
            }
        } catch (Exception e) {
            log.error("Error assigning computer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/computers";
    }
    
    @PostMapping("/delete-department")
    @Transactional
    public String deleteDepartment(@RequestParam long id, RedirectAttributes ra) {
        try {
            Department dept = entityManager.find(
                    Department.class, id);
            if (dept != null) {
                String name = dept.getName();
                entityManager.remove(dept);
                ra.addFlashAttribute("success", "Departamento eliminado: " + name);
            }
        } catch (Exception e) {
            log.error("Error deleting department", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/departments";
    }
    
            // ========== GESTIÓN DE USUARIOS ==========
    
    @PostMapping("/add-user")
    @Transactional
    public String addUser(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String role,
            HttpSession session,
            RedirectAttributes ra) {
        try {
            // Verificar que el usuario no exista
            Long count = entityManager.createNamedQuery("User.hasUsername", Long.class)
                    .setParameter("username", username)
                    .getSingleResult();
            
            if (count > 0) {
                ra.addFlashAttribute("error", "El nombre de usuario ya existe");
                return "redirect:/admin/users";
            }
            
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setEnabled(true);
            user.setRoles(role);
            
            entityManager.persist(user);
            
            String roleName = role.equals("ADMIN") ? "Administrador" : "Técnico";
            ra.addFlashAttribute("success", "Usuario creado exitosamente: " + username + " (" + roleName + ")");
            
            log.info("✅ Usuario creado: {} con rol {}", username, role);
        } catch (Exception e) {
            log.error("Error creating user", e);
            ra.addFlashAttribute("error", "Error al crear usuario: " + e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/edit-user")
    @Transactional
    public String editUser(
            @RequestParam Long id,
            @RequestParam String username,
            @RequestParam(required = false) String password,
            @RequestParam String role,
            RedirectAttributes ra) {
        try {
            User user = entityManager.find(User.class, id);
            if (user != null) {
                user.setUsername(username);
                if (password != null && !password.isEmpty()) {
                    user.setPassword(passwordEncoder.encode(password));
                    log.info("🔐 Contraseña actualizada para usuario: {}", username);
                }
                user.setRoles(role);
                
                String roleName = role.equals("ADMIN") ? "Administrador" : "Técnico";
                ra.addFlashAttribute("success", "Usuario actualizado: " + username + " (" + roleName + ")");
                
                log.info("✅ Usuario editado: {} con rol {}", username, role);
            } else {
                ra.addFlashAttribute("error", "Usuario no encontrado");
            }
        } catch (Exception e) {
            log.error("Error editing user", e);
            ra.addFlashAttribute("error", "Error al editar usuario: " + e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/delete-user")
    @Transactional
    public String deleteUser(@RequestParam Long id, RedirectAttributes ra) {
        try {
            User user = entityManager.find(User.class, id);
            if (user != null) {
                // No permitir eliminar al admin principal
                if (user.getId() == 1) {
                    ra.addFlashAttribute("error", "No se puede eliminar el administrador principal");
                } else {
                    String username = user.getUsername();
                    entityManager.remove(user);
                    ra.addFlashAttribute("success", "Usuario eliminado: " + username);
                    log.info("✅ Usuario eliminado: {}", username);
                }
            } else {
                ra.addFlashAttribute("error", "Usuario no encontrado");
            }
        } catch (Exception e) {
            log.error("Error deleting user", e);
            ra.addFlashAttribute("error", "Error al eliminar usuario: " + e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/toggle-user")
    @Transactional
    public String toggleUser(@RequestParam Long id, RedirectAttributes ra) {
        try {
            User user = entityManager.find(User.class, id);
            if (user != null) {
                // No permitir desactivar al admin principal
                if (user.getId() == 1 && user.isEnabled()) {
                    ra.addFlashAttribute("error", "No se puede desactivar el administrador principal");
                } else {
                    user.setEnabled(!user.isEnabled());
                    String status = user.isEnabled() ? "activado" : "desactivado";
                    ra.addFlashAttribute("success", "Usuario " + status + ": " + user.getUsername());
                    log.info("✅ Usuario {}: {}", status, user.getUsername());
                }
            } else {
                ra.addFlashAttribute("error", "Usuario no encontrado");
            }
        } catch (Exception e) {
            log.error("Error toggling user", e);
            ra.addFlashAttribute("error", "Error al cambiar estado: " + e.getMessage());
        }
        return "redirect:/admin/users";
    }

    // ========== DESCUBRIMIENTO DE IMPRESORAS ==========
    
                @PostMapping("/discover-printers")
    public String discoverPrinters(HttpSession session, RedirectAttributes ra) {
        try {
            User user = (User) session.getAttribute("u");
            
            // Ejecutar escaneo en background
            new Thread(() -> {
                int registeredCount = 0;
                int duplicatesCount = 0;
                try {
                    log.info("========================================");
                    log.info("🔍 Iniciando descubrimiento de impresoras...");
                    
                    // Descubrir impresoras de red
                    List<PrinterDiscoveryService.DiscoveredPrinter> networkPrinters = 
                        printerDiscoveryService.discoverNetworkPrinters();
                    log.info("✅ Impresoras de red descubiertas: {}", networkPrinters.size());
                    
                    // Descubrir impresoras locales
                    List<PrinterDiscoveryService.DiscoveredPrinter> localPrinters = 
                        printerDiscoveryService.discoverLocalPrinters();
                    log.info("✅ Impresoras locales descubiertas: {}", localPrinters.size());
                    
                    int totalFound = networkPrinters.size() + localPrinters.size();
                    log.info("📊 Total de impresoras encontradas: {}", totalFound);
                    log.info("💾 Iniciando registro en base de datos...");
                    
                    // Registrar impresoras de red
                    for (PrinterDiscoveryService.DiscoveredPrinter discovered : networkPrinters) {
                        Printer registered = printerDiscoveryService.registerDiscoveredPrinter(discovered, user);
                        if (registered != null) {
                            registeredCount++;
                        } else {
                            duplicatesCount++;
                        }
                    }
                    
                    // Registrar impresoras locales
                    for (PrinterDiscoveryService.DiscoveredPrinter discovered : localPrinters) {
                        Printer registered = printerDiscoveryService.registerDiscoveredPrinter(discovered, user);
                        if (registered != null) {
                            registeredCount++;
                        } else {
                            duplicatesCount++;
                        }
                    }
                    
                    log.info("========================================");
                    log.info("✅ RESUMEN DEL ESCANEO:");
                    log.info("  🔍 Impresoras encontradas: {}", totalFound);
                    log.info("  ✅ Impresoras registradas: {}", registeredCount);
                    log.info("  ❌ Duplicadas (ya existían): {}", duplicatesCount);
                    log.info("========================================");
                    
                } catch (Exception e) {
                    log.error("❌ Error durante el escaneo", e);
                }
            }).start();
            
                        ra.addFlashAttribute("info", "Escaneo iniciado en segundo plano. Actualiza la página para ver el progreso.");
        } catch (Exception e) {
            log.error("Error al iniciar escaneo", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/printers?scanning=true";
    }
    
                        @GetMapping("/scan-status")
    @ResponseBody
    public PrinterDiscoveryService.ScanStatus getScanStatus() {
        return printerDiscoveryService.getScanStatus();
    }
    
        @PostMapping("/cancel-scan")
    @ResponseBody
    public Map<String, Object> cancelScan() {
        Map<String, Object> response = new HashMap<>();
        try {
            printerDiscoveryService.cancelScan();
            response.put("success", true);
            response.put("message", "Escaneo cancelado exitosamente");
            log.info("✅ Escaneo cancelado por el usuario");
        } catch (Exception e) {
            log.error("❌ Error al cancelar escaneo", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }
    
    /**
     * Endpoint para diagnosticar conectividad de red a una VLAN específica
     */
    @PostMapping("/diagnose-network")
    @ResponseBody
    public es.ucm.fdi.iu.service.NetworkDiagnosticService.NetworkDiagnosticResult diagnoseNetwork(
            @RequestBody Map<String, String> request) {
        try {
            String cidrRange = request.get("cidrRange");
            log.info("🔍 Iniciando diagnóstico de red para: {}", cidrRange);
            return networkDiagnosticService.diagnoseNetwork(cidrRange);
        } catch (Exception e) {
            log.error("❌ Error en diagnóstico de red", e);
            es.ucm.fdi.iu.service.NetworkDiagnosticService.NetworkDiagnosticResult result = 
                new es.ucm.fdi.iu.service.NetworkDiagnosticService.NetworkDiagnosticResult();
            result.setError(e.getMessage());
            return result;
        }
    }
    
    @GetMapping("/printqueues/stats")
    @ResponseBody
    public Map<String, Object> getQueueStats() {
        return printQueueService.getQueueStatistics();
    }
    
    // ========== API REST PARA REGISTRO AUTOMÁTICO DE IMPRESORAS COMPARTIDAS ==========
    
    @PostMapping("/api/register-shared-printer")
    @ResponseBody
    @Transactional
    public Map<String, Object> registerSharedPrinter(
            @RequestBody Map<String, String> printerData,
            HttpSession session) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = (User) session.getAttribute("u");
            if (user == null) {
                // Si no hay sesión, usar el usuario admin por defecto (ID 1)
                user = entityManager.find(User.class, 1L);
                if (user == null) {
                    response.put("success", false);
                    response.put("error", "No se pudo identificar el usuario");
                    return response;
                }
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
            
            log.info("✅ Impresora compartida registrada automáticamente: {} (IP: {})", alias, ip);
            
        } catch (Exception e) {
            log.error("❌ Error registrando impresora compartida", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }
    
    // ========== GESTIÓN DE IMPRESORAS ==========
    
                @PostMapping("/add-printer")
    @Transactional
    public String addPrinter(
            @RequestParam String alias,
            @RequestParam String model,
            @RequestParam String location,
            @RequestParam String ip,
            @RequestParam(required = false) String deviceUri,
            @RequestParam(required = false) String driver,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) Integer port,
            @RequestParam(defaultValue = "false") boolean addToCups,
            @RequestParam(defaultValue = "false") boolean shareViaSamba,
            HttpSession session,
            RedirectAttributes ra) {
        try {
            User user = (User) session.getAttribute("u");
            if (user == null) {
                ra.addFlashAttribute("error", "Sesión expirada. Inicia sesión nuevamente.");
                return "redirect:/login";
            }
            
                        // Crear impresora
            Printer printer = new Printer();
                        printer.setAlias(alias);
            printer.setModel(model);
            printer.setLocation(location);
            printer.setIp(ip);
            printer.setDeviceUri(deviceUri);
            printer.setDriver(driver);
            printer.setProtocol(protocol);
            printer.setPort(port);
            printer.setInstance(user);
            printer.setInk(100);
            printer.setPaper(100);
            
            // Asignar puerto IPP único y dedicado
            Integer maxPort = entityManager.createQuery(
                "SELECT MAX(p.ippPort) FROM Printer p", Integer.class)
                .getSingleResult();
            int nextPort = (maxPort != null) ? maxPort + 1 : 8631;
            printer.setIppPort(nextPort);
            log.info("Puerto IPP {} asignado a impresora {}", nextPort, alias);
            
                        entityManager.persist(printer);
            entityManager.flush(); // Asegurar que se persiste antes de configurar
            
            log.info("========================================");
            log.info("NUEVA IMPRESORA AGREGADA");
            log.info("========================================");
            log.info("Alias: {}", printer.getAlias());
            log.info("IP: {}", printer.getIp());
            log.info("Configurar en CUPS: {}", addToCups);
            log.info("Compartir vía Samba: {}", shareViaSamba);
            
            // Auto-configurar Samba si se solicita
            if (shareViaSamba) {
                log.info("Iniciando auto-configuración Samba...");
                boolean sambaSuccess = sambaAutoConfigService.autoConfigurePrinter(printer);
                
                if (sambaSuccess) {
                    ra.addFlashAttribute("success", 
                        "✅ Impresora creada y compartida vía Samba<br>" +
                        "Ruta de acceso: \\\\<servidor-ip>\\" + printer.getAlias());
                    log.info("✅ Impresora configurada en Samba exitosamente");
                } else {
                    ra.addFlashAttribute("warning", 
                        "⚠️ Impresora creada pero no se pudo configurar Samba automáticamente.<br>" +
                        "Configúrala manualmente o verifica que Samba esté instalado.");
                    log.warn("⚠️ No se pudo configurar Samba automáticamente");
                }
            }
            
            // Auto-configurar en CUPS si se solicita
            if (addToCups) {
                PrinterAutoConfigService.ConfigurationResult result = 
                    printerAutoConfigService.autoConfigurePrinter(printer, false);
                
                if (result.isFullyConfigured()) {
                    StringBuilder msg = new StringBuilder("Impresora configurada exitosamente");
                    if (result.getIppUri() != null) {
                        msg.append("<br>IPP: ").append(result.getIppUri());
                    }
                    if (shareViaSamba) {
                        msg.append("<br>Samba: \\\\<servidor-ip>\\").append(printer.getAlias());
                    }
                    ra.addFlashAttribute("success", msg.toString());
                } else {
                    ra.addFlashAttribute("warning", "Impresora creada pero con errores en configuración");
                }
            } else if (!shareViaSamba) {
                ra.addFlashAttribute("success", "Impresora creada (sin auto-configuración)");
            }
            
            log.info("========================================");
            
                } catch (Exception e) {
            log.error("Error creating printer", e);
            ra.addFlashAttribute("error", "Error al crear impresora: " + e.getMessage());
        }
        return "redirect:/admin/printers";
    }

                                @PostMapping("/edit-printer")
    @Transactional
    public String editPrinter(
            @RequestParam Long id,
            @RequestParam String alias,
            @RequestParam String model,
            @RequestParam String location,
            @RequestParam String ip,
            @RequestParam(required = false) String deviceUri,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) Integer port,
            @RequestParam(defaultValue = "false") boolean shareViaSamba,
            RedirectAttributes ra) {
        try {
            Printer printer = entityManager.find(Printer.class, id);
            if (printer != null) {
                String oldAlias = printer.getAlias();
                boolean wasShared = printer.isSharedViaSamba();
                
                printer.setAlias(alias);
                printer.setModel(model);
                printer.setLocation(location);
                printer.setIp(ip);
                printer.setDeviceUri(deviceUri);
                printer.setProtocol(protocol);
                printer.setPort(port);
                
                log.info("========================================");
                log.info("IMPRESORA ACTUALIZADA");
                log.info("========================================");
                log.info("Alias anterior: {}", oldAlias);
                log.info("Alias nuevo: {}", alias);
                log.info("Compartir vía Samba: {}", shareViaSamba);
                log.info("Estaba compartida: {}", wasShared);
                
                // Si cambió el alias y estaba compartida, eliminar el compartido anterior
                if (wasShared && !oldAlias.equals(alias)) {
                    log.info("Alias cambió, eliminando configuración Samba anterior...");
                    Printer tempPrinter = new Printer();
                    tempPrinter.setAlias(oldAlias);
                    sambaAutoConfigService.unconfigurePrinter(tempPrinter);
                }
                
                // Configurar o des-configurar Samba según se solicite
                if (shareViaSamba && !wasShared) {
                    // Activar compartido
                    log.info("Activando compartido Samba...");
                    boolean success = sambaAutoConfigService.autoConfigurePrinter(printer);
                    if (success) {
                        ra.addFlashAttribute("success", 
                            "✅ Impresora actualizada y compartida vía Samba<br>" +
                            "Ruta: \\\\<servidor-ip>\\" + printer.getAlias());
                    } else {
                        ra.addFlashAttribute("warning", 
                            "Impresora actualizada pero no se pudo configurar Samba");
                    }
                } else if (!shareViaSamba && wasShared) {
                    // Desactivar compartido
                    log.info("Desactivando compartido Samba...");
                    boolean success = sambaAutoConfigService.unconfigurePrinter(printer);
                    if (success) {
                        ra.addFlashAttribute("success", 
                            "Impresora actualizada y compartido Samba eliminado");
                    } else {
                        ra.addFlashAttribute("warning", 
                            "Impresora actualizada pero no se pudo eliminar compartido Samba");
                    }
                } else if (shareViaSamba && wasShared) {
                    // Actualizar compartido existente
                    log.info("Actualizando configuración Samba existente...");
                    boolean success = sambaAutoConfigService.autoConfigurePrinter(printer);
                    if (success) {
                        ra.addFlashAttribute("success", 
                            "Impresora y compartido Samba actualizados");
                    } else {
                        ra.addFlashAttribute("warning", 
                            "Impresora actualizada pero no se pudo actualizar Samba");
                    }
                } else {
                    ra.addFlashAttribute("success", "Impresora actualizada exitosamente");
                }
                
                log.info("========================================");
            } else {
                ra.addFlashAttribute("error", "Impresora no encontrada");
            }
                } catch (Exception e) {
            log.error("Error editing printer", e);
            ra.addFlashAttribute("error", "Error al editar impresora: " + e.getMessage());
        }
        return "redirect:/admin/printers";
    }

            @PostMapping("/test-printer")
    @Transactional
    public String testPrinter(@RequestParam Long id, HttpSession session, RedirectAttributes ra) {
        try {
            User user = (User) session.getAttribute("u");
            Printer printer = entityManager.find(Printer.class, id);
            
                        if (printer == null) {
                ra.addFlashAttribute("error", "Impresora no encontrada");
                return "redirect:/admin/printers";
            }
            
            String username = user != null ? user.getUsername() : "admin";
            log.info("🖨️ Iniciando prueba de impresión para: {} (ID: {})", printer.getAlias(), id);
            
            // Intentar imprimir página de prueba
            boolean success = ippPrintService.printTestPage(printer, username);
            
            if (success) {
                ra.addFlashAttribute("success", 
                    "✅ Página de prueba enviada a \"" + printer.getAlias() + 
                    "\". Revisa la impresora para verificar la impresión.");
                log.info("✅ Página de prueba enviada exitosamente a {}", printer.getAlias());
            } else {
                ra.addFlashAttribute("warning", 
                    "⚠️ No se pudo enviar la página de prueba a \"" + printer.getAlias() + 
                    "\". Verifica que la impresora esté encendida y conectada. " +
                    "IP: " + printer.getIp());
                log.warn("⚠️ Fallo al enviar página de prueba a {}", printer.getAlias());
            }
            
        } catch (Exception e) {
                        log.error("❌ Error en prueba de impresión", e);
            ra.addFlashAttribute("error", "Error al enviar página de prueba: " + e.getMessage());
        }
        return "redirect:/admin/printers";
    }
    
            @PostMapping("/share-printer-samba")
    @Transactional
    public String sharePrinterSamba(@RequestParam Long id, RedirectAttributes ra) {
        try {
            Printer printer = entityManager.find(Printer.class, id);
            if (printer != null) {
                log.info("⚙️ Compartiendo impresora vía Samba: {}", printer.getAlias());
                
                boolean success = sambaAutoConfigService.autoConfigurePrinter(printer);
                
                if (success) {
                    ra.addFlashAttribute("success", 
                        "✅ Impresora \"" + printer.getAlias() + "\" compartida vía Samba<br>" +
                        "Ruta de acceso: \\\\<servidor-ip>\\" + printer.getAlias());
                } else {
                    ra.addFlashAttribute("error", 
                        "❌ No se pudo compartir la impresora vía Samba. " +
                        "Verifica que Samba esté instalado y configurado.");
                }
            } else {
                ra.addFlashAttribute("error", "Impresora no encontrada");
            }
        } catch (Exception e) {
            log.error("❌ Error compartiendo impresora vía Samba", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/printers";
    }
    
    @PostMapping("/unshare-printer-samba")
    @Transactional
    public String unsharePrinterSamba(@RequestParam Long id, RedirectAttributes ra) {
        try {
            Printer printer = entityManager.find(Printer.class, id);
            if (printer != null) {
                log.info("⚙️ Eliminando compartido Samba de: {}", printer.getAlias());
                
                boolean success = sambaAutoConfigService.unconfigurePrinter(printer);
                
                if (success) {
                    ra.addFlashAttribute("success", 
                        "✅ Compartido Samba eliminado de \"" + printer.getAlias() + "\"");
                } else {
                    ra.addFlashAttribute("warning", 
                        "⚠️ No se pudo eliminar el compartido Samba");
                }
            } else {
                ra.addFlashAttribute("error", "Impresora no encontrada");
            }
        } catch (Exception e) {
            log.error("❌ Error eliminando compartido Samba", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/printers";
    }
    
    @GetMapping("/printer-samba-status/{id}")
    @ResponseBody
    public es.ucm.fdi.iu.service.SambaAutoConfigService.SambaShareStatus getPrinterSambaStatus(
            @PathVariable Long id) {
        try {
            Printer printer = entityManager.find(Printer.class, id);
            if (printer != null) {
                return sambaAutoConfigService.getShareStatus(printer);
            }
        } catch (Exception e) {
            log.error("Error obteniendo estado Samba", e);
        }
        return null;
    }
    
                @PostMapping("/delete-printer")
    @Transactional
    public String deletePrinter(@RequestParam Long id, RedirectAttributes ra) {
        try {
            Printer printer = entityManager.find(Printer.class, id);
            if (printer != null) {
                String printerName = printer.getAlias();
                boolean wasShared = printer.isSharedViaSamba();
                
                log.info("========================================");
                log.info("ELIMINANDO IMPRESORA");
                log.info("========================================");
                log.info("Impresora: {}", printerName);
                log.info("Compartida vía Samba: {}", wasShared);
                
                // Si estaba compartida vía Samba, eliminar el compartido primero
                if (wasShared) {
                    log.info("Eliminando compartido Samba antes de eliminar impresora...");
                    sambaAutoConfigService.unconfigurePrinter(printer);
                }
                
                // Remover impresora de todos los departamentos
                List<Department> departments = entityManager.createQuery(
                    "SELECT d FROM Department d JOIN d.printers p WHERE p.id = :printerId", 
                    Department.class)
                    .setParameter("printerId", id)
                    .getResultList();
                
                for (Department dept : departments) {
                    dept.getPrinters().remove(printer);
                }
                
                                // Cerrar puerto IPP si existe
                if (printer.getIppPort() != null) {
                    log.info("Cerrando puerto IPP {} para impresora {}", printer.getIppPort(), printerName);
                    multiPortIppServerService.closePrinterPort(id);
                }
                
                // Eliminar impresora
                entityManager.remove(printer);
                ra.addFlashAttribute("success", "Impresora \"" + printerName + "\" eliminada exitosamente");
                log.info("✅ Impresora eliminada: {} (ID: {})", printerName, id);
                log.info("========================================");
            } else {
                ra.addFlashAttribute("error", "Impresora no encontrada");
            }
        } catch (Exception e) {
                        log.error("❌ Error deleting printer", e);
            ra.addFlashAttribute("error", "Error al eliminar impresora: " + e.getMessage());
        }
        return "redirect:/admin/printers";
    }

    // ========== GESTIÓN DE GRUPOS ==========
    
    @PostMapping("/addgroup")
    @Transactional
    public String addGroup(
            @RequestParam String name,
            @RequestParam Long userId,
            RedirectAttributes ra) {
        try {
            User user = entityManager.find(User.class, userId);
            if (user == null) {
                ra.addFlashAttribute("error", "Usuario no encontrado");
                return "redirect:/admin";
            }
            
            PGroup group = new PGroup();
            group.setName(name);
            group.setInstance(user);
            
            entityManager.persist(group);
            ra.addFlashAttribute("success", "Grupo creado exitosamente");
        } catch (Exception e) {
            log.error("Error creating group", e);
            ra.addFlashAttribute("error", "Error al crear grupo: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/editgroup")
    @Transactional
    public String editGroup(
            @RequestParam Long id,
            @RequestParam String name,
            RedirectAttributes ra) {
        try {
            PGroup group = entityManager.find(PGroup.class, id);
            if (group != null) {
                group.setName(name);
                ra.addFlashAttribute("success", "Grupo actualizado exitosamente");
            }
        } catch (Exception e) {
            log.error("Error editing group", e);
            ra.addFlashAttribute("error", "Error al editar grupo: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/deletegroup")
    @Transactional
    public String deleteGroup(@RequestParam Long id, RedirectAttributes ra) {
        try {
            PGroup group = entityManager.find(PGroup.class, id);
            if (group != null) {
                entityManager.remove(group);
                ra.addFlashAttribute("success", "Grupo eliminado exitosamente");
            }
        } catch (Exception e) {
            log.error("Error deleting group", e);
            ra.addFlashAttribute("error", "Error al eliminar grupo: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/addprintertogroup")
    @Transactional
    public String addPrinterToGroup(
            @RequestParam Long groupId,
            @RequestParam Long printerId,
            RedirectAttributes ra) {
        try {
            PGroup group = entityManager.find(PGroup.class, groupId);
            Printer printer = entityManager.find(Printer.class, printerId);
            
            if (group != null && printer != null) {
                if (!group.getPrinters().contains(printer)) {
                    group.getPrinters().add(printer);
                    printer.getGroups().add(group);
                    ra.addFlashAttribute("success", "Impresora agregada al grupo");
                } else {
                    ra.addFlashAttribute("error", "La impresora ya está en el grupo");
                }
            }
        } catch (Exception e) {
            log.error("Error adding printer to group", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/removeprinterfromgroup")
    @Transactional
    public String removePrinterFromGroup(
            @RequestParam Long groupId,
            @RequestParam Long printerId,
            RedirectAttributes ra) {
        try {
            PGroup group = entityManager.find(PGroup.class, groupId);
            Printer printer = entityManager.find(Printer.class, printerId);
            
            if (group != null && printer != null) {
                group.getPrinters().remove(printer);
                printer.getGroups().remove(group);
                ra.addFlashAttribute("success", "Impresora removida del grupo");
            }
        } catch (Exception e) {
            log.error("Error removing printer from group", e);
                        ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin";
    }
    
            // ========== GESTIÓN DE RANGOS DE RED / VLANs ==========
    
        @PostMapping("/add-network-range")
    @Transactional
    public String addNetworkRange(
            @RequestParam String name,
            @RequestParam String cidrRange,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer vlanId,
            @RequestParam(required = false) String returnTo,
            HttpSession session,
            RedirectAttributes ra,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            // Validar formato CIDR
            if (!NetworkRange.isValidCIDR(cidrRange)) {
                ra.addFlashAttribute("error", "Formato CIDR inválido. Use formato: 192.168.1.0/24");
                return determineRedirectUrl(returnTo, request);
            }
            
            User user = (User) session.getAttribute("u");
            
            NetworkRange range = new NetworkRange();
            range.setName(name);
            range.setCidrRange(cidrRange);
            range.setDescription(description);
            range.setVlanId(vlanId);
            range.setActive(true);
            range.setInstance(user);
            
            entityManager.persist(range);
            ra.addFlashAttribute("success", "Rango de red agregado: " + name);
        } catch (Exception e) {
            log.error("Error adding network range", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return determineRedirectUrl(returnTo, request);
    }
    
        @PostMapping("/edit-network-range")
    @Transactional
    public String editNetworkRange(
            @RequestParam Long id,
            @RequestParam String name,
            @RequestParam String cidrRange,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer vlanId,
            @RequestParam(defaultValue = "false") boolean active,
            @RequestParam(required = false) String returnTo,
            RedirectAttributes ra,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            // Validar formato CIDR
            if (!NetworkRange.isValidCIDR(cidrRange)) {
                ra.addFlashAttribute("error", "Formato CIDR inválido. Use formato: 192.168.1.0/24");
                return determineRedirectUrl(returnTo, request);
            }
            
            NetworkRange range = entityManager.find(NetworkRange.class, id);
            if (range != null) {
                range.setName(name);
                range.setCidrRange(cidrRange);
                range.setDescription(description);
                range.setVlanId(vlanId);
                range.setActive(active);
                
                ra.addFlashAttribute("success", "Rango de red actualizado: " + name);
            } else {
                ra.addFlashAttribute("error", "Rango de red no encontrado");
            }
        } catch (Exception e) {
            log.error("Error editing network range", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return determineRedirectUrl(returnTo, request);
    }
    
    private String determineRedirectUrl(String returnTo, jakarta.servlet.http.HttpServletRequest request) {
        // Si se especifica returnTo, usarlo
        if (returnTo != null && !returnTo.isEmpty()) {
            return "redirect:" + returnTo;
        }
        
        // Intentar obtener el referer
        String referer = request.getHeader("Referer");
        if (referer != null && referer.contains("/printers")) {
            return "redirect:/admin/printers";
        }
        
        // Por defecto, regresar a departments
        return "redirect:/admin/departments";
    }
    
        @PostMapping("/toggle-network-range")
    @Transactional
    public String toggleNetworkRange(
            @RequestParam long id,
            @RequestParam(required = false) String returnTo,
            RedirectAttributes ra,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            NetworkRange range = entityManager.find(NetworkRange.class, id);
            if (range != null) {
                range.setActive(!range.isActive());
                ra.addFlashAttribute("success", 
                    "Rango " + (range.isActive() ? "activado" : "desactivado"));
            }
        } catch (Exception e) {
            log.error("Error toggling network range", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return determineRedirectUrl(returnTo, request);
    }
    
        @PostMapping("/delete-network-range")
    @Transactional
    public String deleteNetworkRange(
            @RequestParam long id,
            @RequestParam(required = false) String returnTo,
            RedirectAttributes ra,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            NetworkRange range = entityManager.find(NetworkRange.class, id);
            if (range != null) {
                String name = range.getName();
                entityManager.remove(range);
                ra.addFlashAttribute("success", "Rango eliminado: " + name);
            }
        } catch (Exception e) {
            log.error("Error deleting network range", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
                return determineRedirectUrl(returnTo, request);
    }
    
    /**
     * Endpoint para testear conectividad con cliente USB de impresora compartida
     */
    @GetMapping("/test-usb-printer")
    @ResponseBody
    public Map<String, Object> testUsbPrinter(@RequestParam Long id) {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> diagnostic = new HashMap<>();
        
        try {
            Printer printer = entityManager.find(Printer.class, id);
            
            if (printer == null) {
                response.put("success", false);
                response.put("message", "Impresora no encontrada");
                return response;
            }
            
            // Verificar que es una impresora USB compartida
            boolean isSharedUSB = printer.getLocation() != null && 
                                 printer.getLocation().contains("Compartida-USB");
            
            if (!isSharedUSB) {
                response.put("success", false);
                response.put("message", "Esta no es una impresora USB compartida");
                return response;
            }
            
            String clientIp = printer.getIp();
            int clientPort = 631;
            
            diagnostic.put("host", clientIp);
            diagnostic.put("port", clientPort);
            
            log.info("🧪 Testeando cliente USB: {}:{}", clientIp, clientPort);
            
            // Test 1: Verificar alcance (ping)
            long startPing = System.currentTimeMillis();
            boolean reachable = false;
            try {
                InetAddress address = InetAddress.getByName(clientIp);
                reachable = address.isReachable(2000);
                long pingTime = System.currentTimeMillis() - startPing;
                diagnostic.put("latency", pingTime);
                
                if (reachable) {
                    log.info("  ✅ Host {} alcanzable ({}ms)", clientIp, pingTime);
                } else {
                    log.warn("  ⚠️ Host {} no responde a ping", clientIp);
                }
            } catch (Exception e) {
                log.warn("  ⚠️ Error en ping: {}", e.getMessage());
            }
            
            // Test 2: Verificar puerto 631 abierto
            long startConnect = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(clientIp, clientPort), 5000);
                long connectTime = System.currentTimeMillis() - startConnect;
                
                log.info("  ✅ Puerto {} abierto (conexión en {}ms)", clientPort, connectTime);
                diagnostic.put("status", "Conectado");
                diagnostic.put("connectionTime", connectTime);
                
                response.put("success", true);
                response.put("message", "Cliente USB conectado y funcionando correctamente");
                response.put("diagnostic", diagnostic);
                
            } catch (ConnectException e) {
                log.error("  ❌ Puerto {} cerrado o rechazado: {}", clientPort, e.getMessage());
                diagnostic.put("status", "Desconectado");
                diagnostic.put("error", e.getMessage());
                
                response.put("success", false);
                response.put("message", "Cliente USB no está ejecutándose o puerto 631 cerrado");
                response.put("diagnostic", diagnostic);
                
            } catch (SocketTimeoutException e) {
                log.error("  ❌ Timeout conectando a puerto {}", clientPort);
                diagnostic.put("status", "Timeout");
                diagnostic.put("error", "Timeout después de 5 segundos");
                
                response.put("success", false);
                response.put("message", "Timeout al conectar - Cliente USB puede estar sobrecargado o red lenta");
                response.put("diagnostic", diagnostic);
            }
            
        } catch (Exception e) {
            log.error("❌ Error en test de cliente USB", e);
            response.put("success", false);
            response.put("message", "Error interno: " + e.getMessage());
        }
        
        return response;
    }
}
