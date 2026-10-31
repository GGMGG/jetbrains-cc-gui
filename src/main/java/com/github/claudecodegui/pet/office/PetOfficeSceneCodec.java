package com.github.claudecodegui.pet.office;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** A renderer projection validates each participant separately before aggregating metadata. */
final class PetOfficeSceneCodec {
    private static final Gson JSON = new Gson();
    private PetOfficeSceneCodec() { }

    static JsonObject encode(PetOfficeParticipantRegistry.View view) throws IOException {
        var snapshot = view.snapshot();
        var participants = new HashSet<>(view.participants());
        if (participants.size() != view.participants().size() || participants.isEmpty() || participants.size() > PetOfficeParticipantRegistry.MAX_PARTICIPANTS
                || !participants.contains(snapshot.instanceId()) || snapshot.sources().size() > PetOfficeSessionRegistry.MAX_SOURCES
                || snapshot.recentResults().size() > PetOfficeSessionRegistry.MAX_RECENT_RESULTS) {
            throw new IOException("OFFICE_SCENE_INVALID");
        }
        for (var source : snapshot.sources()) {
            if (!participants.contains(source.sourceKey().instanceId())) {
                throw new IOException("OFFICE_SCENE_INVALID");
            }
        }
        for (var result : snapshot.recentResults()) {
            if (!participants.contains(result.sourceKey().instanceId())) {
                throw new IOException("OFFICE_SCENE_INVALID");
            }
        }
        for (String participant : participants) {
            var own = new PetOfficeSessionRegistry.CompleteSnapshot(snapshot.schemaVersion(), participant, snapshot.connectionEpoch(), snapshot.watermark(), snapshot.sampledAt(),
                    snapshot.sources().stream().filter(source -> participant.equals(source.sourceKey().instanceId())).toList(),
                    snapshot.recentResults().stream().filter(result -> participant.equals(result.sourceKey().instanceId())).toList(), snapshot.capacityReached());
            PetOfficeSnapshotCodec.encode(own, snapshot.connectionEpoch());
        }
        JsonObject result = JSON.toJsonTree(snapshot).getAsJsonObject();
        result.add("participants", JSON.toJsonTree(view.participants()));
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > PetOfficeSnapshotCodec.MAX_BYTES) {
            throw new IOException("OFFICE_SCENE_CAPACITY");
        }
        return result;
    }
}
