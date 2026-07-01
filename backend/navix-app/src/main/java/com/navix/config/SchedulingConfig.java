package com.navix.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support for the app (the notification module owns
 * {@code @EnableAsync}; the bootable app owns {@code @EnableScheduling}). Today this backs the daily
 * {@code PaymentReminderScheduler}.
 *
 * <p>Single-instance only: there is no distributed lock, so a multi-instance deploy would fan out
 * duplicate reminders — add ShedLock (or a Postgres advisory lock) before scaling out.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
