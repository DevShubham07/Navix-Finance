"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from "@tanstack/react-query";
import {
  notificationsApiFor,
  type NotificationScope,
  type NotificationView,
} from "@/lib/api/applications";

/**
 * React Query hooks for the in-app notification bell. One set of hooks serves both
 * surfaces — pass `scope: "borrower" | "staff"` and the right BFF namespace (cookie)
 * is used. Polled lightly (every 20s) so the badge stays fresh without websockets;
 * the unread count is deliberately a separate, cheaper query than the full list.
 */

const POLL_MS = 20_000;

/** Query keys, namespaced by scope so the two inboxes never collide in the cache. */
export const notificationKeys = {
  list: (scope: NotificationScope) => ["notifications", scope, "list"] as const,
  unread: (scope: NotificationScope) => ["notifications", scope, "unread"] as const,
};

/** The recipient's notifications, newest-first. Polls every 20s. */
export function useNotifications(
  scope: NotificationScope,
  opts?: { enabled?: boolean },
): UseQueryResult<NotificationView[]> {
  const api = notificationsApiFor(scope);
  return useQuery({
    queryKey: notificationKeys.list(scope),
    queryFn: () => api.list(),
    refetchInterval: POLL_MS,
    enabled: opts?.enabled ?? true,
  });
}

/** Just the unread count for the badge — cheap, polled every 20s. */
export function useUnreadCount(
  scope: NotificationScope,
  opts?: { enabled?: boolean },
): UseQueryResult<number> {
  const api = notificationsApiFor(scope);
  return useQuery({
    queryKey: notificationKeys.unread(scope),
    queryFn: () => api.unreadCount(),
    refetchInterval: POLL_MS,
    enabled: opts?.enabled ?? true,
  });
}

/** Mark a single notification read, then refresh the list + badge. */
export function useMarkRead(scope: NotificationScope) {
  const api = notificationsApiFor(scope);
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.markRead(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: notificationKeys.list(scope) });
      qc.invalidateQueries({ queryKey: notificationKeys.unread(scope) });
    },
  });
}

/** Mark everything read, then refresh the list + badge. */
export function useMarkAllRead(scope: NotificationScope) {
  const api = notificationsApiFor(scope);
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.markAllRead(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: notificationKeys.list(scope) });
      qc.invalidateQueries({ queryKey: notificationKeys.unread(scope) });
    },
  });
}
