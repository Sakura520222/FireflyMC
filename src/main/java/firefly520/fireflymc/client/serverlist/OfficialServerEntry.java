package firefly520.fireflymc.client.serverlist;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 官方服务器在原版多人游戏列表中的条目。
 * <p>
 * 继承原版 {@link ServerSelectionList.OnlineServerEntry}：MOTD / 人数 / 延迟 / 图标 / ping 全部沿用原版逻辑，
 * 双击、回车、「加入服务器」按钮也都走原版的连接路径。
 * <p>
 * 与原版条目的区别（官方条目由后台统一管理，不在 servers.dat 里）：
 * <ul>
 *   <li>不参与拖动排序——原版的上移 / 下移把行号直接当 servers.dat 下标，官方条目排在玩家条目之后，
 *       参与的话会数组越界；</li>
 *   <li>「编辑 / 删除」按钮由 JoinMultiplayerScreenMixin 置灰。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class OfficialServerEntry extends ServerSelectionList.OnlineServerEntry {

    // 与原版 ServerSelectionList 中的同名常量一致（那边是包私有，这里无法直接引用）
    private static final ResourceLocation JOIN_HIGHLIGHTED_SPRITE =
            ResourceLocation.withDefaultNamespace("server_list/join_highlighted");
    private static final ResourceLocation JOIN_SPRITE =
            ResourceLocation.withDefaultNamespace("server_list/join");

    private final ServerSelectionList list;
    private final JoinMultiplayerScreen screen;
    private long lastClickTime;

    public OfficialServerEntry(ServerSelectionList list, JoinMultiplayerScreen screen, ServerData serverData) {
        list.super(screen, serverData);
        this.list = list;
        this.screen = screen;
    }

    @Override
    public void render(
            GuiGraphics guiGraphics,
            int index,
            int top,
            int left,
            int width,
            int height,
            int mouseX,
            int mouseY,
            boolean hovering,
            float partialTick
    ) {
        // 原版的悬停浮层会按「行号 == servers.dat 下标」画上移 / 下移箭头，官方条目不参与排序：
        // 先以 hovering=false 走一遍原版渲染（其余绘制不依赖 hovering），再自绘只含「加入」箭头的浮层
        super.render(guiGraphics, index, top, left, width, height, mouseX, mouseY, false, partialTick);

        if (Minecraft.getInstance().options.touchscreen().get() || hovering) {
            guiGraphics.fill(left, top, left + 32, top + 32, -1601138544);
            int relativeX = mouseX - left;
            if (relativeX < 32 && relativeX > 16) {
                guiGraphics.blitSprite(JOIN_HIGHLIGHTED_SPRITE, left, top, 32, 32);
            } else {
                guiGraphics.blitSprite(JOIN_SPRITE, left, top, 32, 32);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double relativeX = mouseX - (double) this.list.getRowLeft();
        if (relativeX < 32.0 && relativeX > 16.0) {
            // 图标上的「加入」箭头：与原版一致，直接加入
            this.screen.setSelected(this);
            this.screen.joinSelectedServer();
            return true;
        }

        // 图标四角（原版在这里上移 / 下移）只做选中；其余位置与原版一致：选中 + 双击加入
        this.screen.setSelected(this);
        if (Util.getMillis() - this.lastClickTime < 250L) {
            this.screen.joinSelectedServer();
        }
        this.lastClickTime = Util.getMillis();
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 不提供 Shift + ↑/↓ 调整顺序；回车加入等按键由 JoinMultiplayerScreen 处理
        return false;
    }
}
