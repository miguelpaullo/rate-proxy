package com.proxy.controller;

import com.proxy.service.ProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/proxy")
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyService proxyService;

    @GetMapping("/score")
    public ResponseEntity<String> proxyScore(
            @RequestParam Map<String, String> params,
            @RequestHeader(value = "X-Priority",    defaultValue = "1")  int priority,
            @RequestHeader(value = "X-TTL-Seconds", defaultValue = "30") int ttl) {

        try {
            String result = proxyService
                    .enqueue("/score", params, priority, ttl)
                    .get(60, TimeUnit.SECONDS);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Erro: {}", e.getMessage());
            return ResponseEntity.status(503)
                    .body("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
