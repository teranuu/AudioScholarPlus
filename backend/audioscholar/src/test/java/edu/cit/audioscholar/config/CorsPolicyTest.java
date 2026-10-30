package edu.cit.audioscholar.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

class CorsPolicyTest {
	private static final String PRODUCTION_ORIGIN = "https://audio-scholar-plus-kappa.vercel.app";

	@Test
	void multiSourcePreflightAllowsIdempotencyHeaderFromProductionFrontend() throws Exception {
		SecurityConfig security = new SecurityConfig();
		ReflectionTestUtils.setField(security, "allowedOrigins",
				"https://audioscholar.vercel.app," + PRODUCTION_ORIGIN);
		CorsConfigurationSource source = security.corsConfigurationSource();
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/audio/multi-source");
		request.addHeader("Origin", PRODUCTION_ORIGIN);
		request.addHeader("Access-Control-Request-Method", "POST");
		request.addHeader("Access-Control-Request-Headers",
				"authorization, content-type, x-audioscholar-client, x-idempotency-key");
		MockHttpServletResponse response = new MockHttpServletResponse();
		CorsConfiguration configuration = source.getCorsConfiguration(request);

		boolean accepted = new DefaultCorsProcessor().processRequest(configuration, request, response);

		assertThat(accepted).isTrue();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(PRODUCTION_ORIGIN);
		assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("X-Idempotency-Key");
	}

	@Test
	void sharedPolicyKeepsMvcAndSecurityHeaderListsInSync() {
		assertThat(CorsPolicy.ALLOWED_HEADERS).contains("Authorization", "Content-Type", "X-AudioScholar-Client",
				"X-Idempotency-Key");
		assertThat(CorsPolicy.ALLOWED_METHODS).contains("OPTIONS", "POST");
	}
}
