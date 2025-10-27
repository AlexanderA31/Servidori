package es.ucm.fdi.iu.service;

import es.ucm.fdi.iu.model.Printer;
import es.ucm.fdi.iu.model.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio para interactuar con Samba
 * 
 * DEPRECADO: Este servicio NO se usa en el enfoque actual.
 * El sistema usa un servidor IPP embebido que funciona directamente con clientes Windows.
 * 
 * Para conectarse a impresoras compartidas Windows, usa SmbShareService (cliente SMB en Java puro).
 * Este servicio se mantiene solo para referencia o integración futura opcional.
 */
@Deprecated
@Service
public class SambaService {

    private static final Logger log = LogManager.getLogger(SambaService.class);
    private static final String SMB_CONF = "/etc/samba/smb.conf";

    /**
     * Representa información de un recurso compartido Samba
     */
    public static class SambaShare {
        public String name;
        public String path;
        public String comment;
        public boolean browseable;
        public boolean guestOk;
        public boolean writable;
        public boolean printable;
        public List<String> validUsers;
    }

    /**
     * Representa un usuario Samba
     */
    public static class SambaUser {
        public String username;
        public boolean enabled;
        public String fullName;
    }

    /**
     * Comparte una impresora CUPS vía Samba
     */
    public boolean sharePrinter(Printer printer, boolean guestAccess, List<String> allowedUsers) {
        try {
            log.info("Compartiendo impresora {} vía Samba", printer.getAlias());
            
            // Leer configuración actual
            List<String> config = readSmbConf();
            
            // Buscar si ya existe la sección de la impresora
            String sectionName = "[" + printer.getAlias() + "]";
            int sectionIndex = findSection(config, sectionName);
            
            // Crear nueva configuración de la impresora
            List<String> printerConfig = new ArrayList<>();
            printerConfig.add(sectionName);
            printerConfig.add("    comment = " + printer.getModel() + " en " + printer.getLocation());
            printerConfig.add("    path = /var/spool/samba");
            printerConfig.add("    printable = yes");
            printerConfig.add("    browseable = yes");
            printerConfig.add("    guest ok = " + (guestAccess ? "yes" : "no"));
            
            if (allowedUsers != null && !allowedUsers.isEmpty()) {
                printerConfig.add("    valid users = " + String.join(", ", allowedUsers));
            }
            
            printerConfig.add("    create mask = 0700");
            printerConfig.add("");
            
            // Si existe, reemplazar; si no, agregar al final
            if (sectionIndex >= 0) {
                int endIndex = findSectionEnd(config, sectionIndex);
                for (int i = endIndex - 1; i >= sectionIndex; i--) {
                    config.remove(i);
                }
                config.addAll(sectionIndex, printerConfig);
            } else {
                config.addAll(printerConfig);
            }
            
            // Escribir configuración
            writeSmbConf(config);
            
            // Recargar configuración de Samba
            reloadSamba();
            
            log.info("Impresora {} compartida exitosamente", printer.getAlias());
            return true;
            
        } catch (Exception e) {
            log.error("Error al compartir impresora", e);
            return false;
        }
    }

