package com.navix.loan.service;

/**
 * A lead-import job row has been written and is ready to be worked.
 *
 * <p>Exists so the worker starts <b>after</b> the row commits. {@link LeadImportJobService#start} is
 * {@code @Transactional}, so calling the {@code @Async} worker directly from it handed the job id to
 * another thread while the INSERT was still uncommitted — that thread's first act is
 * {@code findById}, which then found nothing, logged "vanished before it started" and returned. The
 * row went on to commit as {@code QUEUED}, where it sat forever: the UI polls "Importing…"
 * indefinitely and the one-live-job-per-user guard locks that person out of every further import
 * until a restart runs the reaper.
 *
 * <p>Published instead of called, and consumed at {@code AFTER_COMMIT} — the same
 * publish-then-listen shape the notification engine uses (CLAUDE.md §12).
 */
record LeadImportQueuedEvent(Long jobId) {
}
