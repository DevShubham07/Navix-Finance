package com.navix.notification.repository;

import com.navix.common.notification.NotificationChannel;
import com.navix.notification.entity.NotificationDelivery;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for per-channel {@link NotificationDelivery} rows (the send audit trail). */
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findByNotificationIdOrderByIdAsc(Long notificationId);

    /** Deliveries matching a provider reference (the SES messageId) on a channel — bounce/complaint correlation. */
    List<NotificationDelivery> findByProviderRefAndChannel(String providerRef, NotificationChannel channel);
}
