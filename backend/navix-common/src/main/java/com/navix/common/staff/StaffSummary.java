package com.navix.common.staff;

/**
 * Read-only snapshot of a staff member, exposed across the {@link StaffDirectory}
 * port so other modules can render an assignee/actor by <b>name</b> (and gate on
 * activation) without depending on IAM internals. {@code id} is the real
 * {@code staff_user.id}; {@code role} is the {@code StaffRole} name.
 */
public record StaffSummary(Long id, String name, String role, boolean active) {
}
