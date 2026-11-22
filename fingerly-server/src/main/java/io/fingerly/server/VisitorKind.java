package io.fingerly.server;

/** The {@link Event#getVisitorKind()} values, as the exact strings the API sends. */
public final class VisitorKind {

    private VisitorKind() {}

    /** A browser or an app. */
    public static final String HUMAN = "human";

    /** A named agent collecting or answering on your content. */
    public static final String AI_BOT = "ai_bot";

    /** A driver, headless browser or scripted client. */
    public static final String AUTOMATION = "automation";

    /** A named search engine's crawler. */
    public static final String SEARCH_CRAWLER = "search_crawler";

    /**
     * Only in an agent sightings answer: a request the server could not use; ttl 0, never
     * remembered. An event never carries it.
     */
    public static final String UNKNOWN = "unknown";
}
