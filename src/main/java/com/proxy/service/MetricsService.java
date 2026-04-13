package com.proxy.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

@Service
public class MetricsService {

    private final AtomicLong totalRequests   = new AtomicLong();
    private final AtomicLong cacheHits       = new AtomicLong();
    private final AtomicLong penalties       = new AtomicLong();
    private final AtomicLong droppedRequests = new AtomicLong();
    private final AtomicLong upstreamCalls   = new AtomicLong();
    private volatile int currentQueueSize    = 0;

    public void recordRequest()      { totalRequests.incrementAndGet(); }
    public void recordCacheHit()     { cacheHits.incrementAndGet(); }
    public void recordPenalty()      { penalties.incrementAndGet(); }
    public void recordDropped()      { droppedRequests.incrementAndGet(); }
    public void recordUpstreamCall() { upstreamCalls.incrementAndGet(); }
    public void setQueueSize(int s)  { currentQueueSize = s; }

    public Map<String, Object> snapshot() {
        return Map.of(
            "total_requests",     totalRequests.get(),
            "cache_hits",         cacheHits.get(),
            "upstream_calls",     upstreamCalls.get(),
            "penalties_detected", penalties.get(),
            "dropped_requests",   droppedRequests.get(),
            "current_queue_size", currentQueueSize
        );
    }
}