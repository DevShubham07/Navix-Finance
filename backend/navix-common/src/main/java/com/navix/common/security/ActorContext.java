package com.navix.common.security;

/**
 * Thread-bound holder for the {@link CurrentActor} of the in-flight request. Set by a web filter
 * at the start of each request and cleared at the end. Services read {@link #get()} for audit and
 * separation-of-duties checks. Falls back to {@link CurrentActor#SYSTEM} when nothing is bound.
 */
public final class ActorContext {

    private static final ThreadLocal<CurrentActor> HOLDER = new ThreadLocal<>();

    private ActorContext() {
        // static holder - no instances
    }

    public static void set(CurrentActor actor) {
        HOLDER.set(actor);
    }

    public static CurrentActor get() {
        CurrentActor actor = HOLDER.get();
        return actor != null ? actor : CurrentActor.SYSTEM;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
