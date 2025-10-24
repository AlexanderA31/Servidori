package es.ucm.fdi.iu.control;

import es.ucm.fdi.iu.model.PGroup;
import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.model.User;
import es.ucm.fdi.iu.model.Department;
import es.ucm.fdi.iu.model.Computer;
import es.ucm.fdi.iu.model.NetworkRange;
import es.ucm.fdi.iu.service.PrinterDiscoveryService;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
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

                @GetMapping({"/", ""})
    @Transactional
    public String index(Model model, HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
                        // Cargar departamentos
            List<Department> departments = entityManager.createNamedQuery(
                    "Department.all", Department.class)
                    .getResultList();
            
            // Computadoras sin asignar
            List<Computer> unassignedComputers = entityManager.createQuery(
                    "SELECT c FROM Computer c WHERE c.department IS NULL", Computer.class)
                    .getResultList();
            
            // Todas las impresoras
            List<Printer> allPrinters = entityManager.createQuery(
                    "SELECT p FROM Printer p", Printer.class)
                    .getResultList();
            
                        // Rangos de red
            List<NetworkRange> networkRanges = entityManager.createNamedQuery(
                    "NetworkRange.all", NetworkRange.class)
                    .getResultList();
            
            model.addAttribute("departments", departments);
            model.addAttribute("unassignedComputers", unassignedComputers);
            model.addAttribute("allPrinters", allPrinters);
            model.addAttribute("networkRanges", networkRanges);
            
            log.info("Admin panel loaded successfully");
            return "departments";
        } catch (Exception e) {
            log.error("Error loading admin panel", e);
            model.addAttribute("error", "Error al cargar el panel: " + e.getMessage());
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
        return "redirect:/admin/";
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
                return "redirect:/admin/";
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
        return "redirect:/admin/";
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
                return "redirect:/admin/";
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
        return "redirect:/admin/";
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
        return "redirect:/admin/";
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
        return "redirect:/admin/";
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
        return "redirect:/admin/";
    }
    
    // ========== GESTIÓN DE USUARIOS (COMENTADO - NO SE USA) ==========
    
    @PostMapping("/adduser")
    @Transactional
    public String addUser(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam(required = false) String roles,
            RedirectAttributes ra) {
        try {
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setEnabled(true);
            user.setRoles(roles != null && !roles.isEmpty() ? roles : "USER");
            
            entityManager.persist(user);
            ra.addFlashAttribute("success", "Usuario creado exitosamente");
        } catch (Exception e) {
            log.error("Error creating user", e);
            ra.addFlashAttribute("error", "Error al crear usuario: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/edituser")
    @Transactional
    public String editUser(
            @RequestParam Long id,
            @RequestParam String username,
            @RequestParam(required = false) String password,
            @RequestParam String roles,
            RedirectAttributes ra) {
        try {
            User user = entityManager.find(User.class, id);
            if (user != null) {
                user.setUsername(username);
                if (password != null && !password.isEmpty()) {
                    user.setPassword(passwordEncoder.encode(password));
                }
                user.setRoles(roles);
                ra.addFlashAttribute("success", "Usuario actualizado exitosamente");
            }
        } catch (Exception e) {
            log.error("Error editing user", e);
            ra.addFlashAttribute("error", "Error al editar usuario: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/deleteuser")
    @Transactional
    public String deleteUser(@RequestParam Long id, RedirectAttributes ra) {
        try {
            User user = entityManager.find(User.class, id);
            if (user != null) {
                // No permitir eliminar al admin principal
                if (user.getId() == 1) {
                    ra.addFlashAttribute("error", "No se puede eliminar el administrador principal");
                } else {
                    entityManager.remove(user);
                    ra.addFlashAttribute("success", "Usuario eliminado exitosamente");
                }
            }
        } catch (Exception e) {
            log.error("Error deleting user", e);
            ra.addFlashAttribute("error", "Error al eliminar usuario: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/toggleuser")
    @Transactional
    public String toggleUser(@RequestParam Long id, RedirectAttributes ra) {
        try {
            User user = entityManager.find(User.class, id);
            if (user != null) {
                user.setEnabled(!user.isEnabled());
                ra.addFlashAttribute("success", "Estado del usuario actualizado");
            }
        } catch (Exception e) {
            log.error("Error toggling user", e);
            ra.addFlashAttribute("error", "Error al cambiar estado: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    // ========== DESCUBRIMIENTO DE IMPRESORAS ==========
    
        @PostMapping("/discover-printers")
    public String discoverPrinters(HttpSession session, RedirectAttributes ra) {
        try {
            User user = (User) session.getAttribute("u");
            
            // Ejecutar escaneo en background
            new Thread(() -> {
                try {
                    // Descubrir impresoras de red
                    List<PrinterDiscoveryService.DiscoveredPrinter> networkPrinters = 
                        printerDiscoveryService.discoverNetworkPrinters();
                    
                    // Descubrir impresoras locales
                    List<PrinterDiscoveryService.DiscoveredPrinter> localPrinters = 
                        printerDiscoveryService.discoverLocalPrinters();
                    
                    // Registrar impresoras encontradas
                    for (PrinterDiscoveryService.DiscoveredPrinter discovered : networkPrinters) {
                        printerDiscoveryService.registerDiscoveredPrinter(discovered, user);
                    }
                    
                    for (PrinterDiscoveryService.DiscoveredPrinter discovered : localPrinters) {
                        printerDiscoveryService.registerDiscoveredPrinter(discovered, user);
                    }
                    
                    log.info("Escaneo completado: {} impresoras de red, {} locales", 
                            networkPrinters.size(), localPrinters.size());
                } catch (Exception e) {
                    log.error("Error durante el escaneo", e);
                }
            }).start();
            
            ra.addFlashAttribute("info", "Escaneo iniciado en segundo plano. Actualiza la página para ver el progreso.");
        } catch (Exception e) {
            log.error("Error al iniciar escaneo", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/admin/?scanning=true";
    }
    
        @GetMapping("/scan-status")
    @ResponseBody
    public PrinterDiscoveryService.ScanStatus getScanStatus() {
        return printerDiscoveryService.getScanStatus();
    }
    
    // ========== GESTIÓN DE IMPRESORAS ==========
    
        @PostMapping("/addprinter")
    @Transactional
    public String addPrinter(
            @RequestParam String alias,
            @RequestParam String model,
            @RequestParam String location,
            @RequestParam String ip,
            @RequestParam(required = false) String deviceUri,
            @RequestParam(required = false) String driver,
            @RequestParam(required = false) String protocol,
            @RequestParam(defaultValue = "true") boolean addToCups,
            @RequestParam(defaultValue = "true") boolean shareViaSamba,
            @RequestParam Long userId,
            RedirectAttributes ra) {
        try {
            User user = entityManager.find(User.class, userId);
            if (user == null) {
                ra.addFlashAttribute("error", "Usuario no encontrado");
                return "redirect:/admin";
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
            printer.setInstance(user);
            printer.setInk(100);
            printer.setPaper(100);
            
            entityManager.persist(printer);
            entityManager.flush(); // Asegurar que se persiste antes de configurar
            
            // Auto-configurar en CUPS y Samba si se solicita
            if (addToCups) {
                PrinterAutoConfigService.ConfigurationResult result = 
                    printerAutoConfigService.autoConfigurePrinter(printer, shareViaSamba);
                
                if (result.isFullyConfigured()) {
                    StringBuilder msg = new StringBuilder("Impresora configurada exitosamente");
                    if (result.getIppUri() != null) {
                        msg.append("<br>IPP: ").append(result.getIppUri());
                    }
                    if (result.getSambaUri() != null) {
                        msg.append("<br>Samba: ").append(result.getSambaUri());
                    }
                    ra.addFlashAttribute("success", msg.toString());
                } else {
                    ra.addFlashAttribute("warning", "Impresora creada pero con errores en configuración");
                }
            } else {
                ra.addFlashAttribute("success", "Impresora creada (sin auto-configuración)");
            }
            
        } catch (Exception e) {
            log.error("Error creating printer", e);
            ra.addFlashAttribute("error", "Error al crear impresora: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/editprinter")
    @Transactional
    public String editPrinter(
            @RequestParam Long id,
            @RequestParam String alias,
            @RequestParam String model,
            @RequestParam String location,
            @RequestParam String ip,
            RedirectAttributes ra) {
        try {
            Printer printer = entityManager.find(Printer.class, id);
            if (printer != null) {
                printer.setAlias(alias);
                printer.setModel(model);
                printer.setLocation(location);
                printer.setIp(ip);
                ra.addFlashAttribute("success", "Impresora actualizada exitosamente");
            }
        } catch (Exception e) {
            log.error("Error editing printer", e);
            ra.addFlashAttribute("error", "Error al editar impresora: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/deleteprinter")
    @Transactional
    public String deletePrinter(@RequestParam Long id, RedirectAttributes ra) {
        try {
            Printer printer = entityManager.find(Printer.class, id);
            if (printer != null) {
                entityManager.remove(printer);
                ra.addFlashAttribute("success", "Impresora eliminada exitosamente");
            }
        } catch (Exception e) {
            log.error("Error deleting printer", e);
            ra.addFlashAttribute("error", "Error al eliminar impresora: " + e.getMessage());
        }
        return "redirect:/admin";
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
            HttpSession session,
            RedirectAttributes ra) {
        try {
            // Validar formato CIDR
            if (!NetworkRange.isValidCIDR(cidrRange)) {
                ra.addFlashAttribute("error", "Formato CIDR inválido. Use formato: 192.168.1.0/24");
                return "redirect:/admin/";
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
        return "redirect:/admin/";
    }
    
    @PostMapping("/toggle-network-range")
    @Transactional
    public String toggleNetworkRange(@RequestParam long id, RedirectAttributes ra) {
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
        return "redirect:/admin/";
    }
    
    @PostMapping("/delete-network-range")
    @Transactional
    public String deleteNetworkRange(@RequestParam long id, RedirectAttributes ra) {
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
        return "redirect:/admin/";
    }
}
