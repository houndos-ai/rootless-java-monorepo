package com.example.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class OrdersApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}

@RestController
class OrderController {
    private final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> order = Map.of("id", id, "item", body.getOrDefault("item", "unknown"), "status", "accepted");
        orders.put(id, order);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        Map<String, Object> o = orders.get(id);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(o);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
