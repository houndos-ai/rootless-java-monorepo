package com.example.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.spring.webmvc.v6_0.SpringWebMvcTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class PaymentsApplication {
    private static final Logger log = LoggerFactory.getLogger(PaymentsApplication.class);

    public static void main(String[] args) {
        try {
            AutoConfiguredOpenTelemetrySdk.builder().setResultAsGlobal().build();
        } catch (RuntimeException | LinkageError e) {
            log.error("Failed to initialize OpenTelemetry SDK; continuing without telemetry", e);
        }
        SpringApplication.run(PaymentsApplication.class, args);
    }

    @Bean
    public Filter otelHttpFilter() {
        return SpringWebMvcTelemetry.create(GlobalOpenTelemetry.get()).createServletFilter();
    }
}

@RestController
class ChargeController {
    private final Map<String, Map<String, Object>> charges = new ConcurrentHashMap<>();

    @PostMapping("/charge")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> charge = Map.of("id", id, "amount", body.getOrDefault("amount", 0), "status", "charged");
        charges.put(id, charge);
        return ResponseEntity.ok(charge);
    }

    @GetMapping("/charges/{id}")
    public ResponseEntity<Map<String, Object>> get(@org.springframework.web.bind.annotation.PathVariable String id, HttpServletRequest request) {
        Map<String, Object> c = charges.get(id);
        ResponseEntity<Map<String, Object>> response = c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
        if (c == null) {
            Span.current().setAttribute(AttributeKey.stringKey("error.type"), "charge_not_found");
        }
        return response;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
