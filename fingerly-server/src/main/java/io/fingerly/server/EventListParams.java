package io.fingerly.server;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Filters for {@link EventsService#list}.
 *
 * <p>An unset filter is not sent, so the server applies its own default.
 *
 * <pre>{@code
 * EventPage page = fingerly.events().list(
 *     EventListParams.builder().level("high").limit(50).build());
 * }</pre>
 */
public final class EventListParams {

    /** The API accepts RFC 3339 only, and reads it as an absolute instant. */
    private static final DateTimeFormatter RFC_3339 = DateTimeFormatter.ISO_INSTANT;

    private final Instant from;
    private final Instant to;
    private final Integer page;
    private final Integer limit;
    private final String visitor;
    private final String level;

    private EventListParams(Builder builder) {
        this.from = builder.from;
        this.to = builder.to;
        this.page = builder.page;
        this.limit = builder.limit;
        this.visitor = builder.visitor;
        this.level = builder.level;
    }

    /** A new builder with every filter unset. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Renders the filters as a URL query, omitting every unset one.
     *
     * @return the query including its leading {@code ?}, or an empty string
     */
    String query() {
        List<String> parts = new ArrayList<>();
        if (from != null) {
            parts.add("from=" + encode(RFC_3339.format(from)));
        }
        if (to != null) {
            parts.add("to=" + encode(RFC_3339.format(to)));
        }
        if (page != null) {
            parts.add("page=" + page);
        }
        if (limit != null) {
            parts.add("limit=" + limit);
        }
        if (visitor != null) {
            parts.add("visitor=" + encode(visitor));
        }
        if (level != null) {
            parts.add("level=" + encode(level));
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Builds {@link EventListParams}. Every method is optional. */
    public static final class Builder {
        private Instant from;
        private Instant to;
        private Integer page;
        private Integer limit;
        private String visitor;
        private String level;

        private Builder() {}

        /** Start of the window. Defaults to 24 hours ago. */
        public Builder from(Instant from) {
            this.from = from;
            return this;
        }

        /** End of the window. Defaults to now. */
        public Builder to(Instant to) {
            this.to = to;
            return this;
        }

        /**
         * One-based page number.
         *
         * @throws IllegalArgumentException if it is not positive
         */
        public Builder page(int page) {
            if (page <= 0) {
                throw new IllegalArgumentException("page must be positive");
            }
            this.page = page;
            return this;
        }

        /**
         * Rows per page. The server caps it at 200.
         *
         * @throws IllegalArgumentException if it is not positive
         */
        public Builder limit(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
            return this;
        }

        /** Restrict the listing to one exact visitor id. */
        public Builder visitor(String visitor) {
            this.visitor = visitor;
            return this;
        }

        /** Restrict it to {@code low}, {@code medium} or {@code high}. */
        public Builder level(String level) {
            this.level = level;
            return this;
        }

        /** Builds the immutable filters. */
        public EventListParams build() {
            return new EventListParams(this);
        }
    }
}
