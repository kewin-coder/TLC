import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Creates/restores a single TLC backup containing the SQLite database and
 * the installation encryption key.
 *
 * Stop the TLC server before creating or restoring a backup.
 */
public final class TlcBackup {
    private static final String FORMAT = "TLC-BACKUP-1";
    private static final String DEFAULT_DATABASE_URL = "jdbc:sqlite:tlc.db";
    private static final int KEY_BYTES = 32;

    private TlcBackup() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }

        switch (args[0].toLowerCase()) {
            case "backup":
                createBackup(Paths.get(args[1]));
                return;
            case "restore":
                restoreBackup(Paths.get(args[1]));
                return;
            default:
                usage();
        }
    }

    public static Path createBackup(Path destination) throws Exception {
        Path database = databasePath();
        Path key = TlcKeyStore.keyPath();

        if (!Files.isRegularFile(database)) {
            throw new IOException("TLC database not found: " + database);
        }
        if (!Files.isRegularFile(key)) {
            throw new IOException("TLC encryption key not found: " + key);
        }

        Path output = destination.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        checkpoint();

        try (OutputStream fileOut = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
            addText(zip, "manifest.txt",
                    FORMAT + System.lineSeparator()
                            + "created=" + Instant.now() + System.lineSeparator());
            addFile(zip, "tlc.db", database);
            addFile(zip, "encryption.key", key);
        }

        return output;
    }

    public static void restoreBackup(Path backup) throws Exception {
        Path input = backup.toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new IOException("Backup file not found: " + input);
        }

        Path database = databasePath();
        Path key = TlcKeyStore.keyPath();

        if (Files.exists(database) || Files.exists(key)) {
            throw new IOException(
                    "Restore requires a fresh TLC data location; existing database/key were not overwritten");
        }

        Path databaseParent = database.getParent();
        if (databaseParent != null) {
            Files.createDirectories(databaseParent);
        }
        Path keyParent = key.getParent();
        if (keyParent != null) {
            Files.createDirectories(keyParent);
        }

        Path tempDir = Files.createTempDirectory("tlc-restore-");
        Path tempDatabase = tempDir.resolve("tlc.db");
        Path tempKey = tempDir.resolve("encryption.key");

        try {
            boolean validManifest = false;
            boolean hasDatabase = false;
            boolean hasKey = false;

            try (InputStream fileIn = Files.newInputStream(input);
                 ZipInputStream zip = new ZipInputStream(fileIn, StandardCharsets.UTF_8)) {

                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    if ("manifest.txt".equals(entry.getName())) {
                        String manifest = new String(
                                zip.readAllBytes(), StandardCharsets.UTF_8);
                        validManifest = manifest.startsWith(
                                FORMAT + System.lineSeparator());
                        continue;
                    }

                    if ("tlc.db".equals(entry.getName())) {
                        Files.copy(zip, tempDatabase,
                                StandardCopyOption.REPLACE_EXISTING);
                        hasDatabase = true;
                    } else if ("encryption.key".equals(entry.getName())) {
                        Files.copy(zip, tempKey,
                                StandardCopyOption.REPLACE_EXISTING);
                        hasKey = true;
                    } else {
                        throw new IOException(
                                "Unexpected backup entry: " + entry.getName());
                    }
                }
            }

            if (!validManifest || !hasDatabase || !hasKey) {
                throw new IOException("Invalid TLC backup format");
            }

            String encodedKey = Files.readString(
                    tempKey, StandardCharsets.US_ASCII).trim();
            validateKey(encodedKey);

            moveIntoPlace(tempDatabase, database);
            moveIntoPlace(tempKey, key);
        } finally {
            deleteIfExists(tempDatabase);
            deleteIfExists(tempKey);
            deleteIfExists(tempDir);
        }
    }

    private static void checkpoint() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                System.getenv().getOrDefault(
                        "TLC_DATABASE_URL", DEFAULT_DATABASE_URL));
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(FULL)");
        }
    }

    private static Path databasePath() {
        String url = System.getenv().getOrDefault(
                "TLC_DATABASE_URL", DEFAULT_DATABASE_URL);

        if (!url.startsWith("jdbc:sqlite:")) {
            throw new IllegalStateException(
                    "TLC backup currently supports file-based SQLite JDBC URLs only");
        }

        String location = url.substring("jdbc:sqlite:".length());
        if (location.isBlank() || ":memory:".equals(location)) {
            throw new IllegalStateException(
                    "TLC backup requires a file-based SQLite database");
        }

        return Paths.get(location).toAbsolutePath().normalize();
    }

    private static void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(source, destination);
        }
    }

    private static void validateKey(String encoded) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Backup contains an invalid TLC encryption key", ex);
        }
    }

    private static void addText(
            ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void addFile(
            ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (path != null) {
            Files.deleteIfExists(path);
        }
    }

    private static void usage() {
        System.out.println(
                "TLC backup utility:\n"
                        + "  java TlcBackup backup <output.tlcb>\n"
                        + "  java TlcBackup restore <backup.tlcb>\n"
                        + "Stop TLC before either operation.");
    }
}
