package com.triger.poc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Минимальная кодировка DCUtR HolePunch по спецификации libp2p.
 * Connect содержит наблюдаемые адреса; Sync — пустой. Фреймы uvarint-подобные в духе
 * protobuf-переменных (как в jvm-libp2p реализациях).
 */
public final class HolePunch {
    public enum Type {
        CONNECT,
        SYNC
    }

    public static final class Parsed {
        public final Type type;
        public final List<String> obsAddrs;

        public Parsed(Type type, List<String> obsAddrs) {
            this.type = type == null ? Type.SYNC : type;
            this.obsAddrs = obsAddrs == null ? Collections.<String>emptyList() : obsAddrs;
        }
    }

    public static final class Connect {
        public final List<byte[]> obsAddrs;

        public Connect(List<byte[]> obsAddrs) {
            this.obsAddrs = obsAddrs == null ? Collections.<byte[]>emptyList() : obsAddrs;
        }
    }

    public static final class Sync {
    }

    private HolePunch() {
    }

    public static byte[] encode(Connect connect) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            List<byte[]> addrs = connect == null ? null : connect.obsAddrs;
            if (addrs != null) {
                for (byte[] addr : addrs) {
                    if (addr == null) {
                        continue;
                    }
                    // tag: field 1, wire type 2 (length-delimited)
                    writeUvarint(out, 10L);
                    writeUvarint(out, addr.length);
                    out.write(addr);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("encode Connect failed", e);
        }
        return out.toByteArray();
    }

    public static byte[] encode(Sync sync) {
        return new byte[0];
    }

    public static Parsed parse(byte[] data) {
        if (data == null || data.length == 0) {
            return new Parsed(Type.SYNC, Collections.<String>emptyList());
        }
        List<String> obs = new ArrayList<String>();
        int pos = 0;
        while (pos < data.length) {
            long[] tagInfo = readUvarint(data, pos);
            if (tagInfo == null) {
                break;
            }
            int tag = (int) tagInfo[0];
            pos = (int) tagInfo[1];
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x07;
            if (fieldNumber == 1 && wireType == 2) {
                long[] lenInfo = readUvarint(data, pos);
                if (lenInfo == null) {
                    break;
                }
                int len = (int) lenInfo[0];
                pos = (int) lenInfo[1];
                int end = Math.min(pos + len, data.length);
                if (end > pos) {
                    obs.add(new String(data, pos, end - pos, StandardCharsets.UTF_8));
                }
                pos = end;
                continue;
            }
            if (wireType == 0) {
                long[] v = readUvarint(data, pos);
                if (v == null) {
                    break;
                }
                pos = (int) v[1];
                continue;
            }
            if (wireType == 2) {
                long[] lenInfo = readUvarint(data, pos);
                if (lenInfo == null) {
                    break;
                }
                pos = (int) (lenInfo[1] + lenInfo[0]);
                continue;
            }
            break;
        }
        if (obs.isEmpty()) {
            return new Parsed(Type.SYNC, obs);
        }
        return new Parsed(Type.CONNECT, obs);
    }

    private static void writeUvarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while (v >= 0x80) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long[] readUvarint(byte[] data, int start) {
        long result = 0;
        int shift = 0;
        int i = start;
        while (i < data.length) {
            int b = data[i++] & 0xff;
            result |= ((long) (b & 0x7f)) << shift;
            if ((b & 0x80) == 0) {
                return new long[]{result, (long) i};
            }
            shift += 7;
            if (shift > 63) {
                return null;
            }
        }
        return null;
    }
}
