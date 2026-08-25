package com.example.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class PaymentsApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }
}

@RestController
class ChargeController {
    private final Map<String, Map<String, Object>> charges = new ConcurrentHashMap<>();

    @PostMapping("/charge")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody Map<String, Object> body) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> charge = Map.of("id", id, "amount", body.getOrDefault("amount", 0), "status", "charged");
        charges.put(id, charge);
        return ResponseEntity.ok(charge);
    }

    @GetMapping("/charges/{id}")
    public ResponseEntity<Map<String, Object>> get(@org.springframework.web.bind.annotation.PathVariable String id) {
        Map<String, Object> c = charges.get(id);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
