package io.fingerly.server;

/**
 * The {@link AiAgent#getPurpose()} values, as the exact strings the API sends.
 * The API can add values, so compare against these rather than assume the
 * list is complete.
 */
public final class AiPurpose {

    private AiPurpose() {}

    /** Collecting content to train models. */
    public static final String TRAINING = "training";

    /** Indexing for an AI answer or search product. */
    public static final String SEARCH = "search";

    /** Reading a page a person asked an assistant to read. */
    public static final String USER_FETCH = "user_fetch";

    /** Driving a browser on a person's behalf. */
    public static final String AGENT = "agent";
}
