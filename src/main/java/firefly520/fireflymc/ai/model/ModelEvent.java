package firefly520.fireflymc.ai.model;

import java.util.List;

/** Transient provider events. Only completed, validated calls may reach the tool executor. */
public sealed interface ModelEvent {
    record TextDelta(String text) implements ModelEvent {}
    record ToolCalls(List<ModelMessage.ToolCall> calls) implements ModelEvent {}
    record ToolResultAck(String callId) implements ModelEvent {}
    record Error(String code) implements ModelEvent {}
    record Completed(ModelGateway.Reply reply) implements ModelEvent {}
}
