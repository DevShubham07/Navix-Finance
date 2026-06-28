package com.navix.notification.repository;

import com.navix.notification.entity.NotificationDelivery;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for per-channel {@link NotificationDelivery} rows (the send audit trail). */
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findByNotificationIdOrderByIdAsc(Long notificationId);
}
