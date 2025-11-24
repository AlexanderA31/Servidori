import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public class FilePatcher {
    public static void main(String[] args) throws Exception {
        String file = "src/main/java/es/ucm/fdi/iu/service/UsbClientService.java";
        String content = Files.readString(Paths.get(file), StandardCharsets.UTF_8);
        
        // Reemplazar exec(String) con exec(String[])
        String oldPattern = "Process process = Runtime.getRuntime().exec(command);";
        String newCode = "// USAR ARRAY para evitar problemas con espacios\n" +
                        "            String[] commandArray = {\n" +
                        "                sumatraPath,\n" +
                        "                \"-print-to\",\n" +
                        "                localPrinterName,\n" +
                        "                \"-silent\",\n" +
                        "                file.toAbsolutePath().toString()\n" +
                        "            };\n" +
                        "            Process process = Runtime.getRuntime().exec(commandArray);";
        
        if (content.contains(oldPattern)) {
            content = content.replace(oldPattern, newCode);
            Files.writeString(Paths.get(file), content, StandardCharsets.UTF_8);
            System.out.println("✅ Archivo parcheado exitosamente");
        } else {
            System.out.println("⚠️ Patrón no encontrado");
        }
    }
}
