package com.batareya16.miWearBridge.xp.utils;

/**
 * @author user
 */
public class Save {
    public static byte[] sign;
    /**
     * Determines the current install content
     */
    public static Type status = Type.APP;

    public enum Type {
        APP("App"), WATCHFACE("WatchFace"), FIRMWARE("Firmware"), PULL_LOG("Pull log"), ENCRYPT_KEY("Encrypt Key");

        private final String text;

        Type(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }
}
