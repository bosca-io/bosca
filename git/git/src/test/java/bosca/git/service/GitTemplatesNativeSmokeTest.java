package bosca.git.service;

import java.io.IOException;
import org.junit.Test;

/** Verifies repository initialization templates through dynamic resource lookup, including native images. */
public class GitTemplatesNativeSmokeTest {
    @Test
    public void initializationTemplatesAreAvailable() throws IOException {
        for (var name : new String[] { "Java", "Kotlin", "Node", "Python" }) {
            checkResource("templates/gitignore/" + name + ".gitignore");
        }
        for (var name : new String[] { "MIT", "Apache-2.0", "BSD-3-Clause" }) {
            checkResource("templates/license/" + name);
        }
    }

    private static void checkResource(String path) throws IOException {
        try (var stream = GitTemplatesNativeSmokeTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null || stream.readAllBytes().length == 0) {
                throw new AssertionError("Missing repository template: " + path);
            }
        }
    }

    /** Build with the Git module's main resources on the native-image classpath. */
    public static void main(String[] args) throws IOException {
        new GitTemplatesNativeSmokeTest().initializationTemplatesAreAvailable();
        System.out.println("Git templates native smoke test passed");
    }
}
