package net.benelog.spidersilk.json.processor;

import javax.lang.model.element.Element;

/** A type the processor cannot bind, with the element the compile error points at. */
final class Invalid extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Element element;

    Invalid(Element element, String message) {
        super(message);
        this.element = element;
    }

    Element element() {
        return element;
    }
}
