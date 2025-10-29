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
            log.info("════════════════════════════════════════════════════════════");
            log.info("🔍 ANÁLISIS DE DOCUMENTO");
            log.info("════════════════════════════════════════════════════════════");
            log.info("   Tipo detectado: {}", type);
            log.info("   Tamaño: {} bytes", data.length);
            log.info("   Impresora: {}", printerModel);
            
            // Mostrar primeros bytes para debugging
            if (data.length >= 10) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(20, data.length); i++) {
                    hex.append(String.format("%02X ", data[i]));
                }
                log.info("   Primeros bytes: {}", hex.toString());
            }
            
            if (type.equals("PDF")) {
                log.info("✅ PDF detectado - Enviando directamente");
                log.info("   La impresora debe soportar impresión directa de PDF");
                log.info("════════════════════════════════════════════════════════════");
                return data;
                
            } else if (type.equals("PostScript")) {
                log.info("✅ PostScript detectado - Enviando directamente");
                log.info("════════════════════════════════════════════════════════════");
                return data;
                
            } else if (type.equals("PCL")) {
                log.info("✅ PCL detectado - Enviando directamente");
                log.info("════════════════════════════════════════════════════════════");
                return data;
                
            } else if (type.equals("TEXT")) {
                log.info("🔄 Texto plano detectado - Convirtiendo a formato de impresora");
                log.info("   Impresora destino: {}", printerModel);
                
                byte[] converted;
                // Usar formato apropiado según la impresora
                if (printerModel != null && printerModel.toUpperCase().contains("EPSON")) {
                    log.info("   Formato: ESC/P (Epson)");
                    converted = convertTextToESCP(data);
                } else if (printerModel != null && printerModel.toUpperCase().contains("HP")) {
                    log.info("   Formato: PCL (HP)");
                    converted = convertTextToPCL(data, printerModel);
                } else {
                    log.info("   Formato: PCL (Universal)");
                    converted = convertTextToPCL(data, printerModel);
                }
                
                log.info("✅ Conversión completada: {} bytes → {} bytes", data.length, converted.length);
                log.info("════════════════════════════════════════════════════════════");
                return converted;
                
            } else if (type.equals("IPP")) {
                log.warn("⚠️  Datos IPP/RAW recibidos - Intentando extraer contenido");
                // Buscar inicio de PDF dentro de los datos IPP
                byte[] extracted = extractDocumentFromIPP(data);
                if (extracted != null && extracted.length > 0) {
                    log.info("✅ Documento extraído de IPP: {} bytes", extracted.length);
                    log.info("════════════════════════════════════════════════════════════");
                    // Procesar recursivamente el documento extraído
                    return processForPrinting(extracted, printerModel);
                } else {
                    log.warn("⚠️  No se pudo extraer documento, enviando datos originales");
                    log.info("════════════════════════════════════════════════════════════");
                    return data;
                }
                
            } else {
                log.info("ℹ️  Tipo desconocido - Enviando directamente");
                log.info("   La impresora intentará interpretar el formato");
                log.info("════════════════════════════════════════════════════════════");
                return data;
            }
        } catch (Exception e) {
            log.error("❌ Error procesando documento: {}", e.getMessage(), e);
            log.error("   Enviando datos originales como fallback");
            log.info("════════════════════════════════════════════════════════════");
            return data;
        }
    }
    
    /**
     * Intenta extraer un documento (PDF, PostScript, etc.) de datos IPP
     */
    private byte[] extractDocumentFromIPP(byte[] data) {
        try {
            // Buscar magic number de PDF: %PDF
            for (int i = 0; i < data.length - 4; i++) {
                if (data[i] == 0x25 && data[i+1] == 0x50 && 
                    data[i+2] == 0x44 && data[i+3] == 0x46) {
                    log.info("🔍 PDF encontrado en posición {}", i);
                    byte[] pdf = new byte[data.length - i];
                    System.arraycopy(data, i, pdf, 0, pdf.length);
                    return pdf;
                }
            }
            
            // Buscar PostScript: %!
            for (int i = 0; i < data.length - 2; i++) {
                if (data[i] == 0x25 && data[i+1] == 0x21) {
                    log.info("🔍 PostScript encontrado en posición {}", i);
                    byte[] ps = new byte[data.length - i];
                    System.arraycopy(data, i, ps, 0, ps.length);
                    return ps;
                }
            }
            
            log.debug("No se encontró documento embebido en datos IPP");
            return null;
            
        } catch (Exception e) {
            log.error("Error extrayendo documento de IPP: {}", e.getMessage());
            return null;
        }
    }
}
