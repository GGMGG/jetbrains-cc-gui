package com.github.claudecodegui.clawbot;

import com.google.gson.JsonObject;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** A fresh server challenge proves the coordinator before the client authorizes a request. */
final class ClawBotIpcAuthentication {
    private ClawBotIpcAuthentication() { }

    static String proof(String token, String role, int port, JsonObject hello, JsonObject challenge) throws IOException {
        try {
            String context = String.join("\u0000", "CCGUI-ClawBot-IPC-v2", role, Integer.toString(port),
                    text(hello, "requestId"), text(hello, "clientInstanceId"), Long.toString(number(hello, "clientConnectionEpoch")),
                    text(challenge, "serverNonce"), text(challenge, "instanceId"), Long.toString(number(challenge, "connectionEpoch")));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(context.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException invalid) {
            throw new IOException("CLAWBOT_IPC_AUTH_UNAVAILABLE");
        }
    }

    static boolean verify(String expected, JsonObject object) throws IOException {
        String proof = text(object, "authProof");
        return proof.matches("[a-f0-9]{64}") && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), proof.getBytes(StandardCharsets.US_ASCII));
    }

    static String text(JsonObject object, String name) throws IOException {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("CLAWBOT_IPC_FIELD_INVALID");
        }
        String result = value.getAsString();
        if (result.isBlank() || result.length() > 256 || result.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("CLAWBOT_IPC_FIELD_INVALID");
        }
        return result;
    }

    static long number(JsonObject object, String name) throws IOException {
        try {
            return ClawBotJsonNumbers.nonNegativeLong(object, name);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("CLAWBOT_IPC_FIELD_INVALID");
        }
    }
}
