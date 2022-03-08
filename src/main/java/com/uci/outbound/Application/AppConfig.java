package com.uci.outbound.Application;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.uci.dao.service.HealthService;

@Configuration
public class AppConfig {
	@Bean
	public HealthService healthService() {
		return new HealthService();
	}
}
