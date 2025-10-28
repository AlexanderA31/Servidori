package es.ucm.fdi.iu;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration.
 * 
 * Most security configuration will appear in this file, but according to 
 * https://spring.io/guides/topicals/spring-security-architecture/, it is not
 * a bad idea to also use method security (via @Secured annotations in methods) 
 * 
 * @author mfreire
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Autowired
	private Environment env;

		@Autowired
	private LoginSuccessHandler loginSuccessHandler;
	
	@Autowired
	private AuthenticationLogger authenticationLogger;

	@Bean
	public WebSecurityCustomizer webSecurityCustomizer() {
		String debugProperty = env.getProperty("es.ucm.fdi.debug");
		if (debugProperty != null && Boolean.parseBoolean(debugProperty.toLowerCase())) {
			// allows access to h2 console iff running under debug mode
			return (web) -> web.ignoring().requestMatchers("/h2-console/**");
		}
		return (web) -> {};
	}

	/**
	 * Main security configuration.
	 * 
	 * The first rule that matches will be followed - so if a rule decides to grant access
	 * to a resource, a later rule cannot deny that access, and vice-versa.
	 * 
	 * To disable security entirely, just add a .requestMatchers("**").permitAll() 
	 * as a first rule. Note that this may break an application that expects to have
	 * login information available.
	 */
	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
	    	    http
			.csrf(csrf -> csrf
				.ignoringRequestMatchers("/api/**")
			)
	        	        	        	        	        	        .authorizeHttpRequests(auth -> auth
	                                    .requestMatchers("/css/**", "/js/**", "/img/**", "/", "/index.html", "/error").permitAll()
				.requestMatchers("/api/**").permitAll()             // <-- public api access
				.requestMatchers("/login").permitAll()
	            // Dashboard - accesible para ADMIN y TECNICO
	            .requestMatchers("/admin/", "/admin").hasAnyRole("ADMIN", "TECNICO")
	            // Colas de Impresión - accesible para ADMIN y TECNICO
	            .requestMatchers("/admin/printqueues/**").hasAnyRole("ADMIN", "TECNICO")
	            // Compartir Impresoras - accesible para ADMIN y TECNICO
	            .requestMatchers("/admin/share-printers/**").hasAnyRole("ADMIN", "TECNICO")
	            // Resto de rutas admin - solo ADMIN
	            .requestMatchers("/admin/**").hasRole("ADMIN")
	            .requestMatchers("/departments/**").hasRole("ADMIN") // <-- departments management
	            .anyRequest().authenticated()
	        )
						.formLogin(form -> form
				.loginPage("/login")
				.permitAll()
				.successHandler(loginSuccessHandler) // <-- called when login Ok; can redirect
				.failureHandler(authenticationLogger) // <-- called when login fails
			);
		return http.build();
	}
	
		
	/**
	 * Declares a PasswordEncoder bean.
	 * 
	 * This allows you to write, in any part of Spring-managed code, 
	 * `@Autowired PasswordEncoder passwordEncoder`, and have it initialized
	 * with the result of this method. 
	 */
	@Bean
	public PasswordEncoder getPasswordEncoder() {
		// by default in Spring Security 5, a wrapped new BCryptPasswordEncoder();
		return PasswordEncoderFactories.createDelegatingPasswordEncoder(); 
	}	
	
	/**
	 * Declares a springDataUserDetailsService bean.
	 * 
	 * This is used to translate from Spring Security users to in-application users.
	 */
	@Bean
	public IwUserDetailsService springDataUserDetailsService() {
		return new IwUserDetailsService();
	} 
	
	/**
	 * Declares an AuthenticationManager bean.
	 * 
	 * This can be used to auto-login into the site after creating new users, for example.
	 */
	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
		return authConfig.getAuthenticationManager();
	}
}
