package edu.cit.audioscholar.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.Executor;

@Configuration
@EnableCaching
@EnableAsync
public class AppConfig {

	@Bean
	RestTemplate restTemplate(RestTemplateBuilder builder,
			@Value("${http.client.connect-timeout:30s}") Duration connectTimeout,
			@Value("${http.client.read-timeout:10m}") Duration readTimeout) {
		return builder.connectTimeout(connectTimeout).readTimeout(readTimeout).build();
	}

	@Bean
	public WebClient webClient() {
		return WebClient.create();
	}

	@Bean
	public CacheManager cacheManager() {
		CaffeineCacheManager cacheManager = new CaffeineCacheManager();

		cacheManager.setAsyncCacheMode(true);

		cacheManager.setCaffeine(
				Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(500).recordStats());

		return cacheManager;
	}

	@Bean(name = "audioProcessingTaskExecutor")
	public Executor audioProcessingTaskExecutor(
			@Value("${app.async.core-pool-size:2}") int corePoolSize,
			@Value("${app.async.max-pool-size:4}") int maxPoolSize,
			@Value("${app.async.queue-capacity:20}") int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(corePoolSize);
		executor.setMaxPoolSize(maxPoolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix("audio-demo-");
		executor.initialize();
		return executor;
	}
}
