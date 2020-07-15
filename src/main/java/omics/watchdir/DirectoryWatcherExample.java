package omics.watchdir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;
import static java.util.Collections.min;

public class DirectoryWatcherExample
{
    private final Map<WatchKey, Path> keys;
    private final Map<Path, Long> expirationTimes = new HashMap<>();
    private final Long newFileWait = 10000L;

    private final WatchService watchService;

    public DirectoryWatcherExample(Path path) throws IOException
    {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<>();
        register(path);
    }

    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException
    {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        keys.put(key, dir);
    }

    public void run() throws InterruptedException, IOException
    {
        for (; ; ) {
            //Retrieves and removes next watch key, waiting if none are present.
            WatchKey key = watchService.take();
            for (; ; ) {
                long currentTime = System.currentTimeMillis();

                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path dir = keys.get(key);
                        WatchEvent.Kind<?> kind = event.kind();

                        WatchEvent<Path> ev = cast(event);

                        Path name = ev.context();
                        Path child = dir.resolve(name);

                        if (kind == ENTRY_MODIFY || kind == ENTRY_CREATE) {
                            // Update modified time
                            FileTime lastModified = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS);
                            expirationTimes.put(name, lastModified.toMillis() + newFileWait);
                        } else if (kind == ENTRY_DELETE) {
                            expirationTimes.remove(child);
                        }
                    }
                    // reset watch key to allow the key to be reported again by the watch service
                    key.reset();
                }

                // 已经足够时间没有变化了
                for (Map.Entry<Path, Long> entry : expirationTimes.entrySet()) {
                    if (entry.getValue() <= currentTime) {
                        // do something with the file
                        Path file = entry.getKey();
                        expirationTimes.remove(entry.getKey());
                    }
                }

                // If there are no files left stop polling and block on .take()
                if (expirationTimes.isEmpty())
                    break;

                long minExpiration = min(expirationTimes.values());
                long timeout = minExpiration - currentTime;
                key = watchService.poll(timeout, TimeUnit.MILLISECONDS);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event)
    {
        return (WatchEvent<T>) event;
    }

    public static void main(String[] args) throws IOException, InterruptedException
    {
        DirectoryWatcherExample example = new DirectoryWatcherExample(Paths.get("D:\\test"));
        example.run();
    }
}