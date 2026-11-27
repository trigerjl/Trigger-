package com.triger.poc;

import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class UniversalHashMinerTest {
    private static final String EXACT_INPUT = "6ebb4eda559a631b31ec2d5db3a6fddb08ede58462c917d5bff6f0da284c1afc"
            + "626f7374726f6d317337667579343368387636687a6a74756c7839677879703330726c397435637a337a35366d6b"
            + "371a8f6900000000" + "5500000000000080";
    private static final String EXACT_HASH = "dc51f07716cefc0c583d26750332b230a679b9f0ef6cb1d95bb32549f322f537";
    private static final String VECTOR_1_HASH = "58e342c9b06ce376c8b9310d7a31ac717cf43fb47c304d3815d1e31b117d2b63";
    private static final String VECTOR_2_HASH = "1e344647291f697e7467de1ecfe1f2c51e7e1fcaaf4c1a3f1618202f1c73eb8d";

    @Test
    public void buildInputMatchesRustMiningInput() {
        byte[] seed = UniversalHashMiner.fromHex(
                "6ebb4eda559a631b31ec2d5db3a6fddb08ede58462c917d5bff6f0da284c1afc");
        byte[] input = UniversalHashMiner.buildInput(seed,
                "bostrom1s7fuy43h8v6hzjtulx9gxyp30rl9t5cz3z56mk",
                1770986039L, 0x8000000000000055L);
        assertEquals(EXACT_INPUT, UniversalHashMiner.toHex(input));
    }

    @Test
    public void exactMiningVectorMatchesRustReference() {
        byte[] hash = UniversalHashMiner.hashOnce(UniversalHashMiner.fromHex(EXACT_INPUT));
        assertEquals(EXACT_HASH, UniversalHashMiner.toHex(hash));
    }

    @Test
    public void scratchpadRegressionVectorsMatchRustReference() {
        byte[] first = new byte[68];
        for (int i = 32; i < 52; i++) {
            first[i] = 1;
        }
        assertEquals(VECTOR_1_HASH, UniversalHashMiner.toHex(UniversalHashMiner.hashOnce(first)));

        byte[] second = first.clone();
        second[60] = 1;
        assertEquals(VECTOR_2_HASH, UniversalHashMiner.toHex(UniversalHashMiner.hashOnce(second)));
        assertNotEquals(VECTOR_1_HASH, VECTOR_2_HASH);
    }

    @Test
    public void blake3MatchesOfficialVectors() {
        assertEquals("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
                UniversalHashMiner.toHex(UniversalHashMiner.blake3(new byte[0])));
        assertEquals("6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85",
                UniversalHashMiner.toHex(UniversalHashMiner.blake3(
                        "abc".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void sha256CompressionMatchesJca() throws Exception {
        Method compress = UniversalHashMiner.class.getDeclaredMethod(
                "sha256Compress", byte[].class, byte[].class);
        compress.setAccessible(true);
        byte[] state = UniversalHashMiner.fromHex(
                "6a09e667bb67ae853c6ef372a54ff53a510e527f9b05688c1f83d9ab5be0cd19");
        byte[] block = new byte[64];
        byte[] message = "abc".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(message, 0, block, 0, message.length);
        block[message.length] = (byte) 0x80;
        block[63] = (byte) (message.length * 8);
        compress.invoke(new UniversalHashMiner(), state, block);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(message), state);
    }

    @Test
    public void difficultyCheckMatchesRustSemantics() {
        byte[] hash = new byte[32];
        hash[0] = 0x0F;
        assertTrue(UniversalHashMiner.meetsDifficulty(hash, 4));
        assertFalse(UniversalHashMiner.meetsDifficulty(hash, 5));
        hash[0] = 0;
        hash[1] = 0x40;
        assertTrue(UniversalHashMiner.meetsDifficulty(hash, 9));
        assertFalse(UniversalHashMiner.meetsDifficulty(hash, 10));
    }

    @Test
    public void hasherCanBeReused() {
        UniversalHashMiner miner = new UniversalHashMiner();
        byte[] input = UniversalHashMiner.fromHex(EXACT_INPUT);
        byte[] first = miner.hash(input);
        byte[] second = miner.hash(input);
        assertArrayEquals(first, second);
        assertEquals(EXACT_HASH, UniversalHashMiner.toHex(second));
    }

    @Test
    public void benchmarkIsOptIn() {
        Assume.assumeTrue(Boolean.getBoolean("uhash.benchmark"));
        int iterations = Integer.getInteger("uhash.benchmark.iterations", 3);
        byte[] input = UniversalHashMiner.fromHex(EXACT_INPUT);
        UniversalHashMiner miner = new UniversalHashMiner();
        miner.hash(input);
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            miner.hash(input);
        }
        double elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        double rate = iterations / elapsedSeconds;
        System.out.println("UniversalHash v4 Java port: " + String.format("%.3f", rate)
                + " H/s (" + iterations + " hashes in " + String.format("%.3f", elapsedSeconds) + " s)");
        assertTrue(rate > 0.0);
    }
}
