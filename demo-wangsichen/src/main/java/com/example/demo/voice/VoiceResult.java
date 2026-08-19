package com.example.demo.voice;

public record VoiceResult(byte[] silkBytes, byte[] mp3Bytes, int durationMs, int sampleRate) {
}
