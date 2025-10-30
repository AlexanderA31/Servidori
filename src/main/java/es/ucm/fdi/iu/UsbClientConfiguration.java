package es.ucm.fdi.iu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Configuración específica para el modo cliente USB
 * Excluye componentes que requieren base de datos
 */
@Configuration
@ConditionalOnProperty(name = "app.mode", havingValue = "usb-client")
@ComponentScan(
    basePackages = "es.ucm.fdi.iu",
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
            DataInitializer.class,
            IwUserDetailsService.class
        }),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "es\\.ucm\\.fdi\\.iu\\.control\\..*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "es\\.ucm\\.fdi\\.iu\\.service\\.Print.*(?<!UsbClientService)"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "es\\.ucm\\.fdi\\.iu\\.service\\.Samba.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "es\\.ucm\\.fdi\\.iu\\.service\\.Multi.*")
    }
)
public class UsbClientConfiguration {
    // Configuración vacía, solo define los filtros de escaneo
}
