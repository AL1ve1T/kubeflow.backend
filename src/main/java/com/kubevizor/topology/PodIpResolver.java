package com.kubevizor.topology;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maintains a mapping of pod IP addresses to service names, learned from
 * incoming span resource attributes. Used to resolve the caller service
 * from the client.address attribute on server spans.
 */
@Component
public class PodIpResolver {

    private static final Logger log = LoggerFactory.getLogger(PodIpResolver.class);

    private final ConcurrentMap<String, String> ipToService = new ConcurrentHashMap<>();

    public void register(String podIp, String serviceName) {
        if (podIp == null || podIp.isBlank() || serviceName == null || serviceName.isBlank()) {
            return;
        }
        String previous = ipToService.put(podIp, serviceName);
        if (previous == null) {
            log.info("Learned pod IP mapping: {} -> {}", podIp, serviceName);
        }
    }

    public String resolve(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return ipToService.get(ip);
    }

    public int size() {
        return ipToService.size();
    }
}
