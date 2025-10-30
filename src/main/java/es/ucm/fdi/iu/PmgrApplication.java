package es.ucm.fdi.iu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
	basePackages = "es.ucm.fdi.iu",
	excludeFilters = @ComponentScan.Filter(
		type = FilterType.ASPECTJ,
		pattern = "es.ucm.fdi.iu.control..*"
	)
)
public class PmgrApplication {

	public static void main(String[] args) {
		// Detectar si es modo usb-client y excluir componentes innecesarios
		boolean isUsbClient = false;
		for (String arg : args) {
			if (arg.contains("usb-client")) {
				isUsbClient = true;
				break;
			}
		}
		
		if (isUsbClient) {
			System.out.println("🖨️  Iniciando en modo Cliente USB");
			System.out.println("📦 Cargando solo servicios necesarios para cliente USB");
		}
		
		SpringApplication.run(PmgrApplication.class, args);
	}

}
