package io.fingerly.server;

import static io.fingerly.server.Fixtures.PROXY_KEY;
import static io.fingerly.server.Fixtures.PUBLIC_KEY;
import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** No SDK object may print or serialize a credential. */
class RedactionTest {

    private static final List<String> CREDENTIALS = List.of(SECRET_KEY, PROXY_KEY, PUBLIC_KEY,
            "session=secret", "Bearer secret");

    /** Field-level serialization: the most a careless debug dump would see. */
    private static final ObjectMapper DUMP = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .setVisibility(new ObjectMapper().getVisibilityChecker()
                    .withFieldVisibility(JsonAutoDetect.Visibility.NONE)
                    .withGetterVisibility(JsonAutoDetect.Visibility.PUBLIC_ONLY));

    private static void assertClean(Object value) throws Exception {
        List<String> renderings = new ArrayList<>();
        renderings.add(String.valueOf(value));
        renderings.add(DUMP.writeValueAsString(value));
        for (String rendering : renderings) {
            for (String credential : CREDENTIALS) {
                assertFalse(rendering.contains(credential), value.getClass() + ": " + rendering);
            }
        }
    }

    @Test
    void clientsProxiesBuildersAndRequestsPrintNoCredential() throws Exception {
        FingerlyClient.Builder clientBuilder = FingerlyClient.builder().secretKey(SECRET_KEY);
        FingerlyClient client = clientBuilder.build();
        FingerlyProxy.Builder proxyBuilder = FingerlyProxy.builder()
                .proxyKey(PROXY_KEY)
                .path("/k7x2qm4a")
                .clientIp(request -> "203.0.113.5");
        FingerlyProxy proxy = proxyBuilder.build();
        ProxyRequest request = ProxyRequest.builder()
                .method("POST")
                .path("/k7x2qm4a")
                .header("X-API-Key", PUBLIC_KEY)
                .header("Cookie", "session=secret")
                .header("Authorization", "Bearer secret")
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .build();

        for (Object value : new Object[] {
            clientBuilder, client, client.events(), proxyBuilder, proxy, request,
        }) {
            assertClean(value);
        }
        assertTrue(client.toString().contains("[redacted]"), client.toString());
        assertTrue(proxy.toString().contains("[redacted]"), proxy.toString());
    }
}
