package com.navix.notification.web;

import com.navix.common.web.ApiResponse;
import com.navix.notification.dto.NotificationView;
import com.navix.notification.service.NotificationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The recipient's in-app notification inbox. Protected by the app's {@code anyRequest().authenticated()}
 * chain (no SecurityConfig change); the {@link NotificationService} scopes everything to the caller, so
 * the same endpoints serve borrowers and staff (the JWT audience/role decides whose inbox).
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** The caller's notifications, newest-first. */
    @GetMapping
    public ApiResponse<List<NotificationView>> list(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    /** Unread in-app count for the bell badge. */
    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(service.unreadCount());
    }

    /** Mark one read; returns the fresh unread count. */
    @PostMapping("/{id}/read")
    public ApiResponse<Long> read(@PathVariable Long id) {
        return ApiResponse.ok(service.markRead(id));
    }

    /** Mark all read; returns the fresh unread count (0). */
    @PostMapping("/read-all")
    public ApiResponse<Long> readAll() {
        return ApiResponse.ok(service.markAllRead());
    }
}
