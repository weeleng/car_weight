package cn.iocoder.sanitation.spool;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Cooperative only. Callers must fence old Gateway processes separately. */
final class FileOwnership implements AutoCloseable {
    private static final java.util.Set<Path> JVM_MARKERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Path database;
    private final Path marker;
    private final FileChannel channel;
    private final FileLock lock;
    private final Object markerKey;
    private final Object parentKey;
    private final Object databaseKey;

    private FileOwnership(Path database, Path marker, FileChannel channel, FileLock lock,
                          Object markerKey, Object parentKey, Object databaseKey) {
        this.database = database;
        this.marker = marker;
        this.channel = channel;
        this.lock = lock;
        this.markerKey = markerKey;
        this.parentKey = parentKey;
        this.databaseKey = databaseKey;
    }

    static FileOwnership acquire(Path path) throws IOException {
        Path db = path.toAbsolutePath();
        Path parent = db.getParent();
        checkCanonical(db, parent);
        Object parentKey = key(parent);
        Object dbKey = Files.exists(db, LinkOption.NOFOLLOW_LINKS) ? key(db) : null;
        Path marker = db.resolveSibling(db.getFileName() + ".owner.lock");
        try {
            Files.createFile(marker);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // The indicator is permanent; its presence says nothing about ownership.
        }
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("non-regular lock file");
        }
        Object markerKey = key(marker);
        if (!JVM_MARKERS.add(marker)) {
            throw new IOException("file already owned in this JVM");
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(marker, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("file already owned");
            }
            FileOwnership ownership = new FileOwnership(db, marker, channel, lock, markerKey, parentKey, dbKey);
            ownership.check(db);
            return ownership;
        } catch (IOException | RuntimeException e) {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            } finally {
                JVM_MARKERS.remove(marker);
            }
            if (e instanceof OverlappingFileLockException) {
                throw new IOException("file already owned in this JVM", e);
            }
            throw e;
        }
    }

    void check(Path path) throws IOException {
        Path parent = database.getParent();
        checkCanonical(database, parent);
        Object currentDb = Files.exists(database, LinkOption.NOFOLLOW_LINKS) ? key(database) : null;
        if (!path.toAbsolutePath().equals(database) || !lock.isValid()
                || !Objects.equals(markerKey, key(marker)) || !Objects.equals(parentKey, key(parent))
                || (databaseKey != null && !Objects.equals(databaseKey, currentDb))) {
            throw new IOException("ownership or path replaced");
        }
    }

    private static void checkCanonical(Path db, Path parent) throws IOException {
        if (!db.equals(db.normalize()) || !parent.equals(parent.toRealPath())
                || Files.isSymbolicLink(db)
                || (Files.exists(db, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(db, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("non-canonical database path");
        }
    }

    private static Object key(Path path) throws IOException {
        Object result = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (result == null) {
            throw new IOException("file identity unavailable");
        }
        return result;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } finally {
            JVM_MARKERS.remove(marker);
        }
    }
}
