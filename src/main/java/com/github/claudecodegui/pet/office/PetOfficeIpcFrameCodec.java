package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Bounded stream framing delegates JSON validation to the same codec as the native page and preferences. */
final class PetOfficeIpcFrameCodec {
    static final int MAX_FRAME_BYTES = 64 * 1024;
    private PetOfficeIpcFrameCodec() { }

    static void write(OutputStream output, JsonObject object) throws IOException {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
            throw new IOException("OFFICE_IPC_FRAME_CAPACITY");
        }
        DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(bytes.length);
        stream.write(bytes);
        stream.flush();
    }

    static JsonObject read(InputStream input) throws IOException {
        DataInputStream stream = new DataInputStream(input);
        int length = stream.readInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("OFFICE_IPC_FRAME_CAPACITY");
        }
        byte[] bytes = new byte[length];
        stream.readFully(bytes);
        return parse(bytes);
    }

    static JsonObject parse(byte[] bytes) throws IOException { return PetOfficeJsonCodec.parse(bytes); }
    static void fields(JsonObject value, String... names) throws IOException { PetOfficeJsonCodec.fields(value, names); }
    static String identifier(JsonObject value, String name, boolean optional) throws IOException { return PetOfficeJsonCodec.identifier(value, name, optional); }
    static String string(JsonObject value, String name, int max) throws IOException { return PetOfficeJsonCodec.string(value, name, max); }
    static long integer(JsonObject value, String name, long max) throws IOException { return PetOfficeJsonCodec.integer(value, name, max); }
}
