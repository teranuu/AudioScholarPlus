package edu.cit.audioscholar.service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import edu.cit.audioscholar.dto.Monitor;
import edu.cit.audioscholar.dto.UptimeRobotResponse;
import reactor.core.publisher.Mono;

@Service
public class UptimeRobotService {

	private static final Logger log = LoggerFactory.getLogger(UptimeRobotService.class);
	private static final String MONITORS_PATH = "/monitors";

	private final WebClient.Builder webClientBuilder;
	private final boolean enabled;
	private final String apiToken;
	private final String apiBaseUrl;
	private final String monitorUrlFilter;
	private final Duration apiTimeout;

	public UptimeRobotService(WebClient.Builder webClientBuilder,
			@Value("${uptimerobot.api.enabled:true}") boolean enabled,
			@Value("${uptimerobot.api.token:}") String apiToken,
			@Value("${uptimerobot.api.base-url:https://api.uptimerobot.com/v3}") String apiBaseUrl,
			@Value("${uptimerobot.api.monitor-url-filter:}") String monitorUrlFilter,
			@Value("${uptimerobot.api.timeout:5s}") Duration apiTimeout) {
		this.webClientBuilder = webClientBuilder;
		this.enabled = enabled;
		this.apiToken = apiToken;
		this.apiBaseUrl = apiBaseUrl;
		this.monitorUrlFilter = monitorUrlFilter;
		this.apiTimeout = apiTimeout;
	}

	@Cacheable("uptimeRobotMonitors")
	public List<Monitor> getMonitors() {
		if (!enabled) {
			log.debug("UptimeRobot integration is disabled.");
			return Collections.emptyList();
		}
		if (!hasText(apiToken)) {
			log.warn("UptimeRobot API token is not configured. Returning empty monitor list.");
			return Collections.emptyList();
		}
		if (!hasText(apiBaseUrl)) {
			log.warn("UptimeRobot API base URL is not configured. Returning empty monitor list.");
			return Collections.emptyList();
		}

		try {
			WebClient client = webClientBuilder.baseUrl(apiBaseUrl)
					.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken).build();

			UptimeRobotResponse response = client.get().uri(uriBuilder -> {
				uriBuilder.path(MONITORS_PATH).queryParam("limit", 50);
				if (hasText(monitorUrlFilter)) {
					uriBuilder.queryParam("url", monitorUrlFilter.trim());
				}
				return uriBuilder.build();
			}).retrieve().onStatus(status -> status.isError(),
					clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
							.flatMap(body -> Mono.error(new IllegalStateException(
									"UptimeRobot API returned " + clientResponse.statusCode().value() + ": " + body))))
					.bodyToMono(UptimeRobotResponse.class).timeout(apiTimeout).block();

			List<Monitor> monitors = response != null && response.getMonitors() != null
					? response.getMonitors()
					: Collections.emptyList();
			log.debug("Retrieved {} monitors from UptimeRobot.", monitors.size());
			return monitors;
		} catch (WebClientResponseException e) {
			log.warn("UptimeRobot API returned HTTP {}. Returning empty monitor list.", e.getStatusCode().value());
			return Collections.emptyList();
		} catch (Exception e) {
			log.warn("Unable to retrieve UptimeRobot monitors: {}", e.getMessage());
			return Collections.emptyList();
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
