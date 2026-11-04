package io.fingerly.server;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;

/**
 * The SDK's Jackson configuration, kept in one place.
 *
 * <p>The mapper is private to the SDK. A caller's own {@code ObjectMapper},
 * however it is configured, cannot change how responses are read.
 */
final class Json {

    /**
     * Unknown properties are ignored on purpose: a field the API adds after
     * this SDK was released must not break a client that has not upgraded.
     * The whole body is still reachable through {@code getRaw()}.
     */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            // Bind to fields only. The accessors are shaped for callers, not
            // for the wire: getSuspectScore() returns an Optional over an
            // Integer field, and letting Jackson infer properties from both
            // would make it guess which one the JSON means.
            .setVisibility(new ObjectMapper().getVisibilityChecker()
                    .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                    .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                    .withCreatorVisibility(JsonAutoDetect.Visibility.NONE))
            .registerModule(instantModule());

    private Json() {}

    /**
     * Reads RFC 3339 timestamps into {@link Instant}.
     *
     * <p>A module of one deserializer keeps jackson-datatype-jsr310 off the
     * dependency list, which matters for a library dropped into someone else's
     * build.
     */
    private static SimpleModule instantModule() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Instant.class, new StdDeserializer<Instant>(Instant.class) {
            private static final long serialVersionUID = 1L;

            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                String text = parser.getValueAsString();
                if (text == null || text.isEmpty()) {
                    return null;
                }
                try {
                    return Instant.parse(text);
                } catch (RuntimeException cause) {
                    throw new IOException("Not an RFC 3339 timestamp: " + text, cause);
                }
            }
        });
        return module;
    }

    /**
     * Decodes a response body into a tree.
     *
     * @throws FingerlyException if the body is not JSON, which means it did
     *     not come from the Fingerly API
     */
    static JsonNode tree(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException cause) {
            // The parser's message quotes the body; drop it rather than echo it.
            throw new FingerlyException("Fingerly API returned a body that is not JSON");
        }
    }

    /**
     * Converts a decoded tree into one of the SDK's types.
     *
     * @throws FingerlyException if the tree does not fit the type
     */
    static <T> T convert(JsonNode tree, Class<T> type) {
        try {
            return MAPPER.treeToValue(tree, type);
        } catch (IOException | IllegalArgumentException cause) {
            // The binder's message can quote field values; drop it.
            throw new FingerlyException(
                    "Fingerly API returned an unexpected " + type.getSimpleName());
        }
    }
}
