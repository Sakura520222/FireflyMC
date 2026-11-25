package firefly520.fireflymc.client.serverlist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官方服务器列表的状态机：拉取 / 节流 / 失败回退 / 本地缓存。
 * 传输、时钟与两个执行器全部注入，不访问网络也不依赖 Minecraft。
 */
class OfficialServerListTest {

    private static final String URL = "https://example.invalid/api/servers";
    private static final String TWO_SERVERS = """
            {"servers":[
              {"id":1,"name":"一区","address":"a.example.com"},
              {"id":2,"name":"二区","address":"b.example.com"}
            ]}""";
    /** 与 TWO_SERVERS 内容相同、顺序对调 */
    private static final String TWO_SERVERS_REVERSED = """
            {"servers":[
              {"id":2,"name":"二区","address":"b.example.com"},
              {"id":1,"name":"一区","address":"a.example.com"}
            ]}""";

    /** 可控的假后台：记录请求，按先后次序返回预设结果（String = 响应体，Exception = 抛出）。 */
    private static final class FakeBackend implements OfficialServerList.Transport {
        final List<String> requestedUrls = new ArrayList<>();
        final Deque<Object> replies = new ArrayDeque<>();

        void reply(String body) {
            replies.addLast(body);
        }

        void fail(Exception error) {
            replies.addLast(error);
        }

        @Override
        public String fetch(String url) throws Exception {
            requestedUrls.add(url);
            Object reply = replies.pollFirst();
            if (reply instanceof Exception error) {
                throw error;
            }
            return (String) reply;
        }
    }

