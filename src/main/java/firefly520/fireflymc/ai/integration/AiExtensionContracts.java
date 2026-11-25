package firefly520.fireflymc.ai.integration;

import java.util.List;
import java.util.UUID;

/** P1-P3 extension contracts only; no capabilities are advertised as executable by these types. */
public final class AiExtensionContracts {
    private AiExtensionContracts() {}
    public enum CostPolicy { MATERIALS_ONLY }
    public enum Readiness { DISCOVERED, STATIC_API_MATCH, RUNTIME_READY, UNSUPPORTED_VERSION, FAILED }
    public enum Side { CLIENT, SERVER }
    public record IntegrationDescriptor(String adapterId, List<String> requiredModIds,
            List<String> testedVersions, Side logicalSide, int protocolVersion,
            List<String> requiredSignatures, List<String> providedCapabilities,
            List<String> limitations, String probeEvidence, Readiness state) {}
    public record PlanIdentity(UUID taskId, int planRevision, String planHash, long recipeGeneration,
                               String costPolicyHash, CostPolicy costPolicy) {}
    public record UiEnvelope(int version, UUID primaryConversationId, long seq, UUID messageId,
                             UUID taskId, List<ResponseBlock> blocks) {}
    /** Render as inert text until the client implements and validates additional block types. */
    public record ResponseBlock(String type, String text) {}
}
