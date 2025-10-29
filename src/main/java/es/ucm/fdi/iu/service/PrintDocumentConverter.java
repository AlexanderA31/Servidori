package es.ucm.fdi.iu.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Servicio para convertir documentos a formatos compatibles con impresoras
 * 
 * Convierte texto plano UTF-8 a formatos que las impresoras pueden procesar:
 * - PCL (Printer Command Language) para impresoras HP y compatibles
 * - PostScript para impresoras que lo soporten
 * - ESC/P para impresoras Epson
 */
@Service
@Slf4j
public class PrintDocumentConverter {

    /**
     * Detecta el tipo de documento por sus magic numbers
     */
    public String detectDocumentType(byte[] data) {
        if (data == null || data.length < 4) {
            return "UNKNOWN";
        }
        
        // PDF: %PDF
        if (data[0] == 0x25 && data[1] == 0x50 && data[2] == 0x44 && data[3] == 0x46) {
            return "PDF";
        }
        
        // PostScript: %!
        if (data[0] == 0x25 && data[1] == 0x21) {
            return "PostScript";
        }
        
        // PCL: ESC E (reset) o ESC &
        if (data[0] == 0x1B && (data[1] == 0x45 || data[1] == 0x26)) {
            return "PCL";
        }
        
        // PNG
        if (data[0] == (byte)0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "PNG";
        }
        
        // JPEG
        if (data[0] == (byte)0xFF && data[1] == (byte)0xD8 && data[2] == (byte)0xFF) {
            return "JPEG";
        }
        
        // IPP request
        if (data.length >= 8 && data[0] >= 0x01 && data[0] <= 0x02) {
            return "IPP";
        }
        
        // Si es principalmente texto
        int printable = 0;
        for (int i = 0; i < Math.min(200, data.length); i++) {
            byte b = data[i];
            if ((b >= 32 && b < 127) || b == 9 || b == 10 || b == 13 || (b & 0x80) != 0) {
                printable++;
            }
        }
        
        if (printable > Math.min(180, data.length * 0.9)) {
            return "TEXT";
        }
        
        return "BINARY";
    }

    /**
     * Verifica si el documento ya está en formato de impresora
     */
    public boolean isPrinterReady(byte[] data) {
        String type = detectDocumentType(data);
        return type.equals("PDF") || type.equals("PostScript") || 
               type.equals("PCL") || type.equals("BINARY");
    }

    /**
     * Convierte texto plano UTF-8 a formato PCL con soporte para caracteres especiales
     * 
     * PCL (Printer Command Language) es ampliamente compatible con impresoras HP y otras
     */
    public byte[] convertTextToPCL(byte[] textData, String printerModel) throws Exception {
        ByteArrayOutputStream pcl = new ByteArrayOutputStream();
        
        // Detectar si es EPSON y usar ESC/P en su lugar
        if (printerModel != null && printerModel.toUpperCase().contains("EPSON")) {
            log.info("Impresora Epson detectada, usando formato ESC/P");
            return convertTextToESCP(textData);
        }
        
        log.info("Convirtiendo {} bytes de texto a PCL", textData.length);
        
        // Reset de impresora
        pcl.write(0x1B);
        pcl.write('E');
        
        // Configurar para ISO Latin-1 (mejor soporte para español)
        // Symbol Set: 8859-1 Latin 1 (9U)
        pcl.write(0x1B);
        pcl.write("(9U".getBytes());
        
        // Configurar orientación vertical
        pcl.write(0x1B);
        pcl.write("&l0O".getBytes());
        
        // Tamaño de papel: A4
        pcl.write(0x1B);
        pcl.write("&l26A".getBytes());
        
        // Márgenes (medio pulgada)
        pcl.write(0x1B);
        pcl.write("&l5E".getBytes()); // Margen superior
        
        // Fuente: Courier 12pt
        pcl.write(0x1B);
        pcl.write("(s0p12h10v0s0b3T".getBytes());
        
        // Convertir texto UTF-8 a Latin-1
        String text = new String(textData, StandardCharsets.UTF_8);
        byte[] latin1 = text.getBytes("ISO-8859-1");
        
        // Escribir contenido
        pcl.write(latin1);
        
        // Form feed (expulsar página)
        pcl.write(0x0C);
        
        // Reset final
        pcl.write(0x1B);
        pcl.write('E');
        
        log.info("Conversión PCL completada: {} bytes", pcl.size());
        
        return pcl.toByteArray();
    }

