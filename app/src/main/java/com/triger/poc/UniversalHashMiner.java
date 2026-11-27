package com.triger.poc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class UniversalHashMiner {
    public static final int VERSION = 4;
    public static final int CHAINS = 4;
    public static final int SCRATCHPAD_SIZE = 512 * 1024;
    public static final int TOTAL_MEMORY = CHAINS * SCRATCHPAD_SIZE;
    public static final int ROUNDS = 12_288;
    public static final int BLOCK_SIZE = 64;
    public static final int BLOCKS_PER_SCRATCHPAD = SCRATCHPAD_SIZE / BLOCK_SIZE;

    private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;
    private static final long MIXING_CONSTANT = 0x517CC1B727220A95L;
    private static final int ADDRESS_MASK = BLOCKS_PER_SCRATCHPAD - 1;
    private static final int[] RCON = {0x01, 0x02, 0x04};

    private static final int[] IV = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };

    private static final byte[] IV_BYTES = new byte[32];

    static {
        for (int i = 0; i < 8; i++) {
            writeLe32(IV_BYTES, i * 4, IV[i]);
        }
    }

    private static final int[][] MSG_SCHEDULE = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8},
            {3, 4, 10, 12, 13, 2, 7, 14, 6, 5, 9, 0, 11, 15, 8, 1},
            {10, 7, 12, 9, 14, 3, 13, 15, 4, 0, 11, 2, 5, 8, 1, 6},
            {12, 13, 9, 11, 15, 10, 14, 8, 7, 2, 5, 3, 0, 1, 6, 4},
            {9, 14, 11, 5, 8, 12, 15, 1, 13, 3, 0, 10, 2, 6, 4, 7},
            {11, 15, 5, 0, 1, 9, 8, 6, 14, 10, 2, 12, 3, 4, 7, 13}
    };

    private static final int[] SBOX = {
            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
    };

    private static final int[] SHA_K = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private final byte[][] scratchpads = new byte[CHAINS][SCRATCHPAD_SIZE];
    private final byte[][] chainStates = new byte[CHAINS][32];
    private final byte[] blockBuffer = new byte[BLOCK_SIZE];
    private final byte[] aesTmp = new byte[16];
    private final byte[] aesRoundKeys = new byte[64];
    private final int[] shaState = new int[8];
    private final int[] shaWords = new int[64];
    private final int[] blakeV = new int[16];
    private final int[] blakeWords = new int[16];
    private long effectiveNonce;

    public byte[] hash(byte[] input) {
        if (input == null) {
            throw new IllegalArgumentException("input is null");
        }
        effectiveNonce = extractNonce(input);
        int headerLen = Math.max(0, input.length - 8);
        for (int chain = 0; chain < CHAINS; chain++) {
            long modifier = (long) chain * GOLDEN_RATIO;
            byte[] seedInput = new byte[headerLen + 8];
            System.arraycopy(input, 0, seedInput, 0, headerLen);
            putLe64(seedInput, headerLen, effectiveNonce ^ modifier);
            byte[] seed = Blake3.hash(seedInput);
            System.arraycopy(seed, 0, chainStates[chain], 0, 32);
            fillScratchpad(chain, seed);
        }
        for (int chain = 0; chain < CHAINS; chain++) {
            executeRounds(chain);
        }
        byte[] combined = new byte[32];
        for (int chain = 0; chain < CHAINS; chain++) {
            for (int i = 0; i < 32; i++) {
                combined[i] ^= chainStates[chain][i];
            }
        }
        return Blake3.hash(sha256Digest(combined));
    }

    public static byte[] hashOnce(byte[] input) {
        return new UniversalHashMiner().hash(input);
    }

    static byte[] blake3(byte[] input) {
        return Blake3.hash(input);
    }

    public static byte[] buildInput(byte[] epochSeed, byte[] minerAddress, long timestamp, long nonce) {
        byte[] input = new byte[epochSeed.length + minerAddress.length + 16];
        System.arraycopy(epochSeed, 0, input, 0, epochSeed.length);
        System.arraycopy(minerAddress, 0, input, epochSeed.length, minerAddress.length);
        putLe64(input, epochSeed.length + minerAddress.length, timestamp);
        putLe64(input, input.length - 8, nonce);
        return input;
    }

    public static byte[] buildInput(byte[] epochSeed, String minerAddress, long timestamp, long nonce) {
        return buildInput(epochSeed, minerAddress.getBytes(StandardCharsets.UTF_8), timestamp, nonce);
    }

    public static boolean meetsDifficulty(byte[] hash, int difficultyBits) {
        if (difficultyBits <= 0) {
            return true;
        }
        int zeroBits = 0;
        for (byte value : hash) {
            if (value != 0) {
                zeroBits += Integer.numberOfLeadingZeros(value & 0xFF) - 24;
                break;
            }
            zeroBits += 8;
        }
        return zeroBits >= difficultyBits;
    }

    public static MineResult mine(byte[] headerWithoutNonce, int difficultyBits,
                                  long startNonce, long attempts) {
        UniversalHashMiner miner = new UniversalHashMiner();
        byte[] input = Arrays.copyOf(headerWithoutNonce, headerWithoutNonce.length + 8);
        for (long i = 0; i < attempts; i++) {
            long nonce = startNonce + i;
            putLe64(input, headerWithoutNonce.length, nonce);
            byte[] result = miner.hash(input);
            if (meetsDifficulty(result, difficultyBits)) {
                return new MineResult(nonce, result);
            }
        }
        return null;
    }

    public static byte[] fromHex(String hex) {
        String value = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((Character.digit(value.charAt(i * 2), 16) << 4)
                    | Character.digit(value.charAt(i * 2 + 1), 16));
        }
        return result;
    }

    public static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0x0F];
        }
        return new String(out);
    }

    public static final class MineResult {
        public final long nonce;
        public final byte[] hash;

        private MineResult(long nonce, byte[] hash) {
            this.nonce = nonce;
            this.hash = hash;
        }
    }

    private static long extractNonce(byte[] input) {
        if (input.length >= 8) {
            return readLe64(input, input.length - 8);
        }
        return readLe64(Blake3.hash(input), 0);
    }

    private void fillScratchpad(int chain, byte[] seed) {
        byte[] scratchpad = scratchpads[chain];
        byte[] state = new byte[16];
        byte[] tmp = new byte[16];
        System.arraycopy(seed, 16, state, 0, 16);
        aes128KeyExpand(seed, 0, aesRoundKeys);
        for (int block = 0; block < BLOCKS_PER_SCRATCHPAD; block++) {
            int offset = block * BLOCK_SIZE;
            for (int chunk = 0; chunk < 4; chunk++) {
                aesExpandInPlace(state, aesRoundKeys, tmp);
                System.arraycopy(state, 0, scratchpad, offset + chunk * 16, 16);
            }
        }
    }

    private void executeRounds(int chain) {
        byte[] state = chainStates[chain];
        byte[] scratchpad = scratchpads[chain];
        int initialPrimitive = (int) Long.remainderUnsigned(effectiveNonce + chain, 3L);
        for (int round = 0; round < ROUNDS; round++) {
            long mixed = readLe64(state, 0) ^ readLe64(state, 8)
                    ^ Long.rotateLeft((long) round, 13)
                    ^ ((long) round * MIXING_CONSTANT);
            int offset = ((int) mixed & ADDRESS_MASK) * BLOCK_SIZE;
            System.arraycopy(scratchpad, offset, blockBuffer, 0, BLOCK_SIZE);
            int primitive = (initialPrimitive + round + 1) % 3;
            if (primitive == 0) {
                aesCompress(state, blockBuffer);
            } else if (primitive == 1) {
                sha256Compress(state, blockBuffer);
            } else {
                blake3Compress(state, blockBuffer);
            }
            System.arraycopy(state, 0, scratchpad, offset, 32);
        }
    }

    private static byte[] sha256Digest(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void aes128KeyExpand(byte[] seed, int seedOffset, byte[] roundKeys) {
        System.arraycopy(seed, seedOffset, roundKeys, 0, 16);
        for (int r = 0; r < 3; r++) {
            int prev = r * 16;
            int next = prev + 16;
            roundKeys[next] = (byte) (roundKeys[prev] ^ (SBOX[roundKeys[prev + 13] & 0xFF] & 0xFF) ^ RCON[r]);
            roundKeys[next + 1] = (byte) (roundKeys[prev + 1] ^ SBOX[roundKeys[prev + 14] & 0xFF]);
            roundKeys[next + 2] = (byte) (roundKeys[prev + 2] ^ SBOX[roundKeys[prev + 15] & 0xFF]);
            roundKeys[next + 3] = (byte) (roundKeys[prev + 3] ^ SBOX[roundKeys[prev + 12] & 0xFF]);
            for (int i = 4; i < 16; i++) {
                roundKeys[next + i] = (byte) (roundKeys[prev + i] ^ roundKeys[next + i - 4]);
            }
        }
    }

    private static void aesExpandInPlace(byte[] state, byte[] roundKeys, byte[] tmp) {
        aesEncRound(state, 0, roundKeys, 0, tmp, 0);
        aesEncRound(tmp, 0, roundKeys, 16, state, 0);
        aesEncRound(state, 0, roundKeys, 32, tmp, 0);
        aesEncRound(tmp, 0, roundKeys, 48, state, 0);
    }

    private void aesCompress(byte[] state, byte[] block) {
        aesEncRound(state, 0, block, 0, aesTmp, 0);
        aesEncRound(aesTmp, 0, block, 16, state, 0);
        aesEncRound(state, 0, block, 32, aesTmp, 0);
        aesEncRound(aesTmp, 0, block, 48, state, 0);
        aesEncRound(state, 16, block, 32, aesTmp, 0);
        aesEncRound(aesTmp, 0, block, 48, state, 16);
        aesEncRound(state, 16, block, 0, aesTmp, 0);
        aesEncRound(aesTmp, 0, block, 16, state, 16);
    }

    private static void aesEncRound(byte[] state, int stateOffset, byte[] key, int keyOffset,
                                    byte[] out, int outOffset) {
        int s0 = SBOX[state[stateOffset] & 0xFF];
        int s1 = SBOX[state[stateOffset + 1] & 0xFF];
        int s2 = SBOX[state[stateOffset + 2] & 0xFF];
        int s3 = SBOX[state[stateOffset + 3] & 0xFF];
        int s4 = SBOX[state[stateOffset + 4] & 0xFF];
        int s5 = SBOX[state[stateOffset + 5] & 0xFF];
        int s6 = SBOX[state[stateOffset + 6] & 0xFF];
        int s7 = SBOX[state[stateOffset + 7] & 0xFF];
        int s8 = SBOX[state[stateOffset + 8] & 0xFF];
        int s9 = SBOX[state[stateOffset + 9] & 0xFF];
        int s10 = SBOX[state[stateOffset + 10] & 0xFF];
        int s11 = SBOX[state[stateOffset + 11] & 0xFF];
        int s12 = SBOX[state[stateOffset + 12] & 0xFF];
        int s13 = SBOX[state[stateOffset + 13] & 0xFF];
        int s14 = SBOX[state[stateOffset + 14] & 0xFF];
        int s15 = SBOX[state[stateOffset + 15] & 0xFF];

        int b0 = s0;
        int b1 = s5;
        int b2 = s10;
        int b3 = s15;
        int b4 = s4;
        int b5 = s9;
        int b6 = s14;
        int b7 = s3;
        int b8 = s8;
        int b9 = s13;
        int b10 = s2;
        int b11 = s7;
        int b12 = s12;
        int b13 = s1;
        int b14 = s6;
        int b15 = s11;

        out[outOffset] = (byte) (gf2(b0) ^ gf3(b1) ^ b2 ^ b3 ^ (key[keyOffset] & 0xFF));
        out[outOffset + 1] = (byte) (b0 ^ gf2(b1) ^ gf3(b2) ^ b3 ^ (key[keyOffset + 1] & 0xFF));
        out[outOffset + 2] = (byte) (b0 ^ b1 ^ gf2(b2) ^ gf3(b3) ^ (key[keyOffset + 2] & 0xFF));
        out[outOffset + 3] = (byte) (gf3(b0) ^ b1 ^ b2 ^ gf2(b3) ^ (key[keyOffset + 3] & 0xFF));
        out[outOffset + 4] = (byte) (gf2(b4) ^ gf3(b5) ^ b6 ^ b7 ^ (key[keyOffset + 4] & 0xFF));
        out[outOffset + 5] = (byte) (b4 ^ gf2(b5) ^ gf3(b6) ^ b7 ^ (key[keyOffset + 5] & 0xFF));
        out[outOffset + 6] = (byte) (b4 ^ b5 ^ gf2(b6) ^ gf3(b7) ^ (key[keyOffset + 6] & 0xFF));
        out[outOffset + 7] = (byte) (gf3(b4) ^ b5 ^ b6 ^ gf2(b7) ^ (key[keyOffset + 7] & 0xFF));
        out[outOffset + 8] = (byte) (gf2(b8) ^ gf3(b9) ^ b10 ^ b11 ^ (key[keyOffset + 8] & 0xFF));
        out[outOffset + 9] = (byte) (b8 ^ gf2(b9) ^ gf3(b10) ^ b11 ^ (key[keyOffset + 9] & 0xFF));
        out[outOffset + 10] = (byte) (b8 ^ b9 ^ gf2(b10) ^ gf3(b11) ^ (key[keyOffset + 10] & 0xFF));
        out[outOffset + 11] = (byte) (gf3(b8) ^ b9 ^ b10 ^ gf2(b11) ^ (key[keyOffset + 11] & 0xFF));
        out[outOffset + 12] = (byte) (gf2(b12) ^ gf3(b13) ^ b14 ^ b15 ^ (key[keyOffset + 12] & 0xFF));
        out[outOffset + 13] = (byte) (b12 ^ gf2(b13) ^ gf3(b14) ^ b15 ^ (key[keyOffset + 13] & 0xFF));
        out[outOffset + 14] = (byte) (b12 ^ b13 ^ gf2(b14) ^ gf3(b15) ^ (key[keyOffset + 14] & 0xFF));
        out[outOffset + 15] = (byte) (gf3(b12) ^ b13 ^ b14 ^ gf2(b15) ^ (key[keyOffset + 15] & 0xFF));
    }

    private static int gf2(int value) {
        return ((value << 1) ^ ((value >>> 7) * 0x1B)) & 0xFF;
    }

    private static int gf3(int value) {
        return gf2(value) ^ value;
    }

    private void sha256Compress(byte[] state, byte[] block) {
        for (int i = 0; i < 8; i++) {
            shaState[i] = readBe32(state, i * 4);
        }
        for (int i = 0; i < 16; i++) {
            shaWords[i] = readBe32(block, i * 4);
        }
        for (int i = 16; i < 64; i++) {
            int x = shaWords[i - 15];
            int y = shaWords[i - 2];
            int s0 = Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3);
            int s1 = Integer.rotateRight(y, 17) ^ Integer.rotateRight(y, 19) ^ (y >>> 10);
            shaWords[i] = shaWords[i - 16] + s0 + shaWords[i - 7] + s1;
        }
        int a = shaState[0];
        int b = shaState[1];
        int c = shaState[2];
        int d = shaState[3];
        int e = shaState[4];
        int f = shaState[5];
        int g = shaState[6];
        int h = shaState[7];
        for (int i = 0; i < 64; i++) {
            int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
            int ch = (e & f) ^ ((~e) & g);
            int t1 = h + s1 + ch + SHA_K[i] + shaWords[i];
            int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
            int maj = (a & b) ^ (a & c) ^ (b & c);
            int t2 = s0 + maj;
            h = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        shaState[0] += a;
        shaState[1] += b;
        shaState[2] += c;
        shaState[3] += d;
        shaState[4] += e;
        shaState[5] += f;
        shaState[6] += g;
        shaState[7] += h;
        for (int i = 0; i < 8; i++) {
            writeBe32(state, i * 4, shaState[i]);
        }
    }

    private void blake3Compress(byte[] state, byte[] block) {
        int[] h = new int[8];
        for (int i = 0; i < 8; i++) {
            h[i] = readLe32(state, i * 4);
        }
        for (int i = 0; i < 16; i++) {
            blakeWords[i] = readLe32(block, i * 4);
        }
        for (int i = 0; i < 16; i++) {
            blakeV[i] = i < 8 ? h[i] : IV[i - 8];
        }
        for (int[] schedule : MSG_SCHEDULE) {
            blakeG(0, 4, 8, 12, blakeWords[schedule[0]], blakeWords[schedule[1]]);
            blakeG(1, 5, 9, 13, blakeWords[schedule[2]], blakeWords[schedule[3]]);
            blakeG(2, 6, 10, 14, blakeWords[schedule[4]], blakeWords[schedule[5]]);
            blakeG(3, 7, 11, 15, blakeWords[schedule[6]], blakeWords[schedule[7]]);
            blakeG(0, 5, 10, 15, blakeWords[schedule[8]], blakeWords[schedule[9]]);
            blakeG(1, 6, 11, 12, blakeWords[schedule[10]], blakeWords[schedule[11]]);
            blakeG(2, 7, 8, 13, blakeWords[schedule[12]], blakeWords[schedule[13]]);
            blakeG(3, 4, 9, 14, blakeWords[schedule[14]], blakeWords[schedule[15]]);
        }
        for (int i = 0; i < 8; i++) {
            writeLe32(state, i * 4, blakeV[i] ^ blakeV[i + 8]);
        }
    }

    private void blakeG(int a, int b, int c, int d, int mx, int my) {
        blakeV[a] = blakeV[a] + blakeV[b] + mx;
        blakeV[d] = Integer.rotateRight(blakeV[d] ^ blakeV[a], 16);
        blakeV[c] += blakeV[d];
        blakeV[b] = Integer.rotateRight(blakeV[b] ^ blakeV[c], 12);
        blakeV[a] = blakeV[a] + blakeV[b] + my;
        blakeV[d] = Integer.rotateRight(blakeV[d] ^ blakeV[a], 8);
        blakeV[c] += blakeV[d];
        blakeV[b] = Integer.rotateRight(blakeV[b] ^ blakeV[c], 7);
    }

    private static long readLe64(byte[] bytes, int offset) {
        long result = 0;
        for (int i = 7; i >= 0; i--) {
            result = (result << 8) | (bytes[offset + i] & 0xFFL);
        }
        return result;
    }

    private static void putLe64(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            bytes[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    private static int readLe32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeLe32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static int readBe32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static void writeBe32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static final class Blake3 {
        private static final int CHUNK_START = 1;
        private static final int CHUNK_END = 2;
        private static final int PARENT = 4;
        private static final int ROOT = 8;

        static byte[] hash(byte[] input) {
            int chunkCount = Math.max(1, (input.length + 1023) / 1024);
            List<byte[]> stack = new ArrayList<>();
            Output tail = null;
            int offset = 0;
            for (int index = 0; index < chunkCount; index++) {
                int chunkLen = Math.min(1024, input.length - offset);
                if (index == chunkCount - 1) {
                    tail = chunkOutput(input, offset, chunkLen, index);
                } else {
                    addChunk(stack, chunkOutput(input, offset, chunkLen, index).chainingValue(), index + 1L);
                }
                offset += chunkLen;
            }
            while (!stack.isEmpty()) {
                byte[] left = stack.remove(stack.size() - 1);
                tail = parentOutput(left, tail.chainingValue());
            }
            return tail.rootBytes();
        }

        private static void addChunk(List<byte[]> stack, byte[] cv, long chunkCount) {
            while ((chunkCount & 1L) == 0L) {
                byte[] left = stack.remove(stack.size() - 1);
                cv = parentOutput(left, cv).chainingValue();
                chunkCount >>>= 1;
            }
            stack.add(cv);
        }

        private static Output chunkOutput(byte[] input, int offset, int chunkLen, long chunkIndex) {
            byte[] cv = IV_BYTES;
            byte[] block = new byte[64];
            boolean first = true;
            byte[] beforeLast = cv;
            int lastLen = chunkLen;
            int lastFlags = 0;
            int position = offset;
            int remaining = chunkLen;
            while (first || remaining > 0) {
                int len = Math.min(64, remaining);
                Arrays.fill(block, (byte) 0);
                if (len > 0) {
                    System.arraycopy(input, position, block, 0, len);
                }
                int flags = first ? CHUNK_START : 0;
                if (remaining <= 64) {
                    flags |= CHUNK_END;
                }
                beforeLast = cv;
                lastLen = len;
                lastFlags = flags;
                byte[] result = compress(cv, block, chunkIndex, len, flags);
                cv = Arrays.copyOf(result, 32);
                position += len;
                remaining -= len;
                first = false;
            }
            return new Output(beforeLast, block, chunkIndex, lastLen, lastFlags);
        }

        private static Output parentOutput(byte[] leftCv, byte[] rightCv) {
            byte[] block = new byte[64];
            System.arraycopy(leftCv, 0, block, 0, 32);
            System.arraycopy(rightCv, 0, block, 32, 32);
            return new Output(IV_BYTES, block, 0L, 64, PARENT);
        }

        private static byte[] compress(byte[] cv, byte[] block, long counter, int blockLen, int flags) {
            int[] v = new int[16];
            for (int i = 0; i < 8; i++) {
                v[i] = readLe32(cv, i * 4);
                v[i + 8] = IV[i];
            }
            v[12] = (int) counter;
            v[13] = (int) (counter >>> 32);
            v[14] = blockLen;
            v[15] = flags;
            int[] m = new int[16];
            for (int i = 0; i < 16; i++) {
                m[i] = readLe32(block, i * 4);
            }
            for (int[] schedule : MSG_SCHEDULE) {
                g(v, 0, 4, 8, 12, m[schedule[0]], m[schedule[1]]);
                g(v, 1, 5, 9, 13, m[schedule[2]], m[schedule[3]]);
                g(v, 2, 6, 10, 14, m[schedule[4]], m[schedule[5]]);
                g(v, 3, 7, 11, 15, m[schedule[6]], m[schedule[7]]);
                g(v, 0, 5, 10, 15, m[schedule[8]], m[schedule[9]]);
                g(v, 1, 6, 11, 12, m[schedule[10]], m[schedule[11]]);
                g(v, 2, 7, 8, 13, m[schedule[12]], m[schedule[13]]);
                g(v, 3, 4, 9, 14, m[schedule[14]], m[schedule[15]]);
            }
            byte[] result = new byte[64];
            for (int i = 0; i < 8; i++) {
                writeLe32(result, i * 4, v[i] ^ v[i + 8]);
                writeLe32(result, (i + 8) * 4, v[i + 8] ^ readLe32(cv, i * 4));
            }
            return result;
        }

        private static void g(int[] v, int a, int b, int c, int d, int mx, int my) {
            v[a] = v[a] + v[b] + mx;
            v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
            v[c] += v[d];
            v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
            v[a] = v[a] + v[b] + my;
            v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
            v[c] += v[d];
            v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
        }

        private static final class Output {
            private final byte[] cv;
            private final byte[] block;
            private final long counter;
            private final int blockLen;
            private final int flags;

            Output(byte[] cv, byte[] block, long counter, int blockLen, int flags) {
                this.cv = cv;
                this.block = block;
                this.counter = counter;
                this.blockLen = blockLen;
                this.flags = flags;
            }

            byte[] chainingValue() {
                return Arrays.copyOf(compress(cv, block, counter, blockLen, flags), 32);
            }

            byte[] rootBytes() {
                return Arrays.copyOf(compress(cv, block, counter, blockLen, flags | ROOT), 32);
            }
        }
    }
}
