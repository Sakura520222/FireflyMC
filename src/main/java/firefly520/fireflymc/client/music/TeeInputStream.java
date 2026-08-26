package firefly520.fireflymc.client.music;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 输入流 Tee：读取数据同时写入缓存分支（缓存写失败仅降级停止拷贝，不影响播放） */
public class TeeInputStream extends FilterInputStream {
    private final OutputStream branch;
    private volatile boolean branchBroken = false;

    public TeeInputStream(InputStream in, OutputStream branch) {
        super(in);
        this.branch = branch;
    }

    /** 缓存分支是否已失败（磁盘满等）：调用方据此禁止 finalize 残缺 .part 为有效缓存 */
    public boolean isBranchBroken() {
        return branchBroken;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0 && !branchBroken) {
            try {
                branch.write(b, off, n);
            } catch (IOException e) {
                branchBroken = true; // 磁盘满等：静默降级，仅不缓存
            }
        }
        return n;
    }
}
