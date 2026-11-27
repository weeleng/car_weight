package lab.handoff;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class Owner implements AutoCloseable {
    private static final Set<Path> JVM = ConcurrentHashMap.newKeySet();
    private final Path file;
    private final FileChannel channel;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private boolean stopping;
    private int active;

    private Owner(Path file, FileChannel channel) {
        this.file = file;
        this.channel = channel;
    }

    static Owner acquire(Path database) throws IOException {
        Path file = database.getParent().toRealPath().resolve(database.getFileName());
        if (!JVM.add(file)) throw new IOException("already owned in this JVM");
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file.resolveSibling(file.getFileName()+".lab-owner"),
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            if (channel.tryLock() == null) throw new IOException("already owned in another process");
            return new Owner(file,channel);
        } catch (IOException | RuntimeException e) {
            try {
                if (channel != null) channel.close();
            } finally {
                JVM.remove(file);
            }
            throw e;
        }
    }

    void require(Path path) throws IOException {
        if (!channel.isOpen() || !file.equals(path.toAbsolutePath().normalize())) throw new IOException("wrong owner");
    }

    synchronized Ticket admit() throws IOException {
        if (!channel.isOpen() || (stopping && depth.get()==0)) throw new IOException("owner is stopping");
        depth.set(depth.get()+1);
        active++;
        return new Ticket();
    }

    final class Ticket implements AutoCloseable {
        private boolean closed;

        @Override public void close() throws IOException {
            synchronized (Owner.this) {
                if (closed) return;
                int current=depth.get();
                if (current<=0) throw new IOException("ticket must close on admitting thread");
                closed=true;
                if (current==1) depth.remove();
                else depth.set(current-1);
                active--;
                releaseIfIdle();
            }
        }
    }

    @Override public synchronized void close() throws IOException {
        stopping=true;
        releaseIfIdle();
    }

    private void releaseIfIdle() throws IOException {
        if (!stopping || active!=0 || !channel.isOpen()) return;
        try {
            channel.close();
        } finally {
            JVM.remove(file);
        }
    }
}
