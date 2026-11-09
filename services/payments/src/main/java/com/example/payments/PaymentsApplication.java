package com.example.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.spring.webmvc.v6_0.SpringWebMvcTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import jakarta.servlet.Filter;

import java.util.logging.Level;
import java.util.logging.Logger;
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
        try {
            AutoConfiguredOpenTelemetrySdk.builder().setResultAsGlobal().build();
        } catch (RuntimeException | LinkageError e) {
            Logger.getLogger(PaymentsApplication.class.getName())
                .log(Level.WARNING, "OpenTelemetry SDK registration failed; continuing without telemetry", e);
        }
        SpringApplication.run(PaymentsApplication.class, args);
    }

    @Bean
    public FilterRegistrationBean<Filter> otelHttpFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(
            SpringWebMvcTelemetry.create(GlobalOpenTelemetry.get()).createServletFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<Filter> paymentsTelemetryFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new PaymentsTelemetryFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
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
