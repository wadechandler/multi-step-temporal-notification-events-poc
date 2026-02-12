package com.wadechandler.notification.poc.config;

/**
 * Temporal task queue name constants shared across all modules.
 * <p>
 * {@code NOTIFICATION_QUEUE} — workflow orchestration (scheduling decisions, no I/O).
 * {@code CONTACT_ACTIVITY_QUEUE} — contact resolution activities (getContact, createContact, pollForContact).
 * {@code MESSAGE_ACTIVITY_QUEUE} — message creation activities (bundling + createMessage).
 * <p>
 * The workflow runs on {@code NOTIFICATION_QUEUE}. Activity stubs use
 * {@code ActivityOptions.setTaskQueue()} to route tasks to the appropriate queue.
 * Each queue gets its own Kubernetes deployment and KEDA ScaledObject.
 */
public final class TaskQueues {

    public static final String NOTIFICATION_QUEUE = "NOTIFICATION_QUEUE";
    public static final String CONTACT_ACTIVITY_QUEUE = "CONTACT_ACTIVITY_QUEUE";
    public static final String MESSAGE_ACTIVITY_QUEUE = "MESSAGE_ACTIVITY_QUEUE";

    private TaskQueues() {
        // constants only
    }
}
