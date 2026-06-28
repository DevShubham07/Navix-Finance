package com.navix.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thread-pool sizing for the async notification dispatch ({@code navix.notifications.async.*}).
 * Defaults are applied so the engine works with no config present.
 */
@ConfigurationProperties(prefix = "navix.notifications.async")
public record NotificationAsyncProperties(Integer corePoolSize, Integer maxPoolSize, Integer queueCapacity) {

    public NotificationAsyncProperties {
        if (corePoolSize == null) {
            corePoolSize = 2;
        }
        if (maxPoolSize == null) {
            maxPoolSize = 8;
        }
        if (queueCapacity == null) {
            queueCapacity = 500;
        }
    }
}
