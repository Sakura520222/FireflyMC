package firefly520.fireflymc.client.relay.p2p;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Minimal packet codec for the first P2P MVP.
 *
 * <p>Probe/punch packets are JSON for easy interoperability with the AstrBot relay.
 * Reliable data packets use a compact binary header and are intentionally kept small
 * to avoid IP fragmentation.</p>
 */
public final class UdpPacketCodec {
    public static final String MAGIC = "fireflymc-p2p";
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_PAYLOAD_SIZE = 1200;
    private static final int BINARY_MAGIC = 0x46465032; // FFP2
    private static final Gson GSON = new Gson();

    private UdpPacketCodec() {
    }

    public static byte[] probe(String roomId, String guestSessionId, String role, String token) {
        JsonObject json = new JsonObject();
        json.addProperty("magic", MAGIC);
        json.addProperty("type", "probe");
        json.addProperty("version", PROTOCOL_VERSION);
        json.addProperty("roomId", roomId);
        json.addProperty("guestSessionId", guestSessionId);
        json.addProperty("role", role);
        json.addProperty("p2pToken", token);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] punch(String roomId, String guestSessionId, String role, String token) {
        JsonObject json = new JsonObject();
        json.addProperty("magic", MAGIC);
        json.addProperty("type", "punch");
        json.addProperty("version", PROTOCOL_VERSION);
        json.addProperty("roomId", roomId);
        json.addProperty("guestSessionId", guestSessionId);
        json.addProperty("role", role);
        json.addProperty("p2pToken", token);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] data(int streamId, int seq, int ack, byte flags, byte[] payload, int length) {
        int safeLength = Math.min(length, MAX_PAYLOAD_SIZE);
        ByteBuffer buffer = ByteBuffer.allocate(21 + safeLength);
        buffer.putInt(BINARY_MAGIC);
        buffer.put((byte) PROTOCOL_VERSION);
        buffer.put((byte) 1); // data
        buffer.putInt(streamId);
        buffer.putInt(seq);
        buffer.putInt(ack);
        buffer.put(flags);
        buffer.putShort((short) safeLength);
        buffer.put(payload, 0, safeLength);
        return buffer.array();
    }

    public static DecodedData decodeData(byte[] packet) {
        if (packet.length < 21) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        if (buffer.getInt() != BINARY_MAGIC) {
            return null;
        }
        int version = Byte.toUnsignedInt(buffer.get());
        int type = Byte.toUnsignedInt(buffer.get());
        if (version != PROTOCOL_VERSION || type != 1) {
            return null;
        }
        int streamId = buffer.getInt();
        int seq = buffer.getInt();
        int ack = buffer.getInt();
        byte flags = buffer.get();
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length > buffer.remaining()) {
            return null;
        }
        byte[] payload = new byte[length];
        buffer.get(payload);
        return new DecodedData(streamId, seq, ack, flags, payload);
    }

    public record DecodedData(int streamId, int seq, int ack, byte flags, byte[] payload) {
    }
}
