package bosca.pipelines.builtin;

import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.json.Json;
import org.junit.Test;

/** Exercises JSONata's reflective built-ins on the JVM and through the standalone native entry point. */
public class JsonataNativeSmokeTest {
    @Test
    public void recipientCount() {
        check("$count(recipientIds) > 0", "{\"recipientIds\":[\"recipient\"]}", true);
        check("$count(recipientIds) > 0", "{\"recipientIds\":[]}", false);
        check("$count(recipientIds) > 0", "{}", false);
    }

    @Test
    public void transformAndEventTimeFunctions() {
        check("$join($map(recipientIds, function($id) { $uppercase($id) }), ',')",
                "{\"recipientIds\":[\"one\",\"two\"]}", "ONE,TWO");
        check("$sum([1, 2, 3]) = 6", "{}", true);
        check("$toMillis($eventCreated) = 1767225600000", "{}", true);
    }

    private static void check(String expression, String input, Object expected) {
        var jsonata = Jsonata.jsonata(expression);
        var frame = jsonata.createFrame();
        frame.bind("eventCreated", "2026-01-01T00:00:00Z");
        var actual = jsonata.evaluate(Json.parseJson(input), frame);
        if (!expected.equals(actual)) {
            throw new AssertionError(expression + ": expected " + expected + ", got " + actual);
        }
    }

    /** Build with the pipelines module's main resources on the native-image classpath. */
    public static void main(String[] args) {
        var test = new JsonataNativeSmokeTest();
        test.recipientCount();
        test.transformAndEventTimeFunctions();
        System.out.println("JSONata native smoke test passed");
    }
}
