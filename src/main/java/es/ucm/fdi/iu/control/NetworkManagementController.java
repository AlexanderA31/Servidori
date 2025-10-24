package es.ucm.fdi.iu.control;

import es.ucm.fdi.iu.model.NetworkRange;
import es.ucm.fdi.iu.model.User;
import es.ucm.fdi.iu.service.PrinterDiscoveryService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Controlador para gestión de redes, VLANs y descubrimiento de impresoras
 */
@Controller
@RequestMapping("/network-management")
public class NetworkManagementController {

    private static final Logger log = LogManager.getLogger(NetworkManagementController.class);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PrinterDiscoveryService printerDiscoveryService;

    /**
     * Panel principal de gestión de redes
     */
    @GetMapping("/")
    public String index(Model model, HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            // Obtener todos los rangos de red
            List<NetworkRange> networkRanges = entityManager.createNamedQuery(
                "NetworkRange.all", NetworkRange.class
            ).getResultList();
            
            // Estado del escaneo actual
            PrinterDiscoveryService.ScanStatus scanStatus = printerDiscoveryService.getScanStatus();
            
            model.addAttribute("networkRanges", networkRanges);
            model.addAttribute("scanStatus", scanStatus);
            
            // Sugerencias de rangos comunes
            String[] commonRanges = {
                "192.168.0.0/24",
                "192.168.1.0/24",
                "10.0.0.0/24",
                "172.16.0.0/24"
            };
            model.addAttribute("commonRanges", commonRanges);
            
            log.info("Panel de gestión de redes cargado");
            return "network-management/dashboard";
            
        } catch (Exception e) {
            log.error("Error al cargar panel de gestión de redes", e);
            model.addAttribute("error", "Error al cargar el panel: " + e.getMessage());
            return "error";
        }
    }

    /**
     * Agregar nuevo rango de red / VLAN
     */
    @PostMapping("/add-range")
    @Transactional
    public String addNetworkRange(
            @RequestParam String name,
            @RequestParam String cidrRange,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer vlanId,
            @RequestParam(defaultValue = "true") boolean active,
            HttpSession session,
            RedirectAttributes ra) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            // Validar formato CIDR
            if (!NetworkRange.isValidCIDR(cidrRange)) {
                ra.addFlashAttribute("error", "Formato CIDR inválido. Use: 192.168.1.0/24");
                return "redirect:/network-management/";
            }
            
            // Verificar si ya existe un rango con el mismo CIDR
            List<NetworkRange> existing = entityManager.createQuery(
                "SELECT n FROM NetworkRange n WHERE n.cidrRange = :cidr", NetworkRange.class
            ).setParameter("cidr", cidrRange).getResultList();
            
            if (!existing.isEmpty()) {
                ra.addFlashAttribute("warning", "Ya existe un rango con ese CIDR: " + existing.get(0).getName());
                return "redirect:/network-management/";
            }
            
            // Crear nuevo rango
            NetworkRange range = new NetworkRange();
            range.setName(name);
            range.setCidrRange(cidrRange);
            range.setDescription(description);
            range.setVlanId(vlanId);
            range.setActive(active);
            range.setInstance(currentUser);
            
            entityManager.persist(range);
            
            log.info("Rango de red agregado: {} ({})", name, cidrRange);
            ra.addFlashAttribute("success", "Rango de red agregado: " + name);
            
        } catch (Exception e) {
            log.error("Error al agregar rango de red", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/network-management/";
    }

    /**
     * Editar rango de red existente
     */
    @PostMapping("/edit-range")
    @Transactional
    public String editNetworkRange(
            @RequestParam long id,
            @RequestParam String name,
            @RequestParam String cidrRange,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Integer vlanId,
            @RequestParam(defaultValue = "true") boolean active,
            RedirectAttributes ra) {
        try {
            NetworkRange range = entityManager.find(NetworkRange.class, id);
            
            if (range == null) {
                ra.addFlashAttribute("error", "Rango no encontrado");
                return "redirect:/network-management/";
            }
            
            // Validar nuevo CIDR si cambió
            if (!range.getCidrRange().equals(cidrRange) && !NetworkRange.isValidCIDR(cidrRange)) {
                ra.addFlashAttribute("error", "Formato CIDR inválido");
                return "redirect:/network-management/";
            }
            
            range.setName(name);
            range.setCidrRange(cidrRange);
            range.setDescription(description);
            range.setVlanId(vlanId);
            range.setActive(active);
            
            log.info("Rango de red actualizado: {} ({})", name, cidrRange);
            ra.addFlashAttribute("success", "Rango actualizado: " + name);
            
        } catch (Exception e) {
            log.error("Error al editar rango de red", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/network-management/";
    }

    /**
     * Eliminar rango de red
     */
    @PostMapping("/delete-range")
    @Transactional
    public String deleteNetworkRange(@RequestParam long id, RedirectAttributes ra) {
        try {
            NetworkRange range = entityManager.find(NetworkRange.class, id);
            
            if (range != null) {
                String name = range.getName();
                entityManager.remove(range);
                log.info("Rango de red eliminado: {}", name);
                ra.addFlashAttribute("success", "Rango eliminado: " + name);
            }
            
        } catch (Exception e) {
            log.error("Error al eliminar rango de red", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/network-management/";
    }

    /**
     * Activar/desactivar rango de red
     */
    @PostMapping("/toggle-range")
    @Transactional
    public String toggleNetworkRange(@RequestParam long id, RedirectAttributes ra) {
        try {
            NetworkRange range = entityManager.find(NetworkRange.class, id);
            
            if (range != null) {
                range.setActive(!range.isActive());
                String status = range.isActive() ? "activado" : "desactivado";
                log.info("Rango {} {}", range.getName(), status);
                ra.addFlashAttribute("success", "Rango " + status + ": " + range.getName());
            }
            
        } catch (Exception e) {
            log.error("Error al cambiar estado de rango", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/network-management/";
    }

    /**
     * Escanear un rango específico
     */
    @PostMapping("/scan-range")
    @Transactional
    public String scanNetworkRange(@RequestParam long id, 
                                   HttpSession session,
                                   RedirectAttributes ra) {
        try {
            NetworkRange range = entityManager.find(NetworkRange.class, id);
            User currentUser = (User) session.getAttribute("u");
            
            if (range == null) {
                ra.addFlashAttribute("error", "Rango no encontrado");
                return "redirect:/network-management/";
            }
            
            // Activar temporalmente si está desactivado
            boolean wasActive = range.isActive();
            if (!wasActive) {
                range.setActive(true);
            }
            
            // Iniciar escaneo en background
            new Thread(() -> {
                try {
                    log.info("Iniciando escaneo de rango: {} ({})", range.getName(), range.getCidrRange());
                    
                    // Descubrir impresoras
                    List<PrinterDiscoveryService.DiscoveredPrinter> discovered = 
                        printerDiscoveryService.discoverNetworkPrinters();
                    
                    // Actualizar estadísticas del rango
                    entityManager.getTransaction().begin();
                    NetworkRange updatedRange = entityManager.find(NetworkRange.class, id);
                    updatedRange.setLastScan(LocalDateTime.now());
                    updatedRange.setLastFoundPrinters(discovered.size());
                    
                    // Restaurar estado original
                    if (!wasActive) {
                        updatedRange.setActive(false);
                    }
                    
                    entityManager.getTransaction().commit();
                    
                    // Registrar impresoras encontradas
                    for (PrinterDiscoveryService.DiscoveredPrinter printer : discovered) {
                        printerDiscoveryService.registerDiscoveredPrinter(printer, currentUser);
                    }
                    
                    log.info("Escaneo completado: {} impresoras encontradas", discovered.size());
                    
                } catch (Exception e) {
                    log.error("Error durante escaneo de rango", e);
                }
            }).start();
            
            ra.addFlashAttribute("info", "Escaneo iniciado para: " + range.getName() + 
                ". Actualiza la página para ver el progreso.");
            
        } catch (Exception e) {
            log.error("Error al iniciar escaneo", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/network-management/";
    }

    /**
     * Escanear todos los rangos activos
     */
    @PostMapping("/scan-all")
    @Transactional
    public String scanAllRanges(HttpSession session, RedirectAttributes ra) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            List<NetworkRange> activeRanges = entityManager.createNamedQuery(
                "NetworkRange.active", NetworkRange.class
            ).getResultList();
            
            if (activeRanges.isEmpty()) {
                ra.addFlashAttribute("warning", "No hay rangos activos para escanear");
                return "redirect:/network-management/";
            }
            
            // Iniciar escaneo en background
            new Thread(() -> {
                try {
                    log.info("Iniciando escaneo de {} rangos activos", activeRanges.size());
                    
                    List<PrinterDiscoveryService.DiscoveredPrinter> allDiscovered = 
                        printerDiscoveryService.discoverNetworkPrinters();
                    
                    // Actualizar estadísticas
                    entityManager.getTransaction().begin();
                    LocalDateTime scanTime = LocalDateTime.now();
                    
                    for (NetworkRange range : activeRanges) {
                        NetworkRange updated = entityManager.find(NetworkRange.class, range.getId());
                        updated.setLastScan(scanTime);
                        // Contar impresoras en este rango (aproximado)
                        int count = (int) allDiscovered.stream()
                            .filter(p -> p.getIp() != null && isInRange(p.getIp(), range.getCidrRange()))
                            .count();
                        updated.setLastFoundPrinters(count);
                    }
                    
                    entityManager.getTransaction().commit();
                    
                    // Registrar todas las impresoras
                    for (PrinterDiscoveryService.DiscoveredPrinter printer : allDiscovered) {
                        printerDiscoveryService.registerDiscoveredPrinter(printer, currentUser);
                    }
                    
                    log.info("Escaneo completo: {} impresoras encontradas en total", 
                        allDiscovered.size());
                    
                } catch (Exception e) {
                    log.error("Error durante escaneo completo", e);
                }
            }).start();
            
            ra.addFlashAttribute("info", "Escaneo iniciado para " + activeRanges.size() + 
                " rangos. Actualiza la página para ver el progreso.");
            
        } catch (Exception e) {
            log.error("Error al iniciar escaneo completo", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/network-management/";
    }

    /**
     * API: Estado del escaneo actual
     */
    @GetMapping("/api/scan-status")
    @ResponseBody
    public PrinterDiscoveryService.ScanStatus getScanStatus() {
        return printerDiscoveryService.getScanStatus();
    }

    /**
     * API: Información detallada de un rango
     */
    @GetMapping("/api/range-info/{id}")
    @ResponseBody
    public NetworkRange.Transfer getRangeInfo(@PathVariable long id) {
        NetworkRange range = entityManager.find(NetworkRange.class, id);
        return range != null ? range.toTransfer() : null;
    }

    /**
     * API: Validar CIDR
     */
    @GetMapping("/api/validate-cidr")
    @ResponseBody
    public ValidationResult validateCIDR(@RequestParam String cidr) {
        boolean valid = NetworkRange.isValidCIDR(cidr);
        ValidationResult result = new ValidationResult();
        result.setValid(valid);
        
        if (valid) {
            result.setMessage("Formato válido");
            result.setEstimatedHosts(calculateHosts(cidr));
        } else {
            result.setMessage("Formato inválido. Use: 192.168.1.0/24");
        }
        
        return result;
    }

    /**
     * Verifica si una IP está en un rango CIDR
     */
    private boolean isInRange(String ip, String cidr) {
        try {
            String[] cidrParts = cidr.split("/");
            String networkAddress = cidrParts[0];
            int prefix = Integer.parseInt(cidrParts[1]);
            
            long ipValue = ipToLong(ip);
            long networkValue = ipToLong(networkAddress);
            long mask = (-1L << (32 - prefix)) & 0xFFFFFFFFL;
            
            return (ipValue & mask) == (networkValue & mask);
            
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convierte IP string a long
     */
    private long ipToLong(String ip) {
        String[] octets = ip.split("\\.");
        return (Long.parseLong(octets[0]) << 24) +
               (Long.parseLong(octets[1]) << 16) +
               (Long.parseLong(octets[2]) << 8) +
               Long.parseLong(octets[3]);
    }

    /**
     * Calcula número de hosts en un CIDR
     */
    private int calculateHosts(String cidr) {
        try {
            String[] parts = cidr.split("/");
            int prefix = Integer.parseInt(parts[1]);
            return (int) Math.pow(2, 32 - prefix) - 2;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Clase para resultado de validación
     */
    public static class ValidationResult {
        private boolean valid;
        private String message;
        private int estimatedHosts;

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public int getEstimatedHosts() { return estimatedHosts; }
        public void setEstimatedHosts(int estimatedHosts) { this.estimatedHosts = estimatedHosts; }
    }
}
