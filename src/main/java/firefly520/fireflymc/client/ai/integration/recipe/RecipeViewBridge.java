package firefly520.fireflymc.client.ai.integration.recipe;

import firefly520.fireflymc.ai.recipe.view.RecipeViewSession;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static firefly520.fireflymc.ai.recipe.view.RecipeViewData.*;

/** Bounded, connection/generation-bound display bridge. Optional clients supply hints, never authority. */
public final class RecipeViewBridge {
    public static final RecipeViewBridge INSTANCE = new RecipeViewBridge();
    public record Outcome<T>(boolean success, String code, T value) {
        static <T> Outcome<T> ok(T value) { return new Outcome<>(true, "", Objects.requireNonNull(value)); }
        static <T> Outcome<T> failed(String code) { return new Outcome<>(false, code, null); }
    }
    public record Status(Viewer viewer, String installedVersion, String state, String reason, boolean behaviorVerified) {}
    private final RecipeViewSession<RecipeViewerAccess.Entry> session = new RecipeViewSession<>();
    private final Map<Viewer, RecipeViewerAccess> adapters = new EnumMap<>(Viewer.class);
    private final Map<Viewer, Status> statuses = new EnumMap<>(Viewer.class);
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private PolymorphRecipeAccess polymorph;
    private long ticks;
    private abstract static class Job {
        final Viewer viewer;
        final Authority authority;
        final Object connection;
        final Object runtime;
        final RecipeViewerAccess access;
        final long epoch, deadline;
        RecipeViewerAccess.Cursor cursor;
        Job(Viewer viewer, Authority authority, Object connection, Object runtime, RecipeViewerAccess access,
            long epoch, long deadline) {
            this.viewer = viewer; this.authority = authority; this.connection = connection; this.runtime = runtime;
            this.access = access; this.epoch = epoch; this.deadline = deadline;
        }
        abstract boolean step() throws ReflectiveOperationException;
        abstract void fail(String code);
        void close() { if (cursor != null) cursor.close(); }
    }
    private RecipeViewBridge() {}
    public void bindAuthority(String serverIdentity, long recipeGeneration) {
        thread();
        Object connection = Minecraft.getInstance().getConnection();
        if (connection == null) { disconnect(); return; }
        if (session.bind(connection, new Authority(serverIdentity, recipeGeneration))) clearJobs("RECIPE_AUTHORITY_STALE");
    }
    public Authority authority() { return session.authority(); }
    public List<Status> statuses() {
        thread();
        for (Viewer viewer : Viewer.values()) {
            try {
                version(viewer);
                if (viewer == Viewer.POLYMORPH) {
                    if (polymorph == null) polymorph = new PolymorphRecipeAccess();
                    status(viewer, "INTERFACE_MATCHED", "");
                } else { access(viewer).runtime(); status(viewer, "RUNTIME_READY", ""); }
            } catch (Exception | LinkageError failure) { status(viewer, "UNAVAILABLE", code(failure)); }
        }
        return List.copyOf(statuses.values());
    }
    public CompletableFuture<Outcome<CandidateReport>> discover(Query query) {
        thread(); var future = new CompletableFuture<Outcome<CandidateReport>>();
        try {
            require(query.authority());
            var access = access(query.viewer()); Object runtime = access.runtime();
            session.observeRuntime(query.viewer(), runtime);
            var job = new Job(query.viewer(), query.authority(), Minecraft.getInstance().getConnection(), runtime, access, session.epoch(), ticks + 200) {
                int seen, steps;
                final List<Candidate> found = new ArrayList<>();
                @Override boolean step() throws ReflectiveOperationException {
                    if (future.isCancelled()) return true;
                    if (++steps > 4096) { fail("RECIPE_SCAN_LIMIT"); return true; }
                    if (cursor.exhausted()) { complete(false); return true; }
                    var entry = cursor.next();
                    if (entry == null) return false;
                    if (seen++ < query.offset()) return false;
                    var details = access.describe(runtime, entry);
                    String key = session.remember(viewer, entry);
                    found.add(new Candidate(viewer, entry.categoryId(), entry.displayId(), entry.backingId(), key,
                            details.inputs(), details.outputs(), details.truncated(), false));
                    if (found.size() >= query.limit()) { complete(!cursor.exhausted()); return true; }
                    return false;
                }
                private void complete(boolean more) { future.complete(Outcome.ok(new CandidateReport(authority, viewer, epoch,
                        found, more ? query.offset() + found.size() : -1, more, "CLIENT_HINT"))); }
                @Override void fail(String code) { future.complete(Outcome.failed(code)); }
            };
            job.cursor = access.discover(runtime, item(query.itemId()), query.role());
            enqueue(job);
        } catch (Exception | LinkageError failure) { future.complete(Outcome.failed(code(failure))); }
        return future;
    }
    public CompletableFuture<Outcome<DisplayReceipt>> displayExact(DisplayRequest request, String outputItemId) {
        thread(); var future = new CompletableFuture<Outcome<DisplayReceipt>>();
        try {
            require(request.authority());
            var access = access(request.viewer()); Object runtime = access.runtime();
            session.observeRuntime(request.viewer(), runtime);
            if (!access.exactDisplaySupported(runtime)) throw new IllegalStateException("JEI_EXACT_DISPLAY_UNAVAILABLE");
            var direct = access.direct(runtime, request.recipeId(), request.displayIdHint());
            var job = new Job(request.viewer(), request.authority(), Minecraft.getInstance().getConnection(), runtime, access, session.epoch(), ticks + 200) {
                RecipeViewerAccess.Entry selected = direct != null && request.recipeId().equals(direct.backingId()) ? direct : null;
                int inspected;
                boolean opened;
                long openedAt;
                @Override boolean step() throws ReflectiveOperationException {
                    if (future.isCancelled()) return true;
                    if (selected == null) {
                        if (++inspected > 4096 || cursor == null || cursor.exhausted()) { fail("EXACT_RECIPE_NOT_FOUND"); return true; }
                        var next = cursor.next();
                        if (next != null && request.recipeId().equals(next.backingId())) selected = next;
                        return false;
                    }
                    if (!opened) { access.open(runtime, selected); opened = true; openedAt = ticks; return false; }
                    if (access.visible(runtime, selected)) {
                        future.complete(Outcome.ok(new DisplayReceipt(authority, viewer, epoch, request.recipeId(), selected.displayId(),
                                DisplayMode.EXACT_SERVER_RECIPE_ID))); return true;
                    }
                    if (ticks - openedAt > 20) { fail("RECIPE_DISPLAY_NOT_CONFIRMED"); return true; }
                    return false;
                }
                @Override void fail(String code) { future.complete(Outcome.failed(code)); }
            };
            if (direct == null || !request.recipeId().equals(direct.backingId()))
                job.cursor = access.discover(runtime, item(outputItemId), Role.OUTPUT);
            enqueue(job);
        } catch (Exception | LinkageError failure) { future.complete(Outcome.failed(code(failure))); }
        return future;
    }
    public Outcome<ConflictReport> polymorphConflicts(Authority requested) {
        thread();
        try {
            require(requested); version(Viewer.POLYMORPH);
            if (polymorph == null) polymorph = new PolymorphRecipeAccess();
            var report = polymorph.conflicts(requested);
            status(Viewer.POLYMORPH, "RUNTIME_READY", ""); return Outcome.ok(report);
        } catch (Exception | LinkageError failure) { return Outcome.failed(code(failure)); }
    }
    private void enqueue(Job job) {
        if (jobs.size() >= 4) { job.close(); throw new IllegalStateException("RECIPE_QUERY_QUEUE_FULL"); }
        jobs.addLast(job);
    }
    public void tick() {
        thread(); ticks++;
        if (Minecraft.getInstance().getConnection() == null) { disconnect(); return; }
        long end = System.nanoTime() + 1_500_000;
        for (int step = 0; step < 16 && !jobs.isEmpty() && System.nanoTime() < end; step++) {
            Job job = jobs.removeFirst(); boolean done = false;
            try {
                session.require(Minecraft.getInstance().getConnection(), job.authority);
                if (job.connection != Minecraft.getInstance().getConnection()) throw new IllegalStateException("RECIPE_CONNECTION_CHANGED");
                Object runtime = job.access.runtime();
                session.observeRuntime(job.viewer, runtime);
                if (runtime != job.runtime || job.epoch != session.epoch()) throw new IllegalStateException("RECIPE_RUNTIME_CHANGED");
                if (ticks > job.deadline) throw new IllegalStateException("RECIPE_QUERY_TIMEOUT");
                done = job.step();
                status(job.viewer, "RUNTIME_READY", "");
            } catch (Exception | LinkageError failure) { job.fail(code(failure)); done = true; status(job.viewer, "UNAVAILABLE", code(failure)); }
            if (done) job.close(); else jobs.addLast(job);
        }
    }
    public void disconnect() { thread(); clearJobs("RECIPE_DISCONNECTED"); session.disconnect(); }
    private void clearJobs(String reason) { for (Job job : jobs) { job.fail(reason); job.close(); } jobs.clear(); }
    private void require(Authority authority) { session.require(Minecraft.getInstance().getConnection(), authority); }
    private RecipeViewerAccess access(Viewer viewer) throws ReflectiveOperationException {
        version(viewer);
        RecipeViewerAccess present = adapters.get(viewer);
        if (present != null) return present;
        var created = switch (viewer) {
            case EMI -> new EmiRecipeAccess(); case JEI -> new JeiRecipeAccess();
            case POLYMORPH -> throw new IllegalStateException("POLYMORPH_HAS_NO_RECIPE_VIEWER");
        };
        adapters.put(viewer, created); status(viewer, "INTERFACE_MATCHED", ""); return created;
    }
    private String version(Viewer viewer) {
        String actual = installed(viewer);
        if (actual.isEmpty()) throw new IllegalStateException("RECIPE_VIEWER_NOT_INSTALLED");
        Set<String> expected = switch (viewer) {
            case JEI -> Set.of("19.39.0.369"); case EMI -> Set.of("1.1.24+1.21.1+neoforge");
            case POLYMORPH -> Set.of("1.1.0+1.21.1");
        };
        if (!expected.contains(actual)) throw new IllegalStateException("RECIPE_VIEWER_VERSION_UNSUPPORTED");
        return actual;
    }
    private static String installed(Viewer viewer) {
        return ModList.get().getModContainerById(viewer.name().toLowerCase(Locale.ROOT))
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("");
    }
    private void status(Viewer viewer, String state, String reason) { statuses.put(viewer, new Status(viewer, installed(viewer), state, reason, false)); }
    private static ItemStack item(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        if (!BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("RECIPE_ITEM_UNAVAILABLE");
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(key));
        if (stack.isEmpty()) throw new IllegalArgumentException("RECIPE_ITEM_UNAVAILABLE");
        return stack;
    }
    private static String code(Throwable failure) {
        String text = failure.getMessage();
        return text != null && text.matches("[A-Z_]{1,100}") ? text : "RECIPE_VIEWER_API_MISMATCH";
    }
    private static void thread() { if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("CLIENT_THREAD_REQUIRED"); }
}
