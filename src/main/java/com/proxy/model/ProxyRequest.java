package com.proxy.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Data
@Builder
public class ProxyRequest {
    private String path;
    private Map<String, String> params;
    private int priority;
    private Instant deadline;
    private CompletableFuture<String> future;
}
