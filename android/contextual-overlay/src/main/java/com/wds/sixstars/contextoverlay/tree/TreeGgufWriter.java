package com.wds.sixstars.contextoverlay.tree;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Roots lexical-128 v1: a real GGUF v3 container of derived F32 vectors.
 * NOT a language model, neural weights or semantic embedding. The authoritative
 * text and source provenance stay in SQLite; row keys bind the tensor to it.
 */
public final class TreeGgufWriter {
    public static final int DIM = 128;
    public static final String PROFILE = "roots.lexical.char-trigram.f32.v1";
    private TreeGgufWriter() {}

    public static final class Row {
        public final String key, text;
        public Row(String key, String text) { this.key = key; this.text = text; }
    }

    public static float[] vector(String text) {
        float[] result = new float[DIM];
        int[] code = (" " + text.toLowerCase(Locale.ROOT) + " ").codePoints().toArray();
        for (int i = 0; i + 2 < code.length; i++) {
            int hash = 0x811c9dc5;
            for (int j = i; j < i + 3; j++) hash = (hash ^ code[j]) * 0x01000193;
            result[hash & (DIM - 1)] += 1f;
        }
        double norm = 0;
        for (float value : result) norm += value * value;
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < result.length; i++) result[i] *= scale;
        }
        return result;
    }

    public static void write(OutputStream destination, List<Row> rows, long revision)
            throws IOException {
        if (rows.isEmpty()) throw new IllegalArgumentException("Empty tensor");
        LE out = new LE(new BufferedOutputStream(destination, 64 * 1024));
        out.bytes(new byte[]{'G','G','U','F'});
        out.u32(3); out.u64(1); out.u64(6);
        out.string("general.architecture"); out.u32(8); out.string("roots-tree");
        out.string("general.alignment"); out.u32(4); out.u32(32);
        out.string("roots.vector.profile"); out.u32(8); out.string(PROFILE);
        out.string("roots.tree.revision"); out.u32(10); out.u64(revision);
        out.string("roots.scope"); out.u32(8); out.string("local-private");
        out.string("roots.node.keys"); out.u32(9); out.u32(8); out.u64(rows.size());
        for (Row row : rows) out.string(row.key);
        out.string("node.embedding"); out.u32(2);
        out.u64(DIM); out.u64(rows.size()); out.u32(0); out.u64(0);
        while (out.count % 32 != 0) out.one(0);
        for (Row row : rows)
            for (float value : vector(row.text)) out.u32(Float.floatToRawIntBits(value));
        out.stream.flush();
    }

    private static final class LE {
        final OutputStream stream; long count;
        LE(OutputStream stream) { this.stream = stream; }
        void one(int n) throws IOException { stream.write(n & 255); count++; }
        void bytes(byte[] b) throws IOException { stream.write(b); count += b.length; }
        void u32(int n) throws IOException { for(int i=0;i<4;i++) one(n >>> (8*i)); }
        void u64(long n) throws IOException { for(int i=0;i<8;i++) one((int)(n >>> (8*i))); }
        void string(String s) throws IOException {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            u64(bytes.length); bytes(bytes);
        }
    }
}
