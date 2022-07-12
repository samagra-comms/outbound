package com.uci.outbound.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uci.dao.service.HealthService;

import com.uci.utils.model.ApiResponse;
import com.uci.utils.model.ApiResponseParams;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping(value = "/service")
public class ServiceStatusController {

	@Autowired 
	private HealthService healthService;

    @RequestMapping(value = "/health/cassandra", method = RequestMethod.GET, produces = { "application/json", "text/json" })
    public ResponseEntity<ApiResponse> cassandraStatusCheck() throws IOException, JsonProcessingException {
        ApiResponse response = ApiResponse.builder()
                .id("api.service.health.cassandra")
                .params(ApiResponseParams.builder().build())
                .responseCode(HttpStatus.OK.name())
                .result(healthService.getCassandraHealthNode())
                .build();

        return ResponseEntity.ok(response);
    }

    @RequestMapping(value = "/health/kafka", method = RequestMethod.GET, produces = { "application/json", "text/json" })
    public ResponseEntity<ApiResponse> kafkaStatusCheck() throws IOException, JsonProcessingException {
        ApiResponse response = ApiResponse.builder()
                .id("api.service.health.kafka")
                .params(ApiResponseParams.builder().build())
                .responseCode(HttpStatus.OK.name())
                .result(healthService.getKafkaHealthNode())
                .build();

        return ResponseEntity.ok(response);
    }

    @RequestMapping(value = "/health/campaign", method = RequestMethod.GET, produces = { "application/json", "text/json" })
    public ResponseEntity<ApiResponse> campaignUrlStatusCheck() throws JsonProcessingException, IOException {
        ApiResponse response = ApiResponse.builder()
                .id("api.service.health.campaign")
                .params(ApiResponseParams.builder().build())
                .responseCode(HttpStatus.OK.name())
                .result(healthService.getCampaignUrlHealthNode())
                .build();

        return ResponseEntity.ok(response);
    }
}
