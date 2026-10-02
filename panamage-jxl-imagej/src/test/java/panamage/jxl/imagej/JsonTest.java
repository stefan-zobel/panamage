package panamage.jxl.imagej;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void valuesSurviveARoundTrip() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", "quote \" backslash \\ newline \n tab \t control \u0001 umlaut \u00e4");
        map.put("integer", 42.0);
        map.put("fraction", -0.125);
        map.put("large", 1.5e300);
        map.put("yes", true);
        map.put("no", false);
        map.put("nothing", null);
        map.put("list", Arrays.asList(1.0, "two", null, List.of(), Map.of()));

        String json = Json.write(map);

        assertEquals(map, Json.parse(json));
        assertEquals(List.of("text", "integer", "fraction", "large", "yes", "no", "nothing", "list"),
                List.copyOf(((Map<?, ?>) Json.parse(json)).keySet()));
    }

    @Test
    void integersAreWrittenWithoutFraction() {
        assertEquals("[1,-2,3,4.5]", Json.write(List.of(1, -2L, 3.0, 4.5)));
    }

    @Test
    void whitespaceAndEscapesAreRead() {
        Object value = Json.parse(" { \"a\" : [ 1 , 2e1 , \"\\u0041\\/\" ] ,\n\t\"b\":{} } ");

        assertEquals(Map.of("a", List.of(1.0, 20.0, "A/"), "b", Map.of()), value);
    }

    @Test
    void invalidTextIsRejected() {
        for (String text : List.of("", "{", "[1,]", "{\"a\":1,}", "{a:1}", "\"open", "01", "1.", "-", "tru",
                "[1] 2", "\"tab\there\"", "\"\\x\"", "\"\\u12\"", "{\"a\" 1}")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(text), text);
        }
    }

    @Test
    void deepNestingIsRejected() {
        String text = "[".repeat(100) + "]".repeat(100);

        assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
    }

    @Test
    void numbersThatAreNotFiniteAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(List.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(List.of(new Object())));
    }
}
