package com.victhor.delivery.delivery.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryPingController {

	@GetMapping("/ping")
	public Map<String, String> ping() {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("service", "delivery-service");
		body.put("status", "ok");
		return body;
	}

}

