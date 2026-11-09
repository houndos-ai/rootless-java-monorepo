package com.example.payments;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Business-dimension telemetry layered on top of the OTel Spring WebMVC instrumentation filter
 * (which must run BEFORE this one so Span.current() resolves to the server span it starts).
 *
 * Emits:
 *  - payments.http.request.outcomes: request outcome counter by route + outcome class
 *    (backs payments-http-availability / payments-http-error-rate without scanning traces).
 *  - payments.http.requests.per_tenant: per-tenant request-rate counter
 *    (backs payments-http-request-rate: a single noisy tenant doesn't mask a capacity regression).
 *  - slow_request span event when the handler exceeds the P99 latency budget (750ms).
 *  - error.type span attribute mapping the originating exception class for 5xx attribution.
 */
public class PaymentsTelemetryFilter extends OncePerRequestFilter {

    private static final long SLOW_REQUEST_BUDGET_MS = 750;

    private final LongCounter outcomeCounter;
    private final LongCounter tenantRequestCounter;

    public PaymentsTelemetryFilter() {
        Meter meter = GlobalOpenTelemetry.getMeter("payments-service");
        this.outcomeCounter = meter.counterBuilder("payments.http.request.outcomes")
            .setDescription("Count of HTTP requests by route and outcome class")
            .setUnit("1")
            .build();
        this.tenantRequestCounter = meter.counterBuilder("payments.http.requests.per_tenant")
            .setDescription("Count of HTTP requests per tenant")
            .setUnit("1")
            .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String tenant = request.getHeader("X-Tenant-Id");
        if (tenant == null || tenant.isEmpty()) {
            tenant = "unknown";
        }
        try {
            filterChain.doFilter(request, response);
        } catch (RuntimeException | ServletException | IOException ex) {
            Span.current().setAttribute("error.type", ex.getClass().getName());
            throw ex;
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern != null ? pattern.toString() : request.getRequestURI();
            int status = response.getStatus();
            String outcome = status >= 500 ? "failure" : "success";

            outcomeCounter.add(1, Attributes.of(
                AttributeKey.stringKey("http.route"), route,
                AttributeKey.stringKey("outcome"), outcome));

            tenantRequestCounter.add(1, Attributes.of(
                AttributeKey.stringKey("http.route"), route,
                AttributeKey.stringKey("tenant"), tenant));

            if (elapsedMs > SLOW_REQUEST_BUDGET_MS) {
                Span.current().addEvent("slow_request",
                    Attributes.of(AttributeKey.longKey("duration_ms"), elapsedMs));
            }
        }
    }
}
