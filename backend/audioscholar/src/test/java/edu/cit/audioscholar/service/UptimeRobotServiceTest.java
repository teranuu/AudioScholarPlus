package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import edu.cit.audioscholar.dto.Monitor;

class UptimeRobotServiceTest {

	@Test
	void getMonitorsReturnsEmptyListWhenDisabled() {
		UptimeRobotService service = new UptimeRobotService(WebClient.builder(), false, "token",
				"https://api.uptimerobot.com/v3", "", Duration.ofSeconds(1));

		assertTrue(service.getMonitors().isEmpty());
	}

	@Test
	void getMonitorsReturnsEmptyListWhenTokenMissing() {
		UptimeRobotService service = new UptimeRobotService(WebClient.builder(), true, "",
				"https://api.uptimerobot.com/v3", "", Duration.ofSeconds(1));

		assertTrue(service.getMonitors().isEmpty());
	}

	@Test
	void getMonitorsMapsV3DataResponse() throws IOException {
		try (TestHttpServer server = new TestHttpServer(200,
				"{\"data\":[{\"id\":123,\"friendlyName\":\"AudioScholar Render Backend\","
						+ "\"url\":\"https://audioscholarplus.onrender.com/actuator/health\","
						+ "\"status\":\"UP\",\"currentStateDuration\":3600}],\"nextLink\":null}")) {
			UptimeRobotService service = new UptimeRobotService(WebClient.builder(), true, "test-token",
					server.baseUrl(), "audioscholarplus.onrender.com", Duration.ofSeconds(2));

			List<Monitor> monitors = service.getMonitors();

			assertEquals(1, monitors.size());
			assertEquals(123, monitors.get(0).getId());
			assertEquals("AudioScholar Render Backend", monitors.get(0).getFriendlyName());
			assertEquals("Up", monitors.get(0).getStatusText());
			assertEquals("green", monitors.get(0).getStatusColor());
			assertEquals("/monitors?limit=50&url=audioscholarplus.onrender.com", server.getRequestUri());
			assertEquals("Bearer test-token", server.getAuthorizationHeader());
		}
	}

	@Test
	void getMonitorsReturnsEmptyListForApiErrors() throws IOException {
		try (TestHttpServer server = new TestHttpServer(429, "{\"message\":\"rate limit\"}")) {
			UptimeRobotService service = new UptimeRobotService(WebClient.builder(), true, "test-token",
					server.baseUrl(), "", Duration.ofSeconds(2));

			assertTrue(service.getMonitors().isEmpty());
		}
	}

	private static final class TestHttpServer implements AutoCloseable {

		private final HttpServer server;
		private final AtomicReference<String> requestUri = new AtomicReference<>();
		private final AtomicReference<String> authorizationHeader = new AtomicReference<>();

		private TestHttpServer(int statusCode, String responseBody) throws IOException {
			server = HttpServer.create(new InetSocketAddress(0), 0);
			server.createContext("/monitors", exchange -> handle(exchange, statusCode, responseBody));
			server.start();
		}

		private void handle(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
			requestUri.set(exchange.getRequestURI().toString());
			authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(statusCode, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		}

		private String baseUrl() {
			return "http://localhost:" + server.getAddress().getPort();
		}

		private String getRequestUri() {
			return requestUri.get();
		}

		private String getAuthorizationHeader() {
			return authorizationHeader.get();
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
