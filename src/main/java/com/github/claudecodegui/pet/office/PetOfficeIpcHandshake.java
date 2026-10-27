package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import static com.github.claudecodegui.pet.office.PetOfficeIpcFrameCodec.fields;
import static com.github.claudecodegui.pet.office.PetOfficeIpcFrameCodec.identifier;
import static com.github.claudecodegui.pet.office.PetOfficeIpcFrameCodec.integer;
import static com.github.claudecodegui.pet.office.PetOfficeIpcFrameCodec.read;
import static com.github.claudecodegui.pet.office.PetOfficeIpcFrameCodec.string;
import static com.github.claudecodegui.pet.office.PetOfficeIpcFrameCodec.write;

/** Fresh server challenge before the client discloses any reusable proof. Caller owns the IO deadline. */
final class PetOfficeIpcHandshake {
    private PetOfficeIpcHandshake() { }

    static void client(Socket socket, PetOfficeEndpoint endpoint, JsonObject hello) throws IOException {
        write(socket.getOutputStream(), hello);
        try {
            JsonObject challenge = read(socket.getInputStream());
            fields(challenge, "schemaVersion", "leaderEpoch", "connectionEpoch", "requestId", "type", "serverNonce", "authProof");
            correlate(hello, challenge, "CHALLENGE");
            String nonce = identifier(challenge, "serverNonce", false);
            String instance = identifier(hello, "instanceId", false);
            String epoch = identifier(hello, "connectionEpoch", false);
            String request = identifier(hello, "requestId", false);
            if (!PetOfficeIpcAuthentication.verify(endpoint, "challenge-server", instance, epoch, request, nonce, challenge)) {
                throw new IOException("OFFICE_IPC_AUTH_FAILED");
            }
            JsonObject auth = envelope(hello, "AUTH");
            auth.addProperty("serverNonce", nonce);
            auth.addProperty("authProof", PetOfficeIpcAuthentication.proof(endpoint, "challenge-client", instance, epoch, request, nonce));
            write(socket.getOutputStream(), auth);
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("OFFICE_IPC_AUTH_FAILED");
        }
    }

    static void server(Socket socket, PetOfficeEndpoint endpoint, JsonObject hello) throws IOException {
        fields(hello, "schemaVersion", "leaderEpoch", "connectionEpoch", "requestId", "type", "instanceId");
        if (integer(hello, "schemaVersion", PetOfficeEndpoint.VERSION) != PetOfficeEndpoint.VERSION
                || !identifier(hello, "leaderEpoch", false).equals(endpoint.leaderEpoch()) || !string(hello, "type", 32).equals("HELLO")) {
            throw new IOException("OFFICE_IPC_AUTH_FAILED");
        }
        String instance = identifier(hello, "instanceId", false);
        String epoch = identifier(hello, "connectionEpoch", false);
        String request = identifier(hello, "requestId", false);
        String nonce = UUID.randomUUID().toString();
        JsonObject challenge = envelope(hello, "CHALLENGE");
        challenge.addProperty("serverNonce", nonce);
        challenge.addProperty("authProof", PetOfficeIpcAuthentication.proof(endpoint, "challenge-server", instance, epoch, request, nonce));
        write(socket.getOutputStream(), challenge);
        JsonObject auth = read(socket.getInputStream());
        fields(auth, "schemaVersion", "leaderEpoch", "connectionEpoch", "requestId", "type", "serverNonce", "authProof");
        correlate(hello, auth, "AUTH");
        if (!nonce.equals(identifier(auth, "serverNonce", false))
                || !PetOfficeIpcAuthentication.verify(endpoint, "challenge-client", instance, epoch, request, nonce, auth)) {
            throw new IOException("OFFICE_IPC_AUTH_FAILED");
        }
    }

    private static JsonObject envelope(JsonObject hello, String type) {
        JsonObject result = new JsonObject();
        for (String field : new String[] { "schemaVersion", "leaderEpoch", "connectionEpoch", "requestId" }) {
            result.add(field, hello.get(field));
        }
        result.addProperty("type", type);
        return result;
    }

    private static void correlate(JsonObject hello, JsonObject packet, String type) throws IOException {
        for (String field : new String[] { "schemaVersion", "leaderEpoch", "connectionEpoch", "requestId" }) {
            if (!hello.get(field).equals(packet.get(field))) { throw new IOException("OFFICE_IPC_AUTH_FAILED"); }
        }
        if (!type.equals(string(packet, "type", 32))) { throw new IOException("OFFICE_IPC_AUTH_FAILED"); }
    }
}
