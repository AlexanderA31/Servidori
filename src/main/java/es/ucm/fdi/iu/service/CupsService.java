package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Job;
import es.ucm.fdi.iu.model.Printer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio para interactuar con CUPS (Common Unix Printing System)
 * 
 * DEPRECADO: Este servicio NO se usa en el enfoque actual.
 * El sistema usa un servidor IPP embebido en Java (IppServerService).
 * 
 * Este servicio se mantiene solo para referencia o integración futura opcional.
 * Si deseas usarlo, debes detener CUPS del sistema o cambiar el puerto del servidor IPP.
 */
@Deprecated
@Service
public class CupsService {

    private static final Logger log = LogManager.getLogger(CupsService.class);

    /**
     * Representa información de una impresora CUPS
     */
    public static class CupsPrinterInfo {
        public String name;
        public String description;
        public String location;
        public String makeModel;
        public String deviceUri;
        public String state;
        public String stateReason;
        public boolean accepting;
        public int jobs;
    }

    /**
     * Representa información de un trabajo CUPS
     */
    public static class CupsJobInfo {
        public int id;
        public String printer;
        public String user;
        public String title;
        public String state;
        public int size;
        public int pages;
        public String submittedTime;
    }

    /**
     * Lista todas las impresoras configuradas en CUPS
     */
    public List<CupsPrinterInfo> listCupsPrinters() {
        List<CupsPrinterInfo> printers = new ArrayList<>();
        
        try {
            // Ejecutar lpstat -v para obtener lista de impresoras
            Process process = Runtime.getRuntime().exec("lpstat -v");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                // Formato: device for PrinterName: uri
                Pattern pattern = Pattern.compile("device for (.+): (.+)");
                Matcher matcher = pattern.matcher(line);
                
                if (matcher.find()) {
                    CupsPrinterInfo info = new CupsPrinterInfo();
                    info.name = matcher.group(1);
                    info.deviceUri = matcher.group(2);
                    
                    // Obtener más información de esta impresora
                    enrichPrinterInfo(info);
                    printers.add(info);
                }
            }
            
            process.waitFor();
            reader.close();
            
            log.info("Encontradas {} impresoras en CUPS", printers.size());
            
        } catch (Exception e) {
            log.error("Error al listar impresoras CUPS", e);
        }
        
