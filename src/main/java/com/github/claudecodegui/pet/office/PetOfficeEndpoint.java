package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;
import java.io.IOException;

/** Typed endpoint metadata. The diagnostic representation never contains the authentication secret. */
record PetOfficeEndpoint(int port, String leaderEpoch, String token, String ownerInstanceId) {
    static final int VERSION = 4;

    @Override
    public String toString() { return "PetOfficeEndpoint[port=" + port + ", protocolVersion=" + VERSION + "]"; }

    static JsonObject encode(PetOfficeEndpoint value) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", VERSION);
        result.addProperty("port", value.port());
        result.addProperty("leaderEpoch", value.leaderEpoch());
        result.addProperty("token", value.token());
        result.addProperty("ownerInstanceId", value.ownerInstanceId());
        decode(result);
        return result;
    }

    static PetOfficeEndpoint decode(JsonObject value) throws IOException {
        PetOfficeIpcFrameCodec.fields(value, "schemaVersion", "port", "leaderEpoch", "token", "ownerInstanceId");
        if (PetOfficeIpcFrameCodec.integer(value, "schemaVersion", VERSION) != VERSION) {
            throw new IOException("OFFICE_PROTOCOL_UNSUPPORTED");
        }
        int port = (int) PetOfficeIpcFrameCodec.integer(value, "port", 65535);
        String epoch = PetOfficeIpcFrameCodec.identifier(value, "leaderEpoch", false);
        String token = PetOfficeIpcFrameCodec.string(value, "token", 64);
        if (port == 0 || !token.matches("[a-f0-9]{64}")) {
            throw new IOException("OFFICE_ENDPOINT_INVALID");
        }
        return new PetOfficeEndpoint(port, epoch, token, PetOfficeIpcFrameCodec.identifier(value, "ownerInstanceId", false));
    }
}
