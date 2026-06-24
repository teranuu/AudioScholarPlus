package edu.cit.audioscholar.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import edu.cit.audioscholar.security.JwtDenylistFilter;
import edu.cit.audioscholar.security.JwtTokenProvider;
import edu.cit.audioscholar.service.OAuth2LoginSuccessHandler;
import edu.cit.audioscholar.service.TokenRevocationService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	@Autowired
	private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@Autowired
	private TokenRevocationService tokenRevocationService;

	@Value("#{'${app.cors.allowed-origins}'.split(',')}")
	private List<String> allowedOrigins;

	@Bean
	JwtDecoder jwtDecoder() {
		SecretKey secretKey = tokenProvider.getJwtSecretKey();
		if (secretKey == null) {
			throw new IllegalStateException(
					"JWT Secret Key cannot be null. Check JwtTokenProvider initialization and configuration.");
		}
		return NimbusJwtDecoder.withSecretKey(secretKey).build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
		jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
			String subject = jwt.getSubject();
			log.debug("Processing JWT for subject: {}", subject);

			// Try retrieving as a List (standard for JSON arrays in JWT)
			List<String> rolesList = jwt.getClaimAsStringList("roles");
			if (rolesList != null && !rolesList.isEmpty()) {
				log.debug("JWT roles claim parsed as List for user {}: {} (count: {})", subject, rolesList,
						rolesList.size());
				Collection<GrantedAuthority> authorities = rolesList.stream()
						.map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role)).collect(Collectors.toList());
				log.info("Extracted {} authorities from JWT (List format) for user {}: {}", authorities.size(), subject,
						authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList()));
				return authorities;
			}

			// Fallback: Try retrieving as a comma-separated String (legacy token support)
			String roles = jwt.getClaimAsString("roles");
			if (roles == null || roles.isEmpty()) {
				log.warn("No roles found in JWT for user {}. Token may need refresh.", subject);
				return Collections.emptyList();
			}
			log.debug("JWT roles claim parsed as String (legacy format) for user {}: '{}'", subject, roles);
			Collection<GrantedAuthority> authorities = Arrays.stream(roles.split(",")).map(String::trim)
					.filter(role -> !role.isEmpty()).map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
					.collect(Collectors.toList());
			log.info("Extracted {} authorities from JWT (String format) for user {}: {}", authorities.size(), subject,
					authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList()));
			return authorities;
		});
		return jwtAuthenticationConverter;
	}

	@Bean
	AccessDeniedHandler customAccessDeniedHandler() {
		return new AccessDeniedHandler() {
			@Override
			public void handle(HttpServletRequest request, HttpServletResponse response,
					AccessDeniedException accessDeniedException) throws IOException, ServletException {

				String requestUri = request.getRequestURI();
				String origin = request.getHeader("Origin");
				Authentication auth = SecurityContextHolder.getContext().getAuthentication();

				String username = auth != null ? auth.getName() : "anonymous";
				Collection<? extends GrantedAuthority> authorities = auth != null
						? auth.getAuthorities()
						: Collections.emptyList();

				log.warn("ACCESS DENIED - URI: {}, User: {}, Authorities: {}, Origin: {}, Error: {}", requestUri,
						username, authorities, origin, accessDeniedException.getMessage());

				// Check if this is an admin endpoint
				if (requestUri.startsWith("/api/admin")) {
					boolean hasAdminRole = authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
					log.error("Admin endpoint access denied for user {}. Has ROLE_ADMIN: {}. "
							+ "User needs to have ROLE_ADMIN in their profile and re-authenticate to get a fresh token.",
							username, hasAdminRole);
				}

				response.setStatus(HttpStatus.FORBIDDEN.value());
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.getWriter().write(String.format(
						"{\"error\": \"Forbidden\", \"message\": \"Access denied. Required authority not found.\", "
								+ "\"path\": \"%s\", \"userAuthorities\": %s}",
						requestUri, authorities.toString().replace("\"", "\\\"")));
			}
		};
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isBlank())
				.collect(Collectors.toList()));
		configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
		configuration.setAllowedHeaders(Arrays.asList("Authorization", "Cache-Control", "Content-Type",
				"X-Requested-With", "Accept", "X-CSRF-TOKEN"));
		configuration.setAllowCredentials(true);
		configuration.setExposedHeaders(List.of("Authorization"));
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	@Order(1)
	SecurityFilterChain statefulFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/", "/images/**", "/css/**", "/favicon.ico", "/login/**", "/oauth2/**", "/error",
				"/api/auth/token")
				.authorizeHttpRequests(authz -> authz
						.requestMatchers("/", "/images/**", "/css/**", "/favicon.ico", "/login/**", "/oauth2/**",
								"/error")
						.permitAll().requestMatchers("/api/auth/token").authenticated().anyRequest().denyAll())
				.oauth2Login(oauth2 -> oauth2.successHandler(oAuth2LoginSuccessHandler))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.csrf(AbstractHttpConfigurer::disable);

		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain statelessFilterChain(HttpSecurity http) throws Exception {
		JwtDenylistFilter jwtDenylistFilter = new JwtDenylistFilter(tokenRevocationService);

		http.securityMatcher("/api/**")
				.authorizeHttpRequests(authz -> authz
						.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/verify-firebase-token",
								"/api/auth/verify-google-token", "/api/auth/verify-github-code")
						.permitAll().requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
						.requestMatchers("/api/**").authenticated().anyRequest().denyAll())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(jwtAuthenticationConverter())))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterAfter(jwtDenylistFilter, BearerTokenAuthenticationFilter.class)
				.exceptionHandling(ex -> ex.accessDeniedHandler(customAccessDeniedHandler()))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.csrf(AbstractHttpConfigurer::disable);

		return http.build();
	}
}
