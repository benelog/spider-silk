package net.benelog.silkjson.jsonp;

import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;

/** The streams JSON-P puts on top of a parser's events, read through {@link #next()} and {@link #getValue()} as the stream asks. */
abstract class EventParser implements JsonParser {

    /** Whether the parser is inside an object or an array of the document. */
    abstract boolean inside();

    /** The elements of the array whose START_ARRAY is the current event, each read as the stream asks for it. */
    @Override
    public Stream<JsonValue> getArrayStream() {
        if (currentEvent() != Event.START_ARRAY) {
            throw new IllegalStateException("getArrayStream() reads an array from its START_ARRAY, not from " + currentEvent());
        }
        return stream(new Spliterators.AbstractSpliterator<JsonValue>(Long.MAX_VALUE, Spliterator.ORDERED) {
            private boolean done;

            @Override
            public boolean tryAdvance(Consumer<? super JsonValue> action) {
                if (done || next() == Event.END_ARRAY) {
                    done = true;
                    return false;
                }
                action.accept(getValue());
                return true;
            }
        });
    }

    /** The members of the object whose START_OBJECT is the current event, each read as the stream asks for it. */
    @Override
    public Stream<Map.Entry<String, JsonValue>> getObjectStream() {
        if (currentEvent() != Event.START_OBJECT) {
            throw new IllegalStateException("getObjectStream() reads an object from its START_OBJECT, not from " + currentEvent());
        }
        return stream(new Spliterators.AbstractSpliterator<Map.Entry<String, JsonValue>>(Long.MAX_VALUE, Spliterator.ORDERED) {
            private boolean done;

            @Override
            public boolean tryAdvance(Consumer<? super Map.Entry<String, JsonValue>> action) {
                if (done || next() == Event.END_OBJECT) {
                    done = true;
                    return false;
                }
                String key = getString();
                next();
                action.accept(Map.entry(key, getValue()));
                return true;
            }
        });
    }

    /** The document's value, as a stream of one: a document holds one value. */
    @Override
    public Stream<JsonValue> getValueStream() {
        if (inside()) {
            throw new IllegalStateException("getValueStream() reads the document's value, and the parser is inside it");
        }
        return stream(new Spliterators.AbstractSpliterator<JsonValue>(Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super JsonValue> action) {
                if (!hasNext()) {
                    return false;
                }
                next();
                action.accept(getValue());
                return true;
            }
        });
    }

    private static <T> Stream<T> stream(Spliterator<T> spliterator) {
        return StreamSupport.stream(spliterator, false);
    }
}
