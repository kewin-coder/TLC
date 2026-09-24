import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

public final class TlcKeyStore {
    private static final String ENV_NAME = "TLC_ENCRYPTION_KEY";
    private static final String KEY_FILE_ENV = "TLC_KEY_FILE";
    private static final String DEFAULT_KEY_FILE = "tlc-data/encryption.key";
    private static final int KEY_BYTES = 32;

    private TlcKeyStore() {
    }

    public static String loadOrCreateEncodedKey() {
        String environmentKey = System.getenv(ENV_NAME);
        if (environmentKey != null && !environmentKey.isBlank()) {
            byte[] decoded = decode(environmentKey);
            persistIfMissing(decoded);
            return Base64.getEncoder().encodeToString(decoded);
        }

        Path keyFile = keyPath();
        try {
            if (Files.isRegularFile(keyFile)) {
                return Base64.getEncoder().encodeToString(
                        decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim()));
            }

            byte[] generated = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            String encoded = Base64.getEncoder().encodeToString(generated);

            Path parent = keyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(keyFile, encoded + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
            return encoded;
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not create or read TLC encryption key: " + keyFile, ex);
        }
    }

    public static Path keyPath() {
        return Paths.get(System.getenv().getOrDefault(KEY_FILE_ENV, DEFAULT_KEY_FILE))
                .toAbsolutePath().normalize();
    }

    private static void persistIfMissing(byte[] key) {
        Path file = keyPath();
        try {
            if (Files.exists(file)) {
                return;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    Base64.getEncoder().encodeToString(key) + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not persist TLC encryption key", ex);
        }
    }

    private static byte[] decode(String encoded) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    ENV_NAME + " is not valid Base64", ex);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "TLC encryption key must decode to exactly 32 bytes");
        }
        return decoded;
    }
}
