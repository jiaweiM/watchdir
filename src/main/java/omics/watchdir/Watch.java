package omics.watchdir;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.UnixStyleUsageFormatter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * @author JiaweiMao
 * @version 1.0.0
 * @since 14 Jul 2020, 10:48 AM
 */
public class Watch
{
    @Parameter(names = {"-i"}, required = true, description = "Folder to monitor")
    private String folder;
    @Parameter(names = {"-o"}, required = true, description = "Target folder to store files")
    private String out;
    @Parameter(names = {"-t"}, description = "Waiting time")
    private long waitTime = 1000L;

    public Watch() { }

    public Watch(String folder, String out, long waitTime)
    {
        this.folder = folder;
        this.out = out;
        this.waitTime = waitTime;
    }

    private final Map<Path, Long> expirationTimes = new HashMap<>();

    public void start() throws IOException, InterruptedException
    {
        Path inputFolder = Paths.get(folder);
        if (Files.notExists(inputFolder)) {
            Files.createDirectories(inputFolder);
        }

        WatchService service = FileSystems.getDefault().newWatchService();
        inputFolder.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);

        Path outDir = Paths.get(out);
        if (Files.notExists(outDir)) {
            Files.createDirectories(outDir);
        }

        int count = 0;
        while (true) {
            WatchKey key = service.take();

            while (true) {
                long currentTime = System.currentTimeMillis();
                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {

                        WatchEvent<Path> watchEvent = (WatchEvent<Path>) event;
                        WatchEvent.Kind<Path> kind = watchEvent.kind();

                        Path name = watchEvent.context();
                        Path child = inputFolder.resolve(name);

                        if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                            FileTime lastModifiedTime = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS);
                            expirationTimes.put(child, lastModifiedTime.toMillis() + waitTime);
                        } else if (kind == ENTRY_DELETE) {
                            expirationTimes.remove(child);
                        }
                    }
                    boolean isValid = key.reset();
                    if (!isValid) {
                        if (Files.notExists(inputFolder))
                            Files.createDirectories(inputFolder);
                    }
                }

                for (Map.Entry<Path, Long> entry : expirationTimes.entrySet()) {
                    Path fileName = entry.getKey();
                    Long value = entry.getValue();
                    if (value <= currentTime) {
                        Path target = outDir.resolve(fileName.getFileName());
                        if (Files.exists(target)) {
                            count++;
                            target = Paths.get(out, fileName.getFileName().toString() + count);
                        }
                        try {
                            Files.copy(fileName, target);
                        } catch (IOException e) {
                            System.out.println("Still writing");
                            count--;
                            continue;
                        }
                        expirationTimes.remove(fileName);
                    }
                }

                if (expirationTimes.isEmpty())
                    break;

                long minTime = Collections.min(expirationTimes.values());
                long timeout = minTime - currentTime;
                key = service.poll(timeout, TimeUnit.MILLISECONDS);
            }
        }
    }


    public static void main(String[] args)
    {
        Watch watch = new Watch();
        JCommander jCommander = JCommander.newBuilder().addObject(watch).build();
        jCommander.setUsageFormatter(new UnixStyleUsageFormatter(jCommander));

        try {
            jCommander.parse(args);
            watch.start();
        } catch (Exception e) {
            e.printStackTrace();
            jCommander.usage();
        }
    }
}
