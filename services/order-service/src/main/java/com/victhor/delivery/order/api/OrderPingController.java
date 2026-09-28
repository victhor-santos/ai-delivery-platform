package com.victhor.delivery.order.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderPingController {

	@GetMapping("/ping")
	public Map<String, String> ping() {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("service", "order-service");
		body.put("status", "ok");
		return body;
	}

}
