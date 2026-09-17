import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refreshes the vendored server contract snapshot.
 *
 * <p>The snapshot keeps the test suite independent of sibling repositories.
 * Point {@code FINGERLY_CONTRACT_DIR} at the integration workspace's contract
 * directory to pull a newer one:
 *
 * <pre>{@code
 * FINGERLY_CONTRACT_DIR=../../contract java tools/SyncContract.java
 * }</pre>
 *
 * <p>A single-file program, run straight from source, so refreshing the
 * snapshot needs no build step. {@code ContractTest} verifies the result, so
 * there is no separate check mode: {@code gradlew test} is the check.
 */
public final class SyncContract {

    private static final String[] FILES = {"openapi.json", "fixtures.json"};

    /** Reads one string value out of the manifest without a JSON library. */
    private static final Pattern ENTRY =
            Pattern.compile("\"([^\"]+\\.json)\"\\s*:\\s*\"([0-9a-f]{64})\"");

    private SyncContract() {}

    public static void main(String[] args) throws Exception {
        String configured = System.getenv("FINGERLY_CONTRACT_DIR");
        Path source = Path.of(configured == null ? "contract" : configured);

        String manifest = Files.readString(source.resolve("manifest.json"));
        Map<String, String> hashes = new LinkedHashMap<>();
        Matcher entries = ENTRY.matcher(manifest);
        while (entries.find()) {
            hashes.put(entries.group(1), entries.group(2));
        }

        // Copy only after every file matches the hash the server recorded, so
        // a half-written snapshot cannot land.
        Map<String, byte[]> staged = new LinkedHashMap<>();
        staged.put("manifest.json", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String name : FILES) {
            byte[] content = Files.readAllBytes(source.resolve(name));
            String actual = sha256(content);
            if (!actual.equals(hashes.get(name))) {
                throw new IOException("Invalid contract hash for " + name + ": " + actual);
            }
            staged.put(name, content);
        }

        Path target = Path.of("contract");
        Files.createDirectories(target);
        for (Map.Entry<String, byte[]> file : staged.entrySet()) {
            Files.write(target.resolve(file.getKey()), file.getValue());
        }
        System.out.println("Java contract snapshot refreshed.");
    }

    private static String sha256(byte[] content) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(content)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
