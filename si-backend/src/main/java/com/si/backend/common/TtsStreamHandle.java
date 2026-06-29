package com.si.backend.common;

/**
 * Control handle for a provider-side TTS stream that may need to be stopped early.
 */
@FunctionalInterface
public interface TtsStreamHandle {

    TtsStreamHandle NOOP = reason -> {
    };

    void cancel(String reason);
}
