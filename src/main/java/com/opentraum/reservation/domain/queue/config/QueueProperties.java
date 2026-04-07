package com.opentraum.reservation.domain.queue.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "opentraum.queue")
public class QueueProperties {

    private int batchSize = 100;
    private int maxActiveUsers = 500;
    private int maxQueueSize = 100000;
    private int heartbeatTtlSeconds = 30;
    private int tokenTtlSeconds = 300;
    private int activeTimeoutSeconds = 60;
    private int schedulerIntervalMs = 5000;
    private int cleanupIntervalMs = 10000;
}
