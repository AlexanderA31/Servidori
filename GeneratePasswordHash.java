import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GeneratePasswordHash {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        
        // Generar hash para contraseña "admin123"
        String password = "admin123";
        String hashedPassword = encoder.encode(password);
        
        System.out.println("Password: " + password);
        System.out.println("BCrypt Hash: {bcrypt}" + hashedPassword);
        System.out.println("");
        System.out.println("SQL para import.sql:");
        System.out.println("INSERT INTO user_table (id, enabled, roles, username, password)");
        System.out.println("VALUES (1, TRUE, 'ADMIN,USER', 'admin', '{bcrypt}" + hashedPassword + "');");
    }
}