    /**
     * Elimina el compartido de una impresora
     */
    public boolean unsharePrinter(String printerName) {
        try {
            log.info("Eliminando compartido de impresora {}", printerName);
            
            List<String> config = readSmbConf();
            String sectionName = "[" + printerName + "]";
            int sectionIndex = findSection(config, sectionName);
            
            if (sectionIndex >= 0) {
                int endIndex = findSectionEnd(config, sectionIndex);
                for (int i = endIndex - 1; i >= sectionIndex; i--) {
                    config.remove(i);
                }
                
                writeSmbConf(config);
                reloadSamba();
                
                log.info("Compartido eliminado exitosamente");
                return true;
            } else {
                log.warn("No se encontró sección para {}", printerName);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al eliminar compartido", e);
            return false;
        }
    }

    /**
     * Agrega un usuario a Samba
     */
    public boolean addSambaUser(String username, String password) {
        try {
            log.info("Agregando usuario Samba: {}", username);
            
            // Verificar si el usuario del sistema existe
            Process checkUser = Runtime.getRuntime().exec("id -u " + username);
            int exitCode = checkUser.waitFor();
            
            if (exitCode != 0) {
                // Crear usuario del sistema
                log.info("Creando usuario del sistema: {}", username);
                Process createUser = Runtime.getRuntime().exec(
                    "useradd -M -s /usr/sbin/nologin " + username
                );
                createUser.waitFor();
            }
            
            // Agregar usuario a Samba
            // Nota: Esto requiere que el proceso tenga permisos sudo
            ProcessBuilder pb = new ProcessBuilder(
                "smbpasswd", "-a", "-s", username
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Enviar password al stdin
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream())
            );
            writer.write(password + "\n" + password + "\n");
            writer.flush();
            writer.close();
            
            exitCode = process.waitFor();
            
            if (exitCode == 0) {
                // Habilitar usuario
                process = Runtime.getRuntime().exec("smbpasswd -e " + username);
                process.waitFor();
                
                log.info("Usuario Samba {} agregado exitosamente", username);
                return true;
            } else {
                log.error("Error al agregar usuario Samba, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al agregar usuario Samba", e);
            return false;
        }
    }

    /**
     * Elimina un usuario de Samba
     */
    public boolean removeSambaUser(String username) {
        try {
            log.info("Eliminando usuario Samba: {}", username);
            
            Process process = Runtime.getRuntime().exec("smbpasswd -x " + username);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("Usuario Samba {} eliminado exitosamente", username);
                return true;
            } else {
                log.error("Error al eliminar usuario Samba, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al eliminar usuario Samba", e);
            return false;
        }
    }

    /**
     * Lista usuarios Samba
     */
    public List<SambaUser> listSambaUsers() {
        List<SambaUser> users = new ArrayList<>();
        
        try {
            Process process = Runtime.getRuntime().exec("pdbedit -L");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                // Formato: username:uid:full name
                String[] parts = line.split(":");
                if (parts.length >= 1) {
                    SambaUser user = new SambaUser();
                    user.username = parts[0];
                    user.enabled = true;
                    if (parts.length >= 3) {
                        user.fullName = parts[2];
                    }
                    users.add(user);
                }
            }
            
            process.waitFor();
            reader.close();
            
            log.info("Encontrados {} usuarios Samba", users.size());
            
        } catch (Exception e) {
            log.error("Error al listar usuarios Samba", e);
        }
        
        return users;
    }

    /**
     * Lista recursos compartidos de Samba
     */
    public List<SambaShare> listShares() {
        List<SambaShare> shares = new ArrayList<>();
        
        try {
            List<String> config = readSmbConf();
            
            for (int i = 0; i < config.size(); i++) {
                String line = config.get(i).trim();
                
                if (line.startsWith("[") && line.endsWith("]") && 
                    !line.equals("[global]") && !line.equals("[printers]") && !line.equals("[print$]")) {
                    
                    SambaShare share = new SambaShare();
                    share.name = line.substring(1, line.length() - 1);
                    share.validUsers = new ArrayList<>();
                    
                    // Leer propiedades de la sección
                    for (int j = i + 1; j < config.size(); j++) {
                        String prop = config.get(j).trim();
                        
                        if (prop.startsWith("[")) break; // Nueva sección
                        
                        if (prop.startsWith("path =")) {
                            share.path = prop.substring(7).trim();
                        } else if (prop.startsWith("comment =")) {
                            share.comment = prop.substring(10).trim();
                        } else if (prop.startsWith("browseable =")) {
                            share.browseable = prop.contains("yes");
                        } else if (prop.startsWith("guest ok =")) {
                            share.guestOk = prop.contains("yes");
                        } else if (prop.startsWith("writable =")) {
                            share.writable = prop.contains("yes");
                        } else if (prop.startsWith("printable =")) {
                            share.printable = prop.contains("yes");
                        } else if (prop.startsWith("valid users =")) {
                            String users = prop.substring(14).trim();
                            for (String user : users.split(",")) {
                                share.validUsers.add(user.trim());
                            }
                        }
                    }
                    
                    shares.add(share);
                }
            }
            
            log.info("Encontrados {} recursos compartidos", shares.size());
            
        } catch (Exception e) {
            log.error("Error al listar recursos compartidos", e);
        }
        
        return shares;
    }

