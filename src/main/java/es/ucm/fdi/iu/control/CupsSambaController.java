package es.ucm.fdi.iu.control;

import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.model.User;
import es.ucm.fdi.iu.model.Job;
import es.ucm.fdi.iu.service.CupsService;
import es.ucm.fdi.iu.service.SambaService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlador para integración CUPS + Samba
 * 
 * Proporciona endpoints para:
 * - Sincronizar impresoras entre sistema, CUPS y Samba
 * - Gestionar trabajos de impresión
 * - Configurar compartidos Samba
 * - Monitorear estado del sistema
 */
@Controller
@RequestMapping("/cups-samba")
public class CupsSambaController {

    private static final Logger log = LogManager.getLogger(CupsSambaController.class);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CupsService cupsService;

    @Autowired
    private SambaService sambaService;

    /**
     * Panel principal de gestión CUPS/Samba
     */
    @GetMapping("/")
    public String index(Model model, HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("u");
            
            // Estado de servicios
            boolean cupsRunning = cupsService.isCupsRunning();
            boolean sambaRunning = sambaService.isSambaRunning();
            
            // Impresoras del sistema
            List<Printer> systemPrinters = entityManager.createQuery(
                "SELECT p FROM Printer p", Printer.class
            ).getResultList();
            
            // Impresoras en CUPS
            List<CupsService.CupsPrinterInfo> cupsPrinters = cupsService.listCupsPrinters();
            
            // Recursos compartidos Samba
            List<SambaService.SambaShare> sambaShares = sambaService.listShares();
            
            // Trabajos activos
            List<CupsService.CupsJobInfo> cupsJobs = cupsService.listJobs();
            
            // Usuarios Samba
            List<SambaService.SambaUser> sambaUsers = sambaService.listSambaUsers();
            
            model.addAttribute("cupsRunning", cupsRunning);
            model.addAttribute("sambaRunning", sambaRunning);
            model.addAttribute("systemPrinters", systemPrinters);
            model.addAttribute("cupsPrinters", cupsPrinters);
            model.addAttribute("sambaShares", sambaShares);
            model.addAttribute("cupsJobs", cupsJobs);
            model.addAttribute("sambaUsers", sambaUsers);
            
            log.info("Panel CUPS/Samba cargado - CUPS: {}, Samba: {}", cupsRunning, sambaRunning);
            return "cups-samba/dashboard";
            
        } catch (Exception e) {
            log.error("Error al cargar panel CUPS/Samba", e);
            model.addAttribute("error", "Error al cargar el panel: " + e.getMessage());
            return "error";
        }
    }

    /**
     * Sincroniza impresoras del sistema con CUPS
     */
    @PostMapping("/sync-to-cups")
    @Transactional
    public String syncToCups(@RequestParam long printerId, 
                             @RequestParam String deviceUri,
                             @RequestParam String driver,
                             RedirectAttributes ra) {
        try {
            Printer printer = entityManager.find(Printer.class, printerId);
            
            if (printer == null) {
                ra.addFlashAttribute("error", "Impresora no encontrada");
                return "redirect:/cups-samba/";
            }
            
            // Agregar a CUPS
            boolean success = cupsService.addPrinter(printer, deviceUri, driver);
            
            if (success) {
                ra.addFlashAttribute("success", 
                    "Impresora " + printer.getAlias() + " agregada a CUPS");
            } else {
                ra.addFlashAttribute("error", "Error al agregar impresora a CUPS");
            }
            
        } catch (Exception e) {
            log.error("Error al sincronizar con CUPS", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Importa impresoras desde CUPS al sistema
     */
    @PostMapping("/import-from-cups")
    @Transactional
    public String importFromCups(HttpSession session, RedirectAttributes ra) {
        try {
            User currentUser = (User) session.getAttribute("u");
            List<CupsService.CupsPrinterInfo> cupsPrinters = cupsService.listCupsPrinters();
            
            int imported = 0;
            for (CupsService.CupsPrinterInfo cupsInfo : cupsPrinters) {
                // Verificar si ya existe
                List<Printer> existing = entityManager.createQuery(
                    "SELECT p FROM Printer p WHERE p.alias = :alias", Printer.class
                ).setParameter("alias", cupsInfo.name).getResultList();
                
                if (existing.isEmpty()) {
                    // Crear nueva impresora
                    Printer printer = new Printer();
                    printer.setAlias(cupsInfo.name);
                    printer.setModel(cupsInfo.makeModel != null ? cupsInfo.makeModel : "Desconocido");
                    printer.setLocation(cupsInfo.location != null ? cupsInfo.location : "");
                    
                    // Extraer IP del device URI si es posible
                    if (cupsInfo.deviceUri != null && cupsInfo.deviceUri.contains("://")) {
                        String[] parts = cupsInfo.deviceUri.split("://");
                        if (parts.length > 1) {
                            String host = parts[1].split("/")[0].split(":")[0];
                            if (isValidIp(host)) {
                                printer.setIp(host);
                            }
                        }
                    }
                    
                    printer.setInstance(currentUser);
                    printer.setInk(100);
                    printer.setPaper(100);
                    
                    entityManager.persist(printer);
                    imported++;
                }
            }
            
            ra.addFlashAttribute("success", imported + " impresoras importadas desde CUPS");
            
        } catch (Exception e) {
            log.error("Error al importar desde CUPS", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Comparte una impresora CUPS vía Samba
     */
    @PostMapping("/share-via-samba")
    @Transactional
    public String shareViaSamba(@RequestParam long printerId,
                                @RequestParam(required = false) boolean guestAccess,
                                @RequestParam(required = false) String allowedUsers,
                                RedirectAttributes ra) {
        try {
            Printer printer = entityManager.find(Printer.class, printerId);
            
            if (printer == null) {
                ra.addFlashAttribute("error", "Impresora no encontrada");
                return "redirect:/cups-samba/";
            }
            
            // Parsear usuarios permitidos
            List<String> userList = new ArrayList<>();
            if (allowedUsers != null && !allowedUsers.isEmpty()) {
                for (String user : allowedUsers.split(",")) {
                    userList.add(user.trim());
                }
            }
            
            // Compartir vía Samba
            boolean success = sambaService.sharePrinter(printer, guestAccess, userList);
            
            if (success) {
                ra.addFlashAttribute("success", 
                    "Impresora " + printer.getAlias() + " compartida vía Samba");
            } else {
                ra.addFlashAttribute("error", "Error al compartir impresora vía Samba");
            }
            
        } catch (Exception e) {
            log.error("Error al compartir vía Samba", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Elimina compartido Samba de una impresora
     */
    @PostMapping("/unshare-from-samba")
    @Transactional
    public String unshareFromSamba(@RequestParam String printerName, RedirectAttributes ra) {
        try {
            boolean success = sambaService.unsharePrinter(printerName);
            
            if (success) {
                ra.addFlashAttribute("success", "Compartido eliminado");
            } else {
                ra.addFlashAttribute("error", "Error al eliminar compartido");
            }
            
        } catch (Exception e) {
            log.error("Error al eliminar compartido", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Envía un trabajo de impresión a CUPS
     */
    @PostMapping("/submit-job")
    @Transactional
    public String submitJob(@RequestParam long printerId,
                           @RequestParam("file") MultipartFile file,
                           @RequestParam(required = false) String copies,
                           @RequestParam(required = false) String sides,
                           @RequestParam(required = false) String orientation,
                           HttpSession session,
                           RedirectAttributes ra) {
        try {
            User currentUser = (User) session.getAttribute("u");
            Printer printer = entityManager.find(Printer.class, printerId);
            
            if (printer == null) {
                ra.addFlashAttribute("error", "Impresora no encontrada");
                return "redirect:/cups-samba/";
            }
            
            if (file.isEmpty()) {
                ra.addFlashAttribute("error", "Archivo vacío");
                return "redirect:/cups-samba/";
            }
            
            // Guardar archivo temporalmente
            String uploadDir = System.getProperty("java.io.tmpdir");
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(uploadDir, fileName);
            Files.write(filePath, file.getBytes());
            
            // Preparar opciones
            Map<String, String> options = new HashMap<>();
            if (copies != null && !copies.isEmpty()) {
                options.put("copies", copies);
            }
            if (sides != null && !sides.isEmpty()) {
                options.put("sides", sides);
            }
            if (orientation != null && !orientation.isEmpty()) {
                options.put("orientation-requested", orientation);
            }
            
            // Enviar a CUPS
            boolean success = cupsService.submitPrintJob(
                printer.getAlias(),
                filePath.toString(),
                currentUser.getUsername(),
                options
            );
            
            if (success) {
                // Registrar en base de datos
                Job job = new Job();
                job.setFileName(file.getOriginalFilename());
                job.setOwner(currentUser.getUsername());
                job.setPrinter(printer);
                job.setInstance(currentUser);
                entityManager.persist(job);
                
                ra.addFlashAttribute("success", "Trabajo enviado a impresión");
            } else {
                ra.addFlashAttribute("error", "Error al enviar trabajo");
            }
            
            // Eliminar archivo temporal
            Files.deleteIfExists(filePath);
            
        } catch (Exception e) {
            log.error("Error al enviar trabajo", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Cancela un trabajo de impresión
     */
    @PostMapping("/cancel-job")
    @Transactional
    public String cancelJob(@RequestParam String printerName,
                           @RequestParam int jobId,
                           RedirectAttributes ra) {
        try {
            boolean success = cupsService.cancelJob(printerName, jobId);
            
            if (success) {
                ra.addFlashAttribute("success", "Trabajo cancelado");
            } else {
                ra.addFlashAttribute("error", "Error al cancelar trabajo");
            }
            
        } catch (Exception e) {
            log.error("Error al cancelar trabajo", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Agrega usuario a Samba
     */
    @PostMapping("/add-samba-user")
    @Transactional
    public String addSambaUser(@RequestParam String username,
                              @RequestParam String password,
                              RedirectAttributes ra) {
        try {
            boolean success = sambaService.addSambaUser(username, password);
            
            if (success) {
                ra.addFlashAttribute("success", "Usuario Samba agregado: " + username);
            } else {
                ra.addFlashAttribute("error", "Error al agregar usuario Samba");
            }
            
        } catch (Exception e) {
            log.error("Error al agregar usuario Samba", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Elimina usuario de Samba
     */
    @PostMapping("/remove-samba-user")
    @Transactional
    public String removeSambaUser(@RequestParam String username, RedirectAttributes ra) {
        try {
            boolean success = sambaService.removeSambaUser(username);
            
            if (success) {
                ra.addFlashAttribute("success", "Usuario Samba eliminado: " + username);
            } else {
                ra.addFlashAttribute("error", "Error al eliminar usuario Samba");
            }
            
        } catch (Exception e) {
            log.error("Error al eliminar usuario Samba", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Reinicia servicios CUPS
     */
    @PostMapping("/restart-cups")
    public String restartCups(RedirectAttributes ra) {
        try {
            boolean success = cupsService.restartCups();
            
            if (success) {
                ra.addFlashAttribute("success", "CUPS reiniciado exitosamente");
            } else {
                ra.addFlashAttribute("error", "Error al reiniciar CUPS");
            }
            
        } catch (Exception e) {
            log.error("Error al reiniciar CUPS", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * Reinicia servicios Samba
     */
    @PostMapping("/restart-samba")
    public String restartSamba(RedirectAttributes ra) {
        try {
            boolean success = sambaService.restartSamba();
            
            if (success) {
                ra.addFlashAttribute("success", "Samba reiniciado exitosamente");
            } else {
                ra.addFlashAttribute("error", "Error al reiniciar Samba");
            }
            
        } catch (Exception e) {
            log.error("Error al reiniciar Samba", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        
        return "redirect:/cups-samba/";
    }

    /**
     * API: Obtiene estadísticas de una impresora
     */
    @GetMapping("/api/printer-stats/{printerName}")
    @ResponseBody
    public Map<String, Object> getPrinterStats(@PathVariable String printerName) {
        try {
            return cupsService.getPrinterStatistics(printerName);
        } catch (Exception e) {
            log.error("Error al obtener estadísticas", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
    }

    /**
     * API: Estado de servicios
     */
    @GetMapping("/api/services-status")
    @ResponseBody
    public Map<String, Boolean> getServicesStatus() {
        Map<String, Boolean> status = new HashMap<>();
        status.put("cups", cupsService.isCupsRunning());
        status.put("samba", sambaService.isSambaRunning());
        return status;
    }

    /**
     * API: Lista trabajos activos
     */
    @GetMapping("/api/active-jobs")
    @ResponseBody
    public List<CupsService.CupsJobInfo> getActiveJobs() {
        return cupsService.listJobs();
    }

    /**
     * Valida formato de IP
     */
    private boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
