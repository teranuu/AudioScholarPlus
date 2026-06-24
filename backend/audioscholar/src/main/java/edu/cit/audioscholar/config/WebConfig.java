package edu.cit.audioscholar.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class WebConfig implements WebMvcConfigurer {
	@Value("#{'${app.cors.allowed-origins}'.split(',')}")
	private List<String> allowedOrigins;

	@Bean
	public ObjectMapper objectMapper() {
		return new Jackson2ObjectMapperBuilder().serializationInclusion(JsonInclude.Include.ALWAYS)
				.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).modules(new JavaTimeModule())
				.build();
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
				.allowedOrigins(allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isBlank())
						.toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH").allowedHeaders("Authorization",
						"Cache-Control", "Content-Type", "X-Requested-With", "Accept", "X-CSRF-TOKEN")
				.allowCredentials(true).exposedHeaders("Authorization").maxAge(3600);
	}
}
