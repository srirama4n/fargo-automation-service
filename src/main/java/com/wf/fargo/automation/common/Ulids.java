package com.wf.fargo.automation.common;

import java.security.SecureRandom;

public final class Ulids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulids() {}

    public static String next(long epochMillis) {
        byte[] randomness = new byte[10];
        RANDOM.nextBytes(randomness);
        char[] chars = new char[26];
        writeTime(chars, epochMillis);
        writeRandom(chars, randomness);
        return new String(chars);
    }

    private static void writeTime(char[] chars, long time) {
        chars[0] = ALPHABET[(int) ((time >>> 45) & 31)];
        chars[1] = ALPHABET[(int) ((time >>> 40) & 31)];
        chars[2] = ALPHABET[(int) ((time >>> 35) & 31)];
        chars[3] = ALPHABET[(int) ((time >>> 30) & 31)];
        chars[4] = ALPHABET[(int) ((time >>> 25) & 31)];
        chars[5] = ALPHABET[(int) ((time >>> 20) & 31)];
        chars[6] = ALPHABET[(int) ((time >>> 15) & 31)];
        chars[7] = ALPHABET[(int) ((time >>> 10) & 31)];
        chars[8] = ALPHABET[(int) ((time >>> 5) & 31)];
        chars[9] = ALPHABET[(int) (time & 31)];
    }

    private static void writeRandom(char[] chars, byte[] randomness) {
        int index = 10;
        int buffer = 0;
        int bits = 0;
        for (byte value : randomness) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                chars[index++] = ALPHABET[(buffer >>> bits) & 31];
            }
        }
    }
}
