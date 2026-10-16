package net.benelog.silkjson.processor;

/**
 * A bound type that refers to a type javac has not resolved yet, perhaps one
 * another processor generates in a later round: the codec waits for that
 * round, and the processor reports it only if the type never appears.
 */
final class Deferred extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Deferred(String unresolved) {
        super(unresolved);
    }
}
