package com.github.claudecodegui.pet.office;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class PetOfficeIpcAuthenticationTest {
    private static final PetOfficeEndpoint ENDPOINT = new PetOfficeEndpoint(12345, "leader", "ab".repeat(32), "idea");

    @Test
    public void serverAndClientProofsAreBoundToEveryConnectionCoordinate() throws Exception {
        JsonObject packet = new JsonObject();
        packet.addProperty("authProof", PetOfficeIpcAuthentication.proof(ENDPOINT, "server", "pycharm", "epoch", "nonce"));
        assertTrue(PetOfficeIpcAuthentication.verify(ENDPOINT, "server", "pycharm", "epoch", "nonce", packet));
        assertFalse(PetOfficeIpcAuthentication.verify(ENDPOINT, "client", "pycharm", "epoch", "nonce", packet));
        assertFalse(PetOfficeIpcAuthentication.verify(ENDPOINT, "server", "other", "epoch", "nonce", packet));
        assertFalse(PetOfficeIpcAuthentication.verify(ENDPOINT, "server", "pycharm", "other", "nonce", packet));
        assertFalse(PetOfficeIpcAuthentication.verify(ENDPOINT, "server", "pycharm", "epoch", "other", packet));
        var replacement = new PetOfficeEndpoint(12345, "other-leader", ENDPOINT.token(), "idea");
        assertFalse(PetOfficeIpcAuthentication.verify(replacement, "server", "pycharm", "epoch", "nonce", packet));
        assertFalse(packet.toString().contains(ENDPOINT.token()));
    }

    @Test
    public void endpointCodecValidatesVersionPortAndFieldsWithoutPrintingTheSecret() throws Exception {
        assertEquals(ENDPOINT, PetOfficeEndpoint.decode(PetOfficeEndpoint.encode(ENDPOINT)));
        assertFalse(ENDPOINT.toString().contains(ENDPOINT.token()));
        JsonObject value = PetOfficeEndpoint.encode(ENDPOINT);
        value.addProperty("schemaVersion", PetOfficeEndpoint.VERSION + 1);
        assertThrows(IOException.class, () -> PetOfficeEndpoint.decode(value));
        value.addProperty("schemaVersion", PetOfficeEndpoint.VERSION);
        value.addProperty("port", 0);
        assertThrows(IOException.class, () -> PetOfficeEndpoint.decode(value));
        value.addProperty("port", 12345);
        value.addProperty("extra", "unknown");
        assertThrows(IOException.class, () -> PetOfficeEndpoint.decode(value));
    }
}
