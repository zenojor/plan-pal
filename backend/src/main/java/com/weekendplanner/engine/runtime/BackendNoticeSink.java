package com.weekendplanner.engine.runtime;

import java.util.function.Consumer;

public final class BackendNoticeSink {
    private static final ThreadLocal<Consumer<Notice>> CURRENT = new ThreadLocal<>();

    private BackendNoticeSink() {
    }

    public record Notice(String level, String source, String message) {
    }

    public static Scope open(Consumer<Notice> consumer) {
        Consumer<Notice> previous = CURRENT.get();
        CURRENT.set(consumer);
        return new Scope(previous);
    }

    public static void info(String source, String message) {
        emit("INFO", source, message);
    }

    public static void warn(String source, String message) {
        emit("WARN", source, message);
    }

    public static void error(String source, String message) {
        emit("ERROR", source, message);
    }

    private static void emit(String level, String source, String message) {
        Consumer<Notice> consumer = CURRENT.get();
        if (consumer == null) return;
        consumer.accept(new Notice(level, source, message));
    }

    public static final class Scope implements AutoCloseable {
        private final Consumer<Notice> previous;

        private Scope(Consumer<Notice> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
