package com.example.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class PaymentsApplication {
    public static void main(String[] args) {
        initOpenTelemetry();
        SpringApplication.run(PaymentsApplication.class, args);
    }

    private static void initOpenTelemetry() {
        if (!GlobalOpenTelemetry.isSet()) {
            try {
                AutoConfiguredOpenTelemetrySdk.builder()
                        .setResultAsGlobal()
                        .build();
            } catch (IllegalStateException alreadyConfigured) {
                // An OpenTelemetry SDK (e.g. installed by an agent) is already registered globally;
                // continue using that instance instead of failing startup.
            }
        }
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

@Component
class RequestMetricsFilter implements Filter {
    private final Meter meter = GlobalOpenTelemetry.getMeter("com.example.payments");
    private final DoubleHistogram requestDurationSeconds = meter.histogramBuilder("http.server.request.duration")
            .setUnit("s")
            .setDescription("Duration of inbound HTTP server requests")
            .build();
    private final LongCounter requestCounter = meter.counterBuilder("payments.http.requests.total")
            .setDescription("Count of inbound HTTP requests by route, outcome and tenant")
            .build();

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        long startNanos = System.nanoTime();
        String errorType = null;
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | IOException | ServletException ex) {
            errorType = ex.getClass().getName();
            throw ex;
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            long elapsedMs = elapsedNanos / 1_000_000L;
            String route = routeTemplate(request);
            String tenant = tenantOf(request);
            int status = response.getStatus();

            AttributesBuilder durationAttrs = Attributes.builder()
                    .put(AttributeKey.stringKey("http.request.method"), request.getMethod())
                    .put(AttributeKey.stringKey("url.scheme"), request.getScheme())
                    .put(AttributeKey.stringKey("http.route"), route)
                    .put(AttributeKey.longKey("http.response.status_code"), (long) status);
            if (errorType != null) {
                durationAttrs.put(AttributeKey.stringKey("error.type"), errorType);
                Span.current().setAttribute("error.type", errorType);
            }
            requestDurationSeconds.record(elapsedNanos / 1_000_000_000.0, durationAttrs.build());

            String outcome = (status >= 500 || errorType != null) ? "error" : "success";
            requestCounter.add(1, Attributes.of(
                    AttributeKey.stringKey("http.route"), route,
                    AttributeKey.stringKey("outcome"), outcome,
                    AttributeKey.stringKey("tenant.id"), tenant));

            if (elapsedMs > 750) {
                Span.current().addEvent("slow_request",
                        Attributes.of(AttributeKey.longKey("duration_ms"), elapsedMs));
            }
        }
    }

    private String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }

    private String tenantOf(HttpServletRequest request) {
        String tenant = request.getHeader("X-Tenant-Id");
        return tenant != null ? tenant : "unknown";
    }
}
