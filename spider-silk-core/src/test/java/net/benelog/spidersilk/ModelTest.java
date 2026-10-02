package net.benelog.spidersilk;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Model#of}: {@code Map.of} for a template model, null values taken. */
class ModelTest {

    @Test
    void aNullValueIsKept() {
        String flashed = null;
        Map<String, Object> model = Model.of("deck", "Spanish", "message", flashed);

        assertThat(model).containsEntry("deck", "Spanish").containsKey("message");
        assertThat(model.get("message")).isNull();
        assertThat(model).hasSize(2);
    }

    @Test
    void theEntriesIterateInTheOrderGiven() {
        Map<String, Object> model = Model.of("j", 1, "i", 2, "h", 3, "g", 4, "f", 5,
                "e", 6, "d", 7, "c", 8, "b", 9, "a", 10);

        assertThat(model.keySet()).containsExactly("j", "i", "h", "g", "f", "e", "d", "c", "b", "a");
        assertThat(model.values()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void everyArityBuildsThatManyEntries() {
        assertThat(Model.of()).isEmpty();
        assertThat(Model.of("a", 1)).hasSize(1);
        assertThat(Model.of("a", 1, "b", 2)).hasSize(2);
        assertThat(Model.of("a", 1, "b", 2, "c", 3)).hasSize(3);
        assertThat(Model.of("a", 1, "b", 2, "c", 3, "d", 4)).hasSize(4);
        assertThat(Model.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5)).hasSize(5);
        assertThat(Model.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5, "f", 6)).hasSize(6);
        assertThat(Model.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5, "f", 6, "g", 7)).hasSize(7);
        assertThat(Model.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5, "f", 6, "g", 7, "h", 8))
                .hasSize(8);
        assertThat(Model.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5, "f", 6, "g", 7, "h", 8,
                "i", 9)).hasSize(9);
    }

    @Test
    void theModelCannotBeChanged() {
        Map<String, Object> model = Model.of("deck", "Spanish");

        assertThatThrownBy(() -> model.put("other", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> model.remove("deck"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(model::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** The same exceptions {@code Map.of} throws, so a switch between the two changes no failure. */
    @Test
    void aNullKeyOrARepeatedKeyIsRefusedAsMapOfRefusesIt() {
        String noKey = null;
        assertThatThrownBy(() -> Model.of(noKey, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Model.of("a", 1, noKey, 2))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Model.of("a", 1, "b", 2, "a", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }

    @Test
    void itEqualsAMapOfTheSameEntries() {
        Map<String, Object> expected = new HashMap<>();
        expected.put("deck", "Spanish");
        expected.put("message", null);

        assertThat(Model.of("message", null, "deck", "Spanish")).isEqualTo(expected);
    }

    @Test
    void aTemplateResponseTakesItAsItsModel() {
        WebResponse response = WebResponse.template("deck", Model.of("deck", "Spanish", "error", null));

        WebResponse.Template body = (WebResponse.Template) response.body();
        assertThat(body.model()).containsEntry("deck", "Spanish").containsEntry("error", null);
    }
}