    /** 只收集任务、不执行：用来模拟卡住的拉取，或检查结果落在哪个执行器上。 */
    private static final class CapturingExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }

    private static final class MutableClock extends Clock {
        private long now = 1_000_000L;

        void advance(long millis) {
            now += millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }
    }

    /** 全部同步执行的实例：拉取线程与客户端主线程都直接在调用线程上跑。 */
    private static final class Fixture {
        final FakeBackend backend = new FakeBackend();
        final MutableClock clock = new MutableClock();
        final OfficialServerList list;
        int notifications;

        Fixture(Path cache) {
            list = new OfficialServerList(
                    settings(true, URL), backend, Runnable::run, Runnable::run, clock, cache);
            list.setChangeListener(() -> notifications++);
        }
    }

    private static OfficialServerList.Settings settings(boolean enabled, String url) {
        return new OfficialServerList.Settings() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public String url() {
                return url;
            }
        };
    }

    private static List<String> names(OfficialServerList list) {
        return list.servers().stream().map(OfficialServer::name).toList();
    }

    @Test
    void disabledSettingsNeverFetch(@TempDir Path tempDir) {
        FakeBackend backend = new FakeBackend();
        OfficialServerList list = new OfficialServerList(
                settings(false, URL), backend, Runnable::run, Runnable::run, new MutableClock(),
                tempDir.resolve("cache.json"));
        list.ensureLoaded();
        list.refreshAsync();
        list.refreshAsync(true);
        assertTrue(backend.requestedUrls.isEmpty());
        assertFalse(list.enabled());
        assertTrue(list.servers().isEmpty());
    }

    @Test
    void nonHttpUrlIsIgnored(@TempDir Path tempDir) {
        FakeBackend backend = new FakeBackend();
        OfficialServerList list = new OfficialServerList(
                settings(true, "ftp://example.invalid/servers.json"), backend, Runnable::run, Runnable::run,
                new MutableClock(), tempDir.resolve("cache.json"));
        list.refreshAsync();
        list.refreshAsync(true);
        assertTrue(backend.requestedUrls.isEmpty());
    }

    @Test
    void fetchAppliesListInOrderAndNotifiesListener(@TempDir Path tempDir) {
        Fixture fixture = new Fixture(tempDir.resolve("cache.json"));
        fixture.backend.reply(TWO_SERVERS);
        assertTrue(fixture.list.servers().isEmpty());

        fixture.list.refreshAsync();
        assertEquals(List.of(URL), fixture.backend.requestedUrls);
        assertEquals(List.of("一区", "二区"), names(fixture.list));
        assertEquals("a.example.com", fixture.list.servers().get(0).address());
        assertEquals(1, fixture.notifications);

        // 地址归一化后比较：大小写与省略的端口不影响判定
        assertTrue(fixture.list.isOfficial("A.Example.com:25565"));
        assertTrue(fixture.list.isOfficial("b.example.com"));
        assertFalse(fixture.list.isOfficial("c.example.com"));
        // 本地里格式不合法的地址不会抛异常，只是不算官方
        assertFalse(fixture.list.isOfficial("not an address"));
        assertFalse(fixture.list.isOfficial(null));
    }

    @Test
    void unchangedListDoesNotNotifyAgain(@TempDir Path tempDir) {
        Fixture fixture = new Fixture(tempDir.resolve("cache.json"));
        fixture.backend.reply(TWO_SERVERS);
        // 名称、地址与顺序都相同，只有 id 不同
        fixture.backend.reply("""
                {"servers":[
                  {"id":9,"name":"一区","address":"a.example.com"},
                  {"name":"二区","address":"b.example.com"}
                ]}""");

        fixture.list.refreshAsync();
        String signature = fixture.list.signature();
        fixture.list.refreshAsync(true);
        assertEquals(2, fixture.backend.requestedUrls.size());
        assertEquals(signature, fixture.list.signature());
        assertEquals(1, fixture.notifications);
    }

    @Test
    void changedListNotifiesAndSignatureFollowsOrder(@TempDir Path tempDir) {
        Fixture fixture = new Fixture(tempDir.resolve("cache.json"));
        fixture.backend.reply(TWO_SERVERS);
        fixture.backend.reply(TWO_SERVERS_REVERSED);
        fixture.backend.reply("{\"servers\":[]}");

        fixture.list.refreshAsync();
        String before = fixture.list.signature();

        // 仅顺序不同也算变化
        fixture.list.refreshAsync(true);
        assertEquals(2, fixture.notifications);
        assertFalse(before.equals(fixture.list.signature()));
        assertEquals(List.of("二区", "一区"), names(fixture.list));

        // 后台全部下架：列表清空
        fixture.list.refreshAsync(true);
        assertTrue(fixture.list.servers().isEmpty());
        assertEquals("", fixture.list.signature());
        assertEquals(3, fixture.notifications);
    }

    @Test
    void failedFetchKeepsPreviousList(@TempDir Path tempDir) {
        Fixture fixture = new Fixture(tempDir.resolve("cache.json"));
        fixture.backend.reply(TWO_SERVERS);
        fixture.backend.fail(new IOException("connection reset"));
        fixture.backend.reply("{\"servers\":[{\"name\":\"坏数据\",\"address\":\"http://bad\"}]}"); // 整体拒绝

        fixture.list.refreshAsync();
        fixture.list.refreshAsync(true);
        fixture.list.refreshAsync(true);
        assertEquals(3, fixture.backend.requestedUrls.size());
        assertEquals(List.of("一区", "二区"), names(fixture.list));
        assertEquals(1, fixture.notifications);
    }

    @Test
    void refreshIsThrottledUnlessForced(@TempDir Path tempDir) {
        Fixture fixture = new Fixture(tempDir.resolve("cache.json"));

        fixture.list.refreshAsync();
        assertEquals(1, fixture.backend.requestedUrls.size());

        // 节流间隔内再次打开界面（重新进入 / F5 之外的操作）不重复请求
        fixture.clock.advance(5_000L);
        fixture.list.refreshAsync();
        assertEquals(1, fixture.backend.requestedUrls.size());

        // 原版「刷新」按钮跳过节流
        fixture.list.refreshAsync(true);
        assertEquals(2, fixture.backend.requestedUrls.size());

        // 超过节流间隔后恢复自动拉取
        fixture.clock.advance(30_000L);
        fixture.list.refreshAsync();
        assertEquals(3, fixture.backend.requestedUrls.size());
    }

    @Test
    void hungFetchDoesNotBlockLaterRefreshes(@TempDir Path tempDir) {
        FakeBackend backend = new FakeBackend();
        CapturingExecutor fetchExecutor = new CapturingExecutor();
        MutableClock clock = new MutableClock();
        OfficialServerList list = new OfficialServerList(
                settings(true, URL), backend, fetchExecutor, Runnable::run, clock,
                tempDir.resolve("cache.json"));

        list.refreshAsync();
        assertEquals(1, fetchExecutor.tasks.size());
        // 上一次还没返回：不重复发起
        list.refreshAsync(true);
        assertEquals(1, fetchExecutor.tasks.size());

        // 超过看门狗时长后允许再次发起
        clock.advance(61_000L);
        backend.reply(TWO_SERVERS);
        list.refreshAsync();
        assertEquals(2, fetchExecutor.tasks.size());

        // 两个请求按先后次序返回：先处理的那次拿到预设响应并成功，另一次以失败结束，结果以成功的为准
        fetchExecutor.runAll();
        assertEquals(List.of("一区", "二区"), names(list));
    }

    @Test
    void fetchResultIsDeliveredOnClientExecutor(@TempDir Path tempDir) {
        FakeBackend backend = new FakeBackend();
        backend.reply(TWO_SERVERS);
        CapturingExecutor clientExecutor = new CapturingExecutor();
        OfficialServerList list = new OfficialServerList(
                settings(true, URL), backend, Runnable::run, clientExecutor, new MutableClock(),
                tempDir.resolve("cache.json"));

        list.refreshAsync();
        // 网络线程拿到结果后不在自己线程上改状态
        assertTrue(list.servers().isEmpty());
        assertEquals(1, clientExecutor.tasks.size());
        clientExecutor.runAll();
        assertEquals(List.of("一区", "二区"), names(list));
    }

    @Test
    void listIsCachedAndReloadedWithoutNetwork(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache.json");
        Fixture first = new Fixture(cache);
        first.backend.reply(TWO_SERVERS);
        first.list.refreshAsync();
        assertTrue(Files.exists(cache));

        // 下一次启动时后台不可达：仅靠缓存就能拿到同一份列表（缓存不保存 id，名称与地址一致即可）
        Fixture second = new Fixture(cache);
        second.list.ensureLoaded();
        assertEquals(names(first.list), names(second.list));
        assertEquals(first.list.signature(), second.list.signature());
        assertTrue(second.list.isOfficial("a.example.com:25565"));
        assertTrue(second.backend.requestedUrls.isEmpty());

        // 后台恢复后返回相同内容：不算变化，不会触发界面重建
        second.backend.reply(TWO_SERVERS);
        second.list.refreshAsync();
        assertEquals(List.of(URL), second.backend.requestedUrls);
        assertEquals(0, second.notifications);
    }

    @Test
    void savedStateRoundTripsThroughCache(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache.json");
        String a = "a.example.com:25565";
        String b = "b.example.com:25565";

        Fixture first = new Fixture(cache);
        first.backend.reply(TWO_SERVERS);
        first.list.refreshAsync();
        assertNull(first.list.savedState(a));

        first.list.updateState(a, "iYVBORw0KGgo=", "ENABLED");
        first.list.updateState(b, null, "DISABLED");
        first.list.flush();

        Fixture second = new Fixture(cache);
        second.list.ensureLoaded();
        assertEquals(new OfficialServerList.SavedState("iYVBORw0KGgo=", "ENABLED"), second.list.savedState(a));
        assertEquals(new OfficialServerList.SavedState(null, "DISABLED"), second.list.savedState(b));

        // 地址下架后不再保留它的图标 / 资源包选择，仍在列表里的保留
        Fixture third = new Fixture(cache);
        third.backend.reply(body("b.example.com"));
        third.list.refreshAsync();
        assertNull(third.list.savedState(a));
        assertEquals(new OfficialServerList.SavedState(null, "DISABLED"), third.list.savedState(b));
        assertNotNull(third.list.signature());
    }

    @Test
    void corruptCacheIsIgnored(@TempDir Path tempDir) throws IOException {
        Path cache = tempDir.resolve("cache.json");
        for (String content : List.of(
                "not json",
                "[]",
                "{\"version\":2,\"servers\":[]}",
                "{\"version\":\"1\",\"servers\":[]}",
                "{\"version\":1}")) {
            Files.writeString(cache, content);
            Fixture fixture = new Fixture(cache);
            fixture.list.ensureLoaded();
            assertTrue(fixture.list.servers().isEmpty(), content);
        }

        // 缓存是尽力而为：单条损坏只跳过该条，重复地址只留第一条
        Files.writeString(cache, """
                {"version":1,"servers":[
                  {"name":"好的","address":"a.example.com"},
                  {"name":"坏地址","address":"http://bad"},
                  "不是对象",
                  {"address":"b.example.com"},
                  {"name":"重复","address":"A.Example.com:25565"}
                ]}""");
        Fixture fixture = new Fixture(cache);
        fixture.list.ensureLoaded();
        assertEquals(List.of("好的"), names(fixture.list));
    }

    @Test
    void nothingIsWrittenWithoutChanges(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache.json");
        Fixture fixture = new Fixture(cache);
        fixture.list.ensureLoaded();
        fixture.list.flush();
        fixture.backend.fail(new IOException("down"));
        fixture.list.refreshAsync();
        fixture.list.flush();
        assertFalse(Files.exists(cache));
    }

    private static String body(String... addresses) {
        StringBuilder json = new StringBuilder("{\"servers\":[");
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(i + 1).append(",\"name\":\"s").append(i + 1)
                    .append("\",\"address\":\"").append(addresses[i]).append("\"}");
        }
        return json.append("]}").toString();
    }
}
