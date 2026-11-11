package io.fingerly.server;

/** Bodies the tests reuse, kept out of the assertions that read them. */
final class Fixtures {

    static final String SECRET_KEY = "fly_sk_development_test";
    static final String PROXY_KEY = "fly_px_development_test";
    static final String PUBLIC_KEY = "fly_pk_development_test";
    static final String REQUEST_ID = "0199a963-e400-7000-8000-000000000001";

    private Fixtures() {}

    /** A minimal event carrying every field the API always sends. */
    static String event() {
        return event("");
    }

    /**
     * The same event with extra JSON members spliced in.
     *
     * @param extra a fragment such as {@code "tag": "checkout:1",}
     */
    static String event(String extra) {
        return "{"
                + extra
                + "\"request_id\":\"" + REQUEST_ID + "\","
                + "\"visitor_id\":\"visitor-test\","
                + "\"occurred_at\":\"2026-09-17T12:00:00Z\","
                + "\"state\":\"enriched\","
                + "\"environment\":\"development\","
                + "\"platform\":\"web\","
                + "\"sdk_platform\":\"web\","
                + "\"sdk_version\":\"0.1.0\","
                + "\"ip_network\":\"203.0.113.0/24\","
                + "\"triggers\":[],"
                + "\"has_document\":false"
                + "}";
    }

    /** The API's structured error envelope. */
    static String error(String code, int status) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"Synthetic\",\"status\":"
                + status + "},\"request_id\":\"trace\"}";
    }

    /** An empty page. */
    static String page() {
        return "{\"rows\":[],\"page\":1,\"page_size\":10}";
    }
}
