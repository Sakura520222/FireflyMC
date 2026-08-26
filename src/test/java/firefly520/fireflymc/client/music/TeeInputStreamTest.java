package firefly520.fireflymc.client.music;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TeeInputStreamTest {

    @Test
    void healthyBranchReceivesData() throws IOException {
        ByteArrayOutputStream branch = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream("abc".getBytes());
        TeeInputStream tee = new TeeInputStream(in, branch);
        byte[] buf = new byte[8];
        int n = tee.read(buf);
        assertEquals(3, n);
        assertFalse(tee.isBranchBroken());
        assertEquals("abc", branch.toString());
    }

    @Test
    void branchFailureIsQueryableButMainStreamContinues() throws IOException {
        // 磁盘满等场景：分支写抛 IOException → 主流程继续读，失败状态必须可查询
        //（调用方据此禁止把残缺 .part finalize 成有效缓存）
        OutputStream brokenBranch = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        };
        ByteArrayInputStream in = new ByteArrayInputStream("hello world".getBytes());
        TeeInputStream tee = new TeeInputStream(in, brokenBranch);
        byte[] buf = new byte[8192];
        int n = tee.read(buf);
        assertEquals("hello world".length(), n, "主流读取不受分支失败影响");
        assertEquals("hello world", new String(buf, 0, n));
        assertTrue(tee.isBranchBroken(), "分支失败状态必须暴露给调用方");
    }
}
