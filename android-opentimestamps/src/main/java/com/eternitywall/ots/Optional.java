package com.eternitywall.ots;

import java.util.NoSuchElementException;

/**
 * Typical Optional class, should be replaced with java.util.Optional when we migrate to Java 8.
 */
public class Optional<T> {
    private T value;

    public Optional() {
    }

    private Optional(T value) {
        this.value = value;
    }

    public static <T> Optional<T> of(T value) {
        return new Optional<>(value);
    }

    public static <T> Optional<T> absent() {
        return new Optional<>();
    }

    public boolean isPresent() {
        return value != null;
    }

    public T get() {
        if (value == null) {
            throw new NoSuchElementException("cannot call get on an empty optional");
        }

        return value;
    }
}
