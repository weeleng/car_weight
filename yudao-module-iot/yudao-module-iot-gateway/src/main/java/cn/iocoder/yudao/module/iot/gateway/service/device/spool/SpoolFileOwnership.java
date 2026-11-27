package cn.iocoder.yudao.module.iot.gateway.service.device.spool;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暂存路径的进程间所有权。旧版本不参与旁路锁，接管前仍须停止旧实例。
 */
final class SpoolFileOwnership implements AutoCloseable {

    private static final Set<Path> JVM_OWNERS = ConcurrentHashMap.newKeySet();

    private final Path database;
    private final Path marker;
    private final FileChannel channel;
    private final FileLock lock;
    private final Object parentKey;
    private final Object databaseKey;
    private final Object markerKey;

    private SpoolFileOwnership(Path database, Path marker, FileChannel channel, FileLock lock,
                               Object parentKey, Object databaseKey, Object markerKey) {
        this.database = database;
        this.marker = marker;
        this.channel = channel;
        this.lock = lock;
        this.parentKey = parentKey;
        this.databaseKey = databaseKey;
        this.markerKey = markerKey;
    }

    static SpoolFileOwnership acquire(String file) throws IOException {
        Path database = Path.of(file).toAbsolutePath();
        Path parent = database.getParent();
        if (parent == null || !database.normalize().equals(database)) {
            throw new IOException("离线暂存路径必须规范化");
        }
        // 逐层校验，避免在现有软链接指向的目录内创建文件。
        Path directory = parent.getRoot();
        for (Path component : parent) {
            directory = directory.resolve(component);
            createDirectory(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("离线暂存目录不可用或包含软链接：" + directory);
            }
        }
        if (!parent.equals(parent.toRealPath()) || !Files.isWritable(parent)) {
            throw new IOException("离线暂存目录不可写或路径不规范：" + parent);
        }

        Object parentKey = key(parent);
        Path marker = database.resolveSibling(database.getFileName() + ".owner.lock");
        if (!JVM_OWNERS.add(marker)) {
            throw new IOException("当前 JVM 已接管离线暂存：" + database);
        }
        FileChannel channel = null;
        try {
            createFile(marker);
            Object markerKey = regularFileKey(marker);
            channel = FileChannel.open(marker, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("其他进程已接管离线暂存：" + database);
            }
            checkSidecars(database);
            createFile(database);
            Object databaseKey = regularFileKey(database);
            SpoolFileOwnership result = new SpoolFileOwnership(database, marker, channel, lock,
                    parentKey, databaseKey, markerKey);
            result.verify();
            if (!Files.isWritable(database)) {
                throw new IOException("离线暂存文件不可写：" + database);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            } finally {
                JVM_OWNERS.remove(marker);
            }
            throw e;
        }
    }

    Path database() {
        return database;
    }

    void verify() throws IOException {
        Path parent = database.getParent();
        if (!lock.isValid() || !parent.equals(parent.toRealPath())
                || !parentKey.equals(key(parent)) || !markerKey.equals(regularFileKey(marker))
                || !databaseKey.equals(regularFileKey(database))) {
            throw new IOException("离线暂存目录、锁或数据库文件已替换：" + database);
        }
        checkSidecars(database);
    }

    private static void checkSidecars(Path database) throws IOException {
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            Path sidecar = database.resolveSibling(database.getFileName() + suffix);
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                regularFileKey(sidecar);
            }
        }
    }

    private static Object regularFileKey(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("离线暂存文件必须为普通文件，禁止软链接：" + file);
        }
        if (file.getFileSystem().supportedFileAttributeViews().contains("unix")
                && !Integer.valueOf(1).equals(Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("离线暂存文件禁止使用硬链接：" + file);
        }
        return key(file);
    }

    private static Object key(Path path) throws IOException {
        Object key = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (key == null) {
            throw new IOException("无法核验离线暂存文件身份：" + path);
        }
        return key;
    }

    private static void createDirectory(Path path) throws IOException {
        try {
            Files.createDirectory(path);
        } catch (FileAlreadyExistsException ignored) {
            // 调用方检查目录类型。
        }
    }

    private static void createFile(Path path) throws IOException {
        try {
            Files.createFile(path);
        } catch (FileAlreadyExistsException ignored) {
            // 调用方检查文件类型和身份。
        }
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            JVM_OWNERS.remove(marker);
        }
    }

}