        return printers;
    }

    /**
     * Enriquece la información de una impresora con lpstat
     */
    private void enrichPrinterInfo(CupsPrinterInfo info) {
        try {
            // Ejecutar lpstat -p para obtener estado
            Process process = Runtime.getRuntime().exec("lpstat -p " + info.name);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            String line = reader.readLine();
            if (line != null) {
                if (line.contains("idle")) {
                    info.state = "idle";
                } else if (line.contains("printing")) {
                    info.state = "processing";
                } else if (line.contains("disabled")) {
                    info.state = "stopped";
                }
            }
            
            process.waitFor();
            reader.close();
            
            // Ejecutar lpstat -a para verificar si acepta trabajos
            process = Runtime.getRuntime().exec("lpstat -a " + info.name);
            reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            line = reader.readLine();
            if (line != null) {
                info.accepting = line.contains("accepting requests");
            }
            
            process.waitFor();
            reader.close();
            
        } catch (Exception e) {
            log.warn("No se pudo enriquecer información para " + info.name, e);
        }
    }

    /**
     * Obtiene el estado actual de una impresora específica
     */
    public CupsPrinterInfo getPrinterStatus(String printerName) {
        try {
            List<CupsPrinterInfo> printers = listCupsPrinters();
            for (CupsPrinterInfo printer : printers) {
                if (printer.name.equals(printerName)) {
                    return printer;
                }
            }
        } catch (Exception e) {
            log.error("Error al obtener estado de impresora " + printerName, e);
        }
        return null;
    }

    /**
     * Lista todos los trabajos de impresión
     */
    public List<CupsJobInfo> listJobs() {
        List<CupsJobInfo> jobs = new ArrayList<>();
        
        try {
            // Ejecutar lpstat -o para listar trabajos
            Process process = Runtime.getRuntime().exec("lpstat -o");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                // Formato: PrinterName-JobID user JobSize fecha hora estado
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    CupsJobInfo job = new CupsJobInfo();
                    
                    // Extraer printer y job ID
                    String[] printerJob = parts[0].split("-");
                    if (printerJob.length >= 2) {
                        job.printer = printerJob[0];
                        try {
                            job.id = Integer.parseInt(printerJob[1]);
                        } catch (NumberFormatException e) {
                            continue;
                        }
                    }
                    
                    job.user = parts[1];
                    try {
                        job.size = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        job.size = 0;
                    }
                    
                    jobs.add(job);
                }
            }
            
            process.waitFor();
            reader.close();
            
            log.info("Encontrados {} trabajos en CUPS", jobs.size());
            
        } catch (Exception e) {
            log.error("Error al listar trabajos CUPS", e);
        }
        
        return jobs;
    }

    /**
     * Envía un archivo a CUPS para imprimir
     */
    public boolean submitPrintJob(String printerName, String filePath, String username, Map<String, String> options) {
        try {
            // Construir comando lp
            StringBuilder command = new StringBuilder("lp");
            command.append(" -d ").append(printerName);
            command.append(" -U ").append(username);
            
            // Agregar opciones
            if (options != null) {
                for (Map.Entry<String, String> entry : options.entrySet()) {
                    command.append(" -o ").append(entry.getKey()).append("=").append(entry.getValue());
                }
            }
            
            command.append(" ").append(filePath);
            
            log.info("Ejecutando: {}", command);
            
            Process process = Runtime.getRuntime().exec(command.toString());
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("CUPS output: {}", line);
            }
            
            int exitCode = process.waitFor();
            reader.close();
            
            if (exitCode == 0) {
                log.info("Trabajo enviado exitosamente a {}", printerName);
                return true;
            } else {
                log.error("Error al enviar trabajo, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al enviar trabajo de impresión", e);
            return false;
        }
    }

    /**
     * Cancela un trabajo de impresión
     */
    public boolean cancelJob(String printerName, int jobId) {
        try {
            String command = "cancel " + printerName + "-" + jobId;
            log.info("Ejecutando: {}", command);
            
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("Trabajo {} cancelado exitosamente", jobId);
                return true;
            } else {
                log.error("Error al cancelar trabajo {}, código: {}", jobId, exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al cancelar trabajo", e);
            return false;
        }
    }

    /**
     * Agrega una nueva impresora a CUPS
     */
    public boolean addPrinter(Printer printer, String deviceUri, String driver) {
        try {
            // Usar lpadmin para agregar impresora
            String command = String.format(
                "lpadmin -p %s -E -v %s -m %s -L \"%s\" -D \"%s\"",
                printer.getAlias(),
                deviceUri,
                driver,
                printer.getLocation(),
                printer.getModel()
            );
            
            log.info("Agregando impresora: {}", command);
            
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("Impresora {} agregada exitosamente", printer.getAlias());
                
                // Habilitar la impresora
                process = Runtime.getRuntime().exec("cupsenable " + printer.getAlias());
                process.waitFor();
                
                // Configurar para aceptar trabajos
                process = Runtime.getRuntime().exec("cupsaccept " + printer.getAlias());
                process.waitFor();
                
                return true;
            } else {
                log.error("Error al agregar impresora, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al agregar impresora a CUPS", e);
            return false;
        }
    }

    /**
     * Elimina una impresora de CUPS
     */
    public boolean removePrinter(String printerName) {
        try {
            String command = "lpadmin -x " + printerName;
            log.info("Eliminando impresora: {}", command);
            
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("Impresora {} eliminada exitosamente", printerName);
                return true;
            } else {
                log.error("Error al eliminar impresora, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al eliminar impresora de CUPS", e);
            return false;
        }
    }

    /**
     * Obtiene estadísticas de uso de una impresora
     */
    public Map<String, Object> getPrinterStatistics(String printerName) {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // Contar trabajos completados
            Process process = Runtime.getRuntime().exec("lpstat -W completed -o " + printerName);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            int completedJobs = 0;
            while (reader.readLine() != null) {
                completedJobs++;
            }
            
            stats.put("completedJobs", completedJobs);
            process.waitFor();
            reader.close();
            
            // Contar trabajos pendientes
            process = Runtime.getRuntime().exec("lpstat -o " + printerName);
            reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            int pendingJobs = 0;
            while (reader.readLine() != null) {
                pendingJobs++;
            }
            
            stats.put("pendingJobs", pendingJobs);
            process.waitFor();
            reader.close();
            
            log.info("Estadísticas para {}: {}", printerName, stats);
            
        } catch (Exception e) {
            log.error("Error al obtener estadísticas", e);
        }
        
        return stats;
    }

    /**
     * Verifica si CUPS está ejecutándose
     */
    public boolean isCupsRunning() {
        try {
            Process process = Runtime.getRuntime().exec("systemctl is-active cups");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            String line = reader.readLine();
            boolean running = "active".equals(line);
            
            process.waitFor();
            reader.close();
            
            log.info("Estado CUPS: {}", running ? "activo" : "inactivo");
            return running;
            
        } catch (Exception e) {
            log.error("Error al verificar estado de CUPS", e);
            return false;
        }
    }

    /**
     * Reinicia el servicio CUPS
     */
    public boolean restartCups() {
        try {
            log.info("Reiniciando servicio CUPS...");
            Process process = Runtime.getRuntime().exec("systemctl restart cups");
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("CUPS reiniciado exitosamente");
                return true;
            } else {
                log.error("Error al reiniciar CUPS, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al reiniciar CUPS", e);
            return false;
        }
    }

    /**
     * Obtiene los drivers PPD disponibles
     */
    public List<String> getAvailableDrivers() {
        List<String> drivers = new ArrayList<>();
        
        try {
            Process process = Runtime.getRuntime().exec("lpinfo -m");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                drivers.add(line.trim());
            }
            
            process.waitFor();
            reader.close();
            
            log.info("Encontrados {} drivers disponibles", drivers.size());
            
        } catch (Exception e) {
            log.error("Error al listar drivers", e);
        }
        
        return drivers;
    }
}
