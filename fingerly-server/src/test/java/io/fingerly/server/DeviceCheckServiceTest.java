package io.fingerly.server;

import static io.fingerly.server.Fixtures.REQUEST_ID;
import static io.fingerly.server.Fixtures.SECRET_KEY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeviceCheckServiceTest {

    private static final String DEVICE_CHECK = "/api/v1/events/" + REQUEST_ID + "/device-check";

    private static FingerlyClient client(String url) {
        return FingerlyClient.builder()
                .secretKey(SECRET_KEY)
                .allowInsecureLoopback(true)
                .endpoint(url)
                .timeout(Duration.ofSeconds(2))
                .build();
    }

    private static String error(int status, String code, String details) {
        return "{\"error\":{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"m\""
                + (details == null ? "" : ",\"details\":" + details) + "}}";
    }

    @Test
    void queriesTheBits() throws IOException {
        try (FakeApi api = FakeApi.json(200,
                "{\"found\":true,\"bit0\":true,\"bit1\":false,\"last_update\":\"2026-10\"}")) {
            DeviceCheckBits bits = client(api.url()).deviceCheck().query(REQUEST_ID);

            assertTrue(bits.isFound());
            assertTrue(bits.getBit0());
            assertFalse(bits.getBit1());
            assertEquals(Optional.of("2026-10"), bits.getLastUpdate());
            FakeApi.Recorded sent = api.first();
            assertEquals("POST", sent.method);
            assertEquals(DEVICE_CHECK + "/query", sent.path);
            assertEquals(SECRET_KEY, sent.header("X-API-Key"));
            assertEquals(0, sent.body.length);
        }
    }

    @Test
    void readsBitsApplesHoldsNoneFor() throws IOException {
        try (FakeApi api = FakeApi.json(200, "{\"found\":false,\"bit0\":false,\"bit1\":false}")) {
            DeviceCheckBits bits = client(api.url()).deviceCheck().queryAsync(REQUEST_ID).join();

            assertFalse(bits.isFound());
            assertEquals(Optional.empty(), bits.getLastUpdate());
        }
    }

    @Test
    void writesOnlyTheNamedBits() throws IOException {
        try (FakeApi api = FakeApi.json(200, "{\"bit0\":true}")) {
            DeviceCheckWriteResult result = client(api.url()).deviceCheck()
                    .write(REQUEST_ID, DeviceCheckWrite.builder().bit0(true).build());

            assertEquals(Optional.of(true), result.getBit0());
            assertEquals(Optional.empty(), result.getBit1());
            FakeApi.Recorded sent = api.first();
            assertEquals("PUT", sent.method);
            assertEquals(DEVICE_CHECK, sent.path);
            assertEquals("application/json", sent.header("Content-Type"));
            assertEquals("{\"bit0\":true}", sent.bodyText());
        }
    }

    @Test
    void writesBothBits() throws IOException {
        try (FakeApi api = FakeApi.json(200, "{\"bit0\":false,\"bit1\":true}")) {
            client(api.url()).deviceCheck()
                    .write(REQUEST_ID, DeviceCheckWrite.builder().bit0(false).bit1(true).build());

            assertEquals("{\"bit0\":false,\"bit1\":true}", api.first().bodyText());
        }
    }

    @Test
    void aWriteNamesABit() {
        assertThrows(IllegalArgumentException.class, () -> DeviceCheckWrite.builder().build());
        FingerlyClient fingerly = client("http://127.0.0.1:1");
        assertThrows(IllegalArgumentException.class, () -> fingerly.deviceCheck().write(REQUEST_ID, null));
        assertThrows(IllegalArgumentException.class, () -> fingerly.deviceCheck().query(".."));
    }

    @ParameterizedTest
    @CsvSource({
        "404, device_check_not_held",
        "409, device_check_spent",
        "409, device_check_busy",
        "409, credential_unavailable",
        "422, bits_required",
        "422, apple_rejected",
    })
    void typesEachErrorAndNeverRetriesIt(int status, String code) throws IOException {
        try (FakeApi api = FakeApi.json(status, error(status, code, null))) {
            FingerlyApiException e = assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).deviceCheck().write(REQUEST_ID, DeviceCheckWrite.builder().bit1(true).build()));

            assertEquals(status, e.getStatus());
            assertEquals(code, e.getCode());
            assertEquals("device_check_not_held".equals(code), e.isDeviceCheckNotHeld());
            assertEquals("device_check_spent".equals(code), e.isDeviceCheckSpent());
            assertEquals("device_check_busy".equals(code), e.isDeviceCheckBusy());
            assertEquals("credential_unavailable".equals(code), e.isCredentialUnavailable());
            assertEquals("bits_required".equals(code), e.isBitsRequired());
            assertEquals("apple_rejected".equals(code), e.isAppleRejected());
            assertFalse(e.isDeviceCheckUnavailable());
            assertEquals(1, api.callCount());
        }
    }

    @ParameterizedTest
    @CsvSource({"429", "500", "502"})
    void doesNotRetryOtherFailures(int status) throws IOException {
        try (FakeApi api = FakeApi.json(status, error(status, "other", null))) {
            assertThrows(FingerlyApiException.class, () -> client(api.url()).deviceCheck().query(REQUEST_ID));
            assertEquals(1, api.callCount());
        }
    }

    @Test
    void readsApplesStatus() throws IOException {
        String details = "[{\"field\":\"apple_status\",\"location\":\"upstream\",\"issue\":\"400\"}]";
        try (FakeApi api = FakeApi.json(422, error(422, "apple_rejected", details))) {
            FingerlyApiException e = assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).deviceCheck().query(REQUEST_ID));

            assertTrue(e.isAppleRejected());
            assertEquals(Integer.valueOf(400), e.getAppleStatus());
        }
        try (FakeApi api = FakeApi.json(409, error(409, "device_check_spent", null))) {
            FingerlyApiException e = assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).deviceCheck().query(REQUEST_ID));
            assertNull(e.getAppleStatus());
        }
    }

    @Test
    void retriesA503WithTheSameRequest() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        try (FakeApi api = FakeApi.start(exchange -> {
            if (calls.incrementAndGet() == 1) {
                FakeApi.respond(exchange, 503, error(503, "device_check_unavailable", null));
            } else {
                FakeApi.respond(exchange, 200, "{\"bit0\":true,\"bit1\":false}");
            }
        })) {
            DeviceCheckWriteResult result = client(api.url()).deviceCheck()
                    .write(REQUEST_ID, DeviceCheckWrite.builder().bit0(true).bit1(false).build());

            assertEquals(Optional.of(true), result.getBit0());
            assertEquals(2, api.callCount());
            FakeApi.Recorded first = api.requests.get(0);
            FakeApi.Recorded second = api.requests.get(1);
            assertEquals(first.method, second.method);
            assertEquals(first.path, second.path);
            assertArrayEquals(first.body, second.body);
        }
    }

    @Test
    void givesUpAfterThreeAttempts() throws IOException {
        try (FakeApi api = FakeApi.json(503, error(503, "device_check_unavailable", null))) {
            FingerlyApiException e = assertThrows(FingerlyApiException.class,
                    () -> client(api.url()).deviceCheck().query(REQUEST_ID));

            assertTrue(e.isDeviceCheckUnavailable());
            assertEquals(3, api.callCount());
            for (FakeApi.Recorded sent : api.requests) {
                assertEquals(DEVICE_CHECK + "/query", sent.path);
            }
        }
    }

    @Test
    void retriesATransportFailure() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        try (FakeApi api = FakeApi.start(exchange -> {
            if (calls.incrementAndGet() > 1) {
                FakeApi.respond(exchange, 200, "{\"found\":false,\"bit0\":false,\"bit1\":false}");
            }
            // The first exchange closes without an answer.
        })) {
            DeviceCheckBits bits = client(api.url()).deviceCheck().query(REQUEST_ID);

            assertFalse(bits.isFound());
            assertEquals(2, api.callCount());
        }
    }

    @Test
    void stopsRetryingWhenNothingListens() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }
        assertThrows(FingerlyConnectionException.class,
                () -> client("http://127.0.0.1:" + port).deviceCheck().query(REQUEST_ID));
    }
}
