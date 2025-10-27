package es.ucm.fdi.iu.control;

import es.ucm.fdi.iu.model.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Controlador para gestión de departamentos/grupos
 * Un departamento contiene computadoras e impresoras
 */
@Controller
@RequestMapping("/departments")
@Slf4j
public class DepartmentController {

    @PersistenceContext
    private EntityManager em;

    @GetMapping("/")
    @Transactional
    public String index(Model model, HttpSession session) {
        User user = (User) session.getAttribute("u");
        
        List<Department> departments = em.createNamedQuery("Department.all", Department.class)
                .getResultList();
        
        List<Computer> unassignedComputers = em.createQuery(
                "SELECT c FROM Computer c WHERE c.department IS NULL", Computer.class)
                .getResultList();
        
        List<Printer> allPrinters = em.createQuery(
                "SELECT p FROM Printer p", Printer.class)
                .getResultList();
        
        // Cargar rangos de red
        List<NetworkRange> networkRanges = new java.util.ArrayList<>();
        try {
            networkRanges = em.createNamedQuery(
                    "NetworkRange.all", NetworkRange.class)
                    .getResultList();
        } catch (Exception e) {
            log.warn("No se pudieron cargar rangos de red: {}", e.getMessage());
        }
        
        model.addAttribute("departments", departments);
        model.addAttribute("unassignedComputers", unassignedComputers);
        model.addAttribute("allPrinters", allPrinters);
        model.addAttribute("networkRanges", networkRanges);
        
        return "departments";
    }

    @PostMapping("/create")
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
            
            em.persist(dept);
            ra.addFlashAttribute("success", "Departamento creado: " + name);
        } catch (Exception e) {
            log.error("Error creating department", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/departments/";
    }

    @PostMapping("/add-computer")
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
            Department dept = em.find(Department.class, departmentId);
            
            if (dept == null) {
                ra.addFlashAttribute("error", "Departamento no encontrado");
                return "redirect:/departments/";
            }
            
            Computer computer = new Computer();
            computer.setMacAddress(macAddress);
            computer.setName(name);
            computer.setHostname(hostname);
            computer.setAuthorized(true);
            computer.setDepartment(dept);
            computer.setInstance(user);
            
            em.persist(computer);
            ra.addFlashAttribute("success", "Computadora agregada al departamento");
        } catch (Exception e) {
            log.error("Error adding computer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/departments/";
    }

    @PostMapping("/assign-printer")
    @Transactional
    public String assignPrinterToDepartment(
            @RequestParam long departmentId,
            @RequestParam long printerId,
            RedirectAttributes ra) {
        try {
            Department dept = em.find(Department.class, departmentId);
            Printer printer = em.find(Printer.class, printerId);
            
            if (dept == null || printer == null) {
                ra.addFlashAttribute("error", "Departamento o impresora no encontrado");
                return "redirect:/departments/";
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
        return "redirect:/departments/";
    }

    @PostMapping("/remove-printer")
    @Transactional
    public String removePrinterFromDepartment(
            @RequestParam long departmentId,
            @RequestParam long printerId,
            RedirectAttributes ra) {
        try {
            Department dept = em.find(Department.class, departmentId);
            Printer printer = em.find(Printer.class, printerId);
            
            if (dept != null && printer != null) {
                dept.getPrinters().remove(printer);
                ra.addFlashAttribute("success", "Impresora removida del departamento");
            }
        } catch (Exception e) {
            log.error("Error removing printer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/departments/";
    }

    @PostMapping("/remove-computer")
    @Transactional
    public String removeComputerFromDepartment(
            @RequestParam long computerId,
            RedirectAttributes ra) {
        try {
            Computer computer = em.find(Computer.class, computerId);
            if (computer != null) {
                computer.setDepartment(null);
                ra.addFlashAttribute("success", "Computadora removida del departamento");
            }
        } catch (Exception e) {
            log.error("Error removing computer", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/departments/";
    }

    @PostMapping("/delete")
    @Transactional
    public String deleteDepartment(@RequestParam long id, RedirectAttributes ra) {
        try {
            Department dept = em.find(Department.class, id);
            if (dept != null) {
                String name = dept.getName();
                em.remove(dept);
                ra.addFlashAttribute("success", "Departamento eliminado: " + name);
            }
        } catch (Exception e) {
            log.error("Error deleting department", e);
            ra.addFlashAttribute("error", "Error: " + e.getMessage());
        }
        return "redirect:/departments/";
    }
}
