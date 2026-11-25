package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;

/** An observed world report. Structure and usable functionality are deliberately separate facts. */
public record ConstructionVerification(int version, UUID planId, String planHash, String dimension, long registryGeneration,
        long checkedAt, long serverTick, int targetCount, int verifiedTargets, int unobservedTargets,
        List<Difference> differences, List<Function> functions, boolean truncated) {
    public enum FunctionalState { NOT_APPLICABLE, READY, WAITING_ACTIVATION, UNVERIFIED }
    public record Difference(BlueprintDocument.Position position, BlueprintDocument.State expected, BlueprintDocument.State actual, String reason) { }
    public record Function(BlueprintDocument.Position position, String blockId, String mechanism, FunctionalState state,
                           List<String> reasons, Map<String, String> observed) {
        public Function {
            Objects.requireNonNull(position); Objects.requireNonNull(state); reasons = List.copyOf(reasons);
            observed = Collections.unmodifiableMap(new TreeMap<>(observed));
            if (blockId == null || mechanism == null || reasons.size() > 16 || observed.size() > 24)
                throw new IllegalArgumentException("BUILD_FUNCTION_REPORT_INVALID");
        }
    }
    public ConstructionVerification {
        differences = List.copyOf(differences); functions = List.copyOf(functions);
        Objects.requireNonNull(planId);
        if (version != 1 || planHash == null || !planHash.matches("[0-9a-f]{64}") || dimension == null
                || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || registryGeneration < 0 || checkedAt <= 0 || serverTick < 0
                || targetCount < 1 || verifiedTargets < 0 || unobservedTargets < 0 || verifiedTargets + unobservedTargets > targetCount
                || differences.size() > 64 || functions.size() > 128)
            throw new IllegalArgumentException("BUILD_VERIFICATION_INVALID");
    }
    public boolean structureComplete() { return verifiedTargets == targetCount && unobservedTargets == 0; }
    public FunctionalState functionality() {
        if (!structureComplete() || truncated || functions.stream().anyMatch(row -> row.state() == FunctionalState.UNVERIFIED))
            return FunctionalState.UNVERIFIED;
        if (functions.stream().anyMatch(row -> row.state() == FunctionalState.WAITING_ACTIVATION)) return FunctionalState.WAITING_ACTIVATION;
        return functions.isEmpty() ? FunctionalState.NOT_APPLICABLE : FunctionalState.READY;
    }
    public String structureState() {
        return structureComplete() ? "STRUCTURE_COMPLETE" : unobservedTargets > 0 ? "STRUCTURE_UNVERIFIED" : "PARTIALLY_BUILT";
    }
    public String summary() {
        if (!structureComplete()) return "结构尚未完整核验：" + verifiedTargets + "/" + targetCount + " 格匹配；未确认功能运行。";
        return switch (functionality()) {
            case NOT_APPLICABLE -> "结构已完成；此计划没有需要运行条件核验的功能模块。";
            case READY -> "结构已完成；列出的原生运行或交互条件已核验，不代表自动加工、自动使用或长期运行成功。";
            case WAITING_ACTIVATION -> "结构已完成、功能待激活；真实机器仍需其原生物料、供能与工作条件，未自动补给或豁免费用。";
            case UNVERIFIED -> "结构已完成；功能条件尚未全部核验，未宣称机器可运行。";
        };
    }
    public String digest() { return AiJson.digest(AiJson.canonical(this)); }
}
