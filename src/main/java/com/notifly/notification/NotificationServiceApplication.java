package com.notifly.notification;

import com.notifly.notification.common.config.DispatchProperties;
import com.notifly.notification.common.config.NotiflyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the multi-tenant notification service.
 *
 * <p>Scheduling is enabled here because the dispatch pipeline is driven by periodic pollers:
 * one promotes due scheduled notifications into the dispatch queue, another claims queued work
 * for the bounded per-channel worker pools, and a third reaps expired dispatch leases.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({NotiflyProperties.class, DispatchProperties.class})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
