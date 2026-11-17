package io.fingerly.server;

/**
 * The {@link WebhookEvent#getType()} values, as the exact strings the API sends.
 *
 * <p>The accessor stays a plain string, so a type the server adds after this
 * SDK was released parses normally: keep a default branch for it.
 *
 * <pre>{@code
 * switch (event.getType()) {
 *     case WebhookEventType.VISITOR_SUSPECT:
 *         review(event.getData());
 *         break;
 *     case WebhookEventType.IDENTIFICATION_COMPLETED:
 *         record(event.getData());
 *         break;
 *     default:
 *         break;
 * }
 * }</pre>
 */
public final class WebhookEventType {

    private WebhookEventType() {}

    /** An identification finished. */
    public static final String IDENTIFICATION_COMPLETED = "identification.completed";

    /** An identification request was refused. */
    public static final String IDENTIFICATION_REFUSED = "identification.refused";

    /** A visitor scored as suspect. */
    public static final String VISITOR_SUSPECT = "visitor.suspect";

    /** The organization's billing status changed. */
    public static final String BILLING_STATUS_CHANGED = "billing.status_changed";

    /** A day's usage was settled. */
    public static final String USAGE_DAILY_SETTLED = "usage.daily_settled";

    /** A test delivery sent on request to one endpoint. Nobody subscribes to it. */
    public static final String WEBHOOK_TEST = "webhook.test";
}