    /**
     * Convierte texto plano a formato ESC/P para impresoras Epson
     */
    public byte[] convertTextToESCP(byte[] textData) throws Exception {
        ByteArrayOutputStream escp = new ByteArrayOutputStream();
        
        log.info("Convirtiendo {} bytes de texto a ESC/P (Epson)", textData.length);
        
        // Reset de impresora ESC @
        escp.write(0x1B);
        escp.write('@');
        
        // Establecer conjunto de caracteres Latin-1
        // ESC R n (n=3 para Latin-1)
        escp.write(0x1B);
        escp.write('R');
        escp.write(3);
        
        // Configurar modo de caracteres internacionales (España)
        // ESC ( t 3 0 0 n (n=13 para España)
        escp.write(0x1B);
        escp.write('(');
        escp.write('t');
        escp.write(3);
        escp.write(0);
        escp.write(0);
        escp.write(13);
        
        // Espaciado de línea 1/6 pulgada (estándar)
        escp.write(0x1B);
        escp.write('2');
        
        // Convertir texto UTF-8 a Latin-1
        String text = new String(textData, StandardCharsets.UTF_8);
        byte[] latin1 = text.getBytes("ISO-8859-1");
        
        // Escribir contenido
        escp.write(latin1);
        
        // Form feed (expulsar página)
        escp.write(0x0C);
        
        // Reset final
        escp.write(0x1B);
        escp.write('@');
        
        log.info("Conversión ESC/P completada: {} bytes", escp.size());
        
        return escp.toByteArray();
    }

    /**
     * Convierte texto plano a formato PostScript
     */
    public byte[] convertTextToPostScript(byte[] textData) throws Exception {
        StringBuilder ps = new StringBuilder();
        
        log.info("Convirtiendo {} bytes de texto a PostScript", textData.length);
        
        // Encabezado PostScript
        ps.append("%!PS-Adobe-3.0\n");
        ps.append("%%Title: Print Job\n");
        ps.append("%%Creator: Print Queue Service\n");
        ps.append("%%Pages: 1\n");
        ps.append("%%EndComments\n\n");
        
        ps.append("%%Page: 1 1\n");
        ps.append("/Courier findfont 12 scalefont setfont\n");
        ps.append("72 720 moveto\n"); // Margen superior izquierdo
        
        // Convertir texto
        String text = new String(textData, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        
        int y = 720;
        for (String line : lines) {
            // Escapar caracteres especiales para PostScript
            String escaped = line.replace("\\", "\\\\")
                                .replace("(", "\\(")
                                .replace(")", "\\)")
                                .replace("\r", "");
            
            ps.append("72 ").append(y).append(" moveto\n");
            ps.append("(").append(escaped).append(") show\n");
            y -= 14; // Espaciado de línea
            
            if (y < 72) { // Nueva página si se acaba el espacio
                ps.append("showpage\n");
                ps.append("%%Page: 2 2\n");
                y = 720;
            }
        }
        
        ps.append("showpage\n");
        ps.append("%%EOF\n");
        
        log.info("Conversión PostScript completada: {} bytes", ps.length());
        
        return ps.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Procesa un documento para impresión
     * Si es texto plano, lo convierte al formato apropiado
     * Si ya está en formato de impresora, lo deja pasar
     */
    public byte[] processForPrinting(byte[] data, String printerModel) {
        try {
            String type = detectDocumentType(data);
            log.info("Tipo de documento detectado: {}", type);
            
            if (type.equals("TEXT")) {
                log.info("Texto plano detectado, convirtiendo para impresora: {}", printerModel);
                
                // Usar formato apropiado según la impresora
                if (printerModel != null && printerModel.toUpperCase().contains("EPSON")) {
                    return convertTextToESCP(data);
                } else if (printerModel != null && printerModel.toUpperCase().contains("HP")) {
                    return convertTextToPCL(data, printerModel);
                } else {
                    // Por defecto, intentar PCL (más universal)
                    return convertTextToPCL(data, printerModel);
                }
            } else if (type.equals("IPP")) {
                log.warn("Datos IPP recibidos, extrayendo contenido...");
                // TODO: Parsear IPP y extraer datos reales
                return data;
            } else {
                log.info("Documento ya en formato de impresora ({}), enviando directamente", type);
                return data;
            }
        } catch (Exception e) {
            log.error("Error procesando documento, enviando datos originales: {}", e.getMessage());
            return data;
        }
    }
}
