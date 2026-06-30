package com.si.backend.service;

public interface RealtimeFallbackCallbacks {

    void onRecognizing(String text, String language, String speakerId);

    void onRecognized(String text, String language, String speakerId, long speechStartAtMs);

    void onStatus(FallbackEngineStatus status);

    void onError(String message);
}
