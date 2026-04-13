package com.proxy.service;

import com.proxy.model.ProxyRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

    private final RestClient restClient;
    private final MetricsService metrics;

    @Value("${proxy.queue.max-size:100}")
    private int maxQueueSize;

    @Value("${proxy.client-id}")
    private String clientId;

    private final PriorityBlockingQueue<ProxyRequest> queue =
        new PriorityBlockingQueue<>(100,
            Comparator.comparingInt(ProxyRequest::getPriority).reversed()
                      .thenComparing(ProxyRequest::getDeadline));

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    private final ConcurrentHashMap<String, String> responseCache =
        new ConcurrentHashMap<>();

    private volatile boolean penaltyMode = false;
    private volatile long penaltyUntil = 0;

    @PostConstruct
    public void startScheduler() {
        scheduler.scheduleAtFixedRate(this::processNext, 0, 1, TimeUnit.SECONDS);
        log.info("✅ Proxy scheduler iniciado — 1 req/s para upstream.");
    }

    public CompletableFuture<String> enqueue(String path,
                                              Map<String, String> params,
                                              int priority,
                                              int ttlSeconds) {
        metrics.recordRequest();
        String cacheKey = path + params.toString();

        // 1) Cache hit
        if (responseCache.containsKey(cacheKey)) {
            metrics.recordCacheHit();
            log.info("[CACHE HIT] {}", cacheKey);
            return CompletableFuture.completedFuture(responseCache.get(cacheKey));
        }

        // 2) Fila cheia → descarta
        if (queue.size() >= maxQueueSize) {
            metrics.recordDropped();
            log.warn("[DROPPED] Fila cheia ({}/{})", queue.size(), maxQueueSize);
            return CompletableFuture.failedFuture(
                new RuntimeException("Serviço sobrecarregado. Tente novamente."));
        }

        // 3) Enfileira
        CompletableFuture<String> future = new CompletableFuture<>();
        ProxyRequest request = ProxyRequest.builder()
                .path(path)
                .params(params)
                .priority(priority)
                .deadline(Instant.now().plusSeconds(ttlSeconds))
                .future(future)
                .build();

        queue.put(request);
        metrics.setQueueSize(queue.size());
        log.info("[ENQUEUED] path={} priority={} queueSize={}", path, priority, queue.size());
        return future;
    }

    private void processNext() {
    metrics.setQueueSize(queue.size());

    if (penaltyMode && System.currentTimeMillis() < penaltyUntil) {
        log.warn("[PENALTY MODE] Aguardando penalidade acabar...");
        return;
    }
    penaltyMode = false;

    ProxyRequest request = queue.poll();
    if (request == null) return;

    if (Instant.now().isAfter(request.getDeadline())) {
        metrics.recordDropped();
        log.warn("[TTL EXPIRED] {}", request.getPath());
        request.getFuture().completeExceptionally(
            new RuntimeException("Requisição expirou na fila (TTL vencido)."));
        return;
    }

    String cacheKey = request.getPath() + request.getParams().toString();

    try {
        metrics.recordUpstreamCall();
        log.info("[UPSTREAM CALL] {}", request.getPath());

        StringBuilder url = new StringBuilder(request.getPath() + "?");
        request.getParams().forEach((k, v) ->
            url.append(k).append("=").append(v).append("&"));

        String response = restClient.get()
                .uri(url.toString())
                .header("client-id", clientId)
                .retrieve()
                .body(String.class);

        responseCache.put(cacheKey, response);
        request.getFuture().complete(response);
        log.info("[SUCCESS] {}", request.getPath());

    } catch (org.springframework.web.client.HttpClientErrorException e) {
        if (e.getStatusCode().value() == 429) {
            metrics.recordPenalty();
            penaltyMode = true;
            penaltyUntil = System.currentTimeMillis() + 3000;
            log.warn("[PENALTY DETECTED] Pausando 3s. Recolocando na fila...");
            queue.put(request);
            return;
        }
        log.error("[ERROR] {}", e.getMessage());
        if (responseCache.containsKey(cacheKey)) {
            metrics.recordCacheHit();
            request.getFuture().complete(responseCache.get(cacheKey));
        } else {
            request.getFuture().completeExceptionally(e);
        }

    } catch (Exception e) {
        log.error("[ERROR] {}", e.getMessage());
        if (responseCache.containsKey(cacheKey)) {
            metrics.recordCacheHit();
            request.getFuture().complete(responseCache.get(cacheKey));
        } else {
            request.getFuture().completeExceptionally(e);
        }
    }
}
}
