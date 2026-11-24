package bosca.analytics.queue;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds the real OS lock in another JVM without depending on Gradle's test-worker class loader. */
public final class DiskAnalyticsRoleHolder {
    public static void main(String[] args) throws Exception {
        try (var channel = FileChannel.open(Path.of(args[0], args[1] + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
        }
    }
}