    /**
     * Verifica si Samba está ejecutándose
     */
    public boolean isSambaRunning() {
        try {
            Process process = Runtime.getRuntime().exec("systemctl is-active smbd");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String line = reader.readLine();
            boolean running = "active".equals(line);
            
            process.waitFor();
            reader.close();
            
            log.info("Estado Samba: {}", running ? "activo" : "inactivo");
            return running;
            
        } catch (Exception e) {
            log.error("Error al verificar estado de Samba", e);
            return false;
        }
    }

    /**
     * Reinicia los servicios Samba
     */
    public boolean restartSamba() {
        try {
            log.info("Reiniciando servicios Samba...");
            
            Process process = Runtime.getRuntime().exec("systemctl restart smbd nmbd");
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("Samba reiniciado exitosamente");
                return true;
            } else {
                log.error("Error al reiniciar Samba, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al reiniciar Samba", e);
            return false;
        }
    }

    /**
     * Recarga la configuración de Samba sin reiniciar
     */
    private boolean reloadSamba() {
        try {
            log.info("Recargando configuración Samba...");
            
            Process process = Runtime.getRuntime().exec("systemctl reload smbd");
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("Configuración recargada exitosamente");
                return true;
            } else {
                log.error("Error al recargar configuración, código: {}", exitCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error al recargar configuración", e);
            return false;
        }
    }

    /**
     * Lee el archivo smb.conf
     */
    private List<String> readSmbConf() throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(SMB_CONF));
        
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        
        reader.close();
        return lines;
    }

    /**
     * Escribe el archivo smb.conf
     */
    private void writeSmbConf(List<String> lines) throws IOException {
        // Crear backup
        File backup = new File(SMB_CONF + ".backup");
        File original = new File(SMB_CONF);
        
        if (original.exists()) {
            Files.copy(original.toPath(), backup.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        
        // Escribir nueva configuración
        BufferedWriter writer = new BufferedWriter(new FileWriter(SMB_CONF));
        
        for (String line : lines) {
            writer.write(line);
            writer.newLine();
        }
        
        writer.close();
        log.info("Archivo smb.conf actualizado");
    }

    /**
     * Encuentra el índice de una sección en la configuración
     */
    private int findSection(List<String> config, String sectionName) {
        for (int i = 0; i < config.size(); i++) {
            if (config.get(i).trim().equals(sectionName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Encuentra el final de una sección
     */
    private int findSectionEnd(List<String> config, int startIndex) {
        for (int i = startIndex + 1; i < config.size(); i++) {
            String line = config.get(i).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                return i;
            }
        }
        return config.size();
    }

    /**
     * Configura la sección global para compartir impresoras
     */
    public boolean configureGlobalPrinting() {
        try {
            log.info("Configurando sección global para impresión");
            
            List<String> config = readSmbConf();
            int globalIndex = findSection(config, "[global]");
            
            if (globalIndex < 0) {
                log.error("No se encontró sección [global]");
                return false;
            }
            
            // Buscar final de sección global
            int endIndex = findSectionEnd(config, globalIndex);
            
            // Verificar y agregar configuraciones necesarias
            Map<String, String> requiredSettings = new HashMap<>();
            requiredSettings.put("printing", "cups");
            requiredSettings.put("printcap name", "cups");
            requiredSettings.put("load printers", "yes");
            
            for (Map.Entry<String, String> setting : requiredSettings.entrySet()) {
                boolean found = false;
                
                for (int i = globalIndex + 1; i < endIndex; i++) {
                    if (config.get(i).trim().startsWith(setting.getKey() + " =")) {
                        found = true;
                        break;
                    }
                }
                
                if (!found) {
                    config.add(endIndex, "    " + setting.getKey() + " = " + setting.getValue());
                    endIndex++;
                }
            }
            
            writeSmbConf(config);
            reloadSamba();
            
            log.info("Configuración global actualizada");
            return true;
            
        } catch (Exception e) {
            log.error("Error al configurar sección global", e);
            return false;
        }
    }
}
