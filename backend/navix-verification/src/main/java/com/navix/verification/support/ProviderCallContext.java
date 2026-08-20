package com.navix.verification.support;

/**
 * Per-request context for provider-call auditing: which application the call belongs to, and whether
 * it came from real traffic or from an admin's manual workbench run.
 *
 * <p>Mirrors the {@code ActorContext} ThreadLocal idiom already used across the codebase. It is set
 * once per inbound request (the servlet filter parses the application id out of
 * {@code /api/applications/{id}/...}) and cleared in a {@code finally}, so nothing leaks onto a
 * reused worker thread.
 */
public final class ProviderCallContext {

    /** Real borrower/staff traffic. */
    public static final String LIVE = "LIVE";
    /** An administrator firing a call by hand from the Provider API dashboard. */
    public static final String MANUAL = "MANUAL";

    private static final ThreadLocal<Long> APPLICATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CHECK_TYPE = new ThreadLocal<>();
    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Long> LAST_EXECUTION_ID = new ThreadLocal<>();

    private ProviderCallContext() {
        // static holder - no instances
    }

    public static void setApplicationId(Long applicationId) {
        APPLICATION_ID.set(applicationId);
    }

    /** The application this call belongs to, or {@code null} when it cannot be attributed. */
    public static Long applicationId() {
        return APPLICATION_ID.get();
    }

    public static void setCheckType(String checkType) {
        CHECK_TYPE.set(checkType);
    }

    /** The verification step being performed ({@code BUREAU}, {@code PAN}, …), or {@code null}. */
    public static String checkType() {
        return CHECK_TYPE.get();
    }

    public static void setSource(String source) {
        SOURCE.set(source);
    }

    /** {@link #LIVE} unless something explicitly marked this thread as a manual run. */
    public static String source() {
        String source = SOURCE.get();
        return source == null ? LIVE : source;
    }

    /** Set by the recorder after each call so a caller can find the row it just produced. */
    public static void setLastExecutionId(Long executionId) {
        LAST_EXECUTION_ID.set(executionId);
    }

    /** The audit row id of the most recent provider call on this thread, or {@code null}. */
    public static Long lastExecutionId() {
        return LAST_EXECUTION_ID.get();
    }

    public static void clear() {
        APPLICATION_ID.remove();
        CHECK_TYPE.remove();
        SOURCE.remove();
        LAST_EXECUTION_ID.remove();
    }
}
