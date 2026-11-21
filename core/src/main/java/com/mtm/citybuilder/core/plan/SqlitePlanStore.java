package com.mtm.citybuilder.core.plan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * A plan store in one SQLite file ({@code citybuilder.db} in the dimension's folder) instead of a file per key: a large
 * world has tens of thousands of planned blocks, and a folder of that many small files is slow to copy, back up and
 * delete (the user, 2026-11-22). A table {@code plans (key, data)}; written in WAL mode, so a crash loses at most the
 * blocks planned last, which are planned again.
 * <p>
 * The driver is the xerial sqlite-jdbc library MTM_worldgen carries as a nested jar, looked up by name: core compiles
 * without it, and {@link #open} returns null where it is missing, the caller falling back to {@link FilePlanStore}.
 * <p>
 * A world saved before kept its plans as files ({@code citybuilder/plan/<key>.bin}): a key not in the database is
 * looked for there and copied in, so its planned blocks stay as they were. The files are left where they are.
 */
public final class SqlitePlanStore implements PlanStore, AutoCloseable {
    /** Raised whenever what is stored changes its meaning. A plan is never dropped: a newer schema is refused. */
    private static final int SCHEMA = 1;

    private final Connection connection;
    private final PreparedStatement read;
    private final PreparedStatement write;
    private final FilePlanStore legacy;

    private SqlitePlanStore(Connection connection, Path legacy) throws SQLException {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            int schema;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                schema = result.next() ? result.getInt(1) : 0;
            }
            if (schema > SCHEMA) {
                throw new SQLException("The plan database is of a newer version (" + schema + ") of this mod");
            }
            statement.execute("CREATE TABLE IF NOT EXISTS plans (key TEXT PRIMARY KEY NOT NULL, data BLOB NOT NULL)");
            statement.execute("PRAGMA user_version=" + SCHEMA);
        }
        read = connection.prepareStatement("SELECT data FROM plans WHERE key = ?");
        // A key is saved once; a second save (two planners racing) keeps the first
        write = connection.prepareStatement("INSERT OR IGNORE INTO plans (key, data) VALUES (?, ?)");
        this.legacy = legacy == null ? null : new FilePlanStore(legacy);
    }

    /**
     * Opens the database at a file, creating it where it does not exist yet; null when no SQLite driver can be loaded.
     *
     * @param legacy the folder of an older save's plan files, or null
     */
    public static SqlitePlanStore open(Path file, Path legacy) {
        Driver driver;
        try {
            // Not through DriverManager: it looks for drivers with the caller's class loader, which on Forge is not
            // the one that sees the nested library
            driver = (Driver) Class.forName("org.sqlite.JDBC", true, SqlitePlanStore.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Connection connection = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
            if (connection == null) {
                throw new SQLException("The SQLite driver did not accept " + file);
            }
            try {
                return new SqlitePlanStore(connection, legacy);
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open the plan database " + file, e);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open the plan database " + file, e);
        }
    }

    @Override
    public synchronized byte[] load(String key) {
        PlanStore.checkKey(key);
        try {
            read.setString(1, key);
            try (ResultSet result = read.executeQuery()) {
                if (result.next()) {
                    return result.getBytes(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read plan " + key, e);
        }
        if (legacy != null) {
            byte[] old = legacy.load(key);
            if (old != null) {
                save(key, old);
                return old;
            }
        }
        return null;
    }

    @Override
    public synchronized void save(String key, byte[] data) {
        PlanStore.checkKey(key);
        try {
            write.setString(1, key);
            write.setBytes(2, data);
            write.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write plan " + key, e);
        }
    }

    /**
     * Writes what the write-ahead log holds into the database file and empties the log, so the world's folder holds
     * every plan in {@code citybuilder.db} alone (copied without its {@code -wal} file, a backup keeps them all).
     * The store stays open: a planner still running may save after.
     */
    public synchronized void checkpoint() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot checkpoint the plan database", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            read.close();
            write.close();
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot close the plan database", e);
        }
    }
}
