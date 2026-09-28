package com.victhor.delivery.user.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserPingController {

	@GetMapping("/ping")
	public Map<String, String> ping() {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("service", "user-service");
		body.put("status", "ok");
		return body;
	}

}

