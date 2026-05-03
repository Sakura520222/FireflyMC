package firefly520.fireflymc.client.relay.p2p;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeMap;

final class ReorderBuffer {
    private int expectedSeq = 1;
    private final Map<Integer, byte[]> pending = new TreeMap<>();

    synchronized void accept(int seq, byte[] payload, OutputStream output) throws IOException {
        if (seq < expectedSeq) {
            return;
        }
        if (seq == expectedSeq) {
            output.write(payload);
            expectedSeq++;
            while (true) {
                byte[] next = pending.remove(expectedSeq);
                if (next == null) {
                    break;
                }
                output.write(next);
                expectedSeq++;
            }
            output.flush();
            return;
        }
        pending.putIfAbsent(seq, payload);
    }

    synchronized int ackSeq() {
        return expectedSeq - 1;
    }
}
