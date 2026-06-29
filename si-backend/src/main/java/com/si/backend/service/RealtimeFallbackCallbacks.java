package com.si.backend.service;

public interface RealtimeFallbackCallbacks {

    void onRecognizing(String text, String language, String speakerId);

    void onRecognized(String text, String language, String speakerId);

    void onTranslated(String originalText, String translatedText, String sourceLang,
                      String targetLang, String speakerId, String speakerName);

    void onTtsAudio(byte[] pcmData, String targetLang, String ttsTaskId,
                    Long ttsSequence, Integer chunkIndex, long speechStartAtMs);

    void onStatus(FallbackEngineStatus status);

    void onError(String message);
}
