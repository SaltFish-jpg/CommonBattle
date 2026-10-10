package com.commonbattle.example.cross;

import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnterSceneRequestCodecTest {
    @Test
    void decodesOldRequestWithoutChunkFieldsAsOrigin() {
        EnterSceneRequestCodec codec = new EnterSceneRequestCodec();
        byte[] oldVersionBytes = oldVersionPayload(10001L, "world-1");

        EnterSceneRequest decoded = codec.decode(oldVersionBytes);

        assertEquals(10001L, decoded.playerId());
        assertEquals("world-1", decoded.sceneId());
        assertEquals(0, decoded.chunkX());
        assertEquals(0, decoded.chunkY());
    }

    private static byte[] oldVersionPayload(long playerId, String sceneId) {
        int size = CodedOutputStream.computeInt64Size(1, playerId)
                + CodedOutputStream.computeStringSize(2, sceneId);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, playerId);
            output.writeString(2, sceneId);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode old enter scene request", e);
        }
    }
}
