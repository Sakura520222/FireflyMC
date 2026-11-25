package firefly520.fireflymc.ai;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import firefly520.fireflymc.ai.model.*;
import firefly520.fireflymc.ai.security.SecretFilter;
import firefly520.fireflymc.ai.task.TaskControl;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelStreamingTest {
    private static String frame(String json) { return "data: " + json.replace("\n", "").replace("\r", "") + "\r\n\r\n"; }
    private static String chunk(String delta) { return frame("{\"choices\":[{\"index\":0,\"delta\":" + delta + "}]}"); }
    private static void bytes(StreamingResponseParser parser, String text) throws Exception {
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) parser.accept(value);
    }

    @Test void byteFragmentsPreserveChineseAndInterleavedCallsWithoutPrematureExecutionEvents() throws Exception {
        List<ModelEvent> events = new ArrayList<>();
        var parser = new StreamingResponseParser(false, events::add);
        bytes(parser, ": heartbeat\r\n" + chunk("{\"role\":\"assistant\",\"content\":\"小萤正在核对\",\"reasoning_content\":\"private\"}"));
        bytes(parser, chunk("""
                {"tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"second","arguments":"{\\"n\\":"}},
                {"index":0,"id":"a","type":"function","function":{"name":"first","arguments":"{"}}]}
                """));
        bytes(parser, chunk("""
                {"tool_calls":[{"index":0,"function":{"arguments":"}"}},{"index":1,"function":{"arguments":"2}"}}]}
                """));
        assertTrue(events.stream().allMatch(e -> e instanceof ModelEvent.TextDelta));
        bytes(parser, frame("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"));
        bytes(parser, frame("{\"choices\":[],\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":13}}") + frame("[DONE]"));
        var reply = new ChatCompletionsAdapter().decode(parser.finish());
        assertEquals("小萤正在核对", reply.message().text());
        assertEquals(List.of("a", "b"), reply.message().calls().stream().map(ModelMessage.ToolCall::id).toList());
        assertEquals("{\"n\":2}", reply.message().calls().get(1).arguments());
        assertEquals(21, reply.inputTokens());
        assertEquals("private", reply.message().providerState().get("reasoning_content").getAsString());
        assertTrue(events.stream().noneMatch(e -> e.toString().contains("private")));
    }

    @Test void responsesCommitOnlyFinalItemsAndRetainOpaqueReasoningAcrossNextRequest() throws Exception {
        List<ModelEvent> events = new ArrayList<>();
        var parser = new StreamingResponseParser(true, events::add);
        bytes(parser, frame("{\"type\":\"response.output_text.delta\",\"delta\":\"核验中\"}"));
        bytes(parser, frame("{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\"}"));
        assertThrows(ModelGateway.Failure.class, parser::finish);
        bytes(parser, frame("""
                {"type":"response.completed","response":{"status":"completed","output":[
                {"type":"reasoning","id":"r1","summary":[],"encrypted_content":"opaque"},
                {"type":"function_call","id":"fc1","call_id":"a","name":"lookup","arguments":"{}"}],
                "usage":{"input_tokens":20,"output_tokens":9}}}
                """));
        var protocol = new ResponsesAdapter();
        var reply = protocol.decode(parser.finish());
        var wire = protocol.encode("test", new ModelGateway.Request(List.of(reply.message(),
                ModelMessage.result("a", "verified")), List.of(), 128, false));
        assertTrue(wire.toString().contains("opaque"));
        assertTrue(wire.toString().contains("function_call_output"));
        assertEquals(1, events.size());
        assertInstanceOf(ModelEvent.TextDelta.class, events.getFirst());
    }

    @Test void truncatedStreamsNeverBecomeAcceptedCallsAndCredentialSuffixIsHeld() throws Exception {
        var parser = new StreamingResponseParser(false, ignored -> {});
        bytes(parser, chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"place\",\"arguments\":\"{\"}}]}"));
        assertThrows(ModelGateway.Failure.class, () -> new ChatCompletionsAdapter().decode(parser.finish()));
        var secret = "sk-testsecretabcdef0123456789";
        var filter = new SecretFilter(List.of(secret));
        String source = "ordinary ".repeat(20) + secret + " suffix".repeat(40);
        for (int i = 0; i <= source.length(); i++) {
            String visible = filter.streamingPrefix(source.substring(0, i));
            assertFalse(visible.contains("sk-test"), visible);
        }
    }

    @Test void realHttpStreamPublishesDeltasThenAcknowledgementsAndCompletion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<ModelEvent> events = new CopyOnWriteArrayList<>();
        server.createContext("/chat/completions", exchange -> {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(request.get("stream").getAsBoolean());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String data = chunk("{\"content\":\"实测\"}") + frame(
                    "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}") + frame("[DONE]");
            for (byte value : data.getBytes(StandardCharsets.UTF_8)) {
                exchange.getResponseBody().write(value);
                exchange.getResponseBody().flush();
            }
            exchange.close();
        });
        server.start();
        try {
            var gateway = new HttpModelGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test", "test", new ChatCompletionsAdapter(), 5000, 0);
            var reply = gateway.complete(new ModelGateway.Request(List.of(ModelMessage.result("previous", "result")),
                    List.of(), 128, false, "auto", true), new TaskControl(10000), events::add);
            assertEquals("实测", reply.message().text());
            assertInstanceOf(ModelEvent.TextDelta.class, events.getFirst());
            assertTrue(events.stream().anyMatch(e -> e instanceof ModelEvent.ToolResultAck ack && ack.callId().equals("previous")));
            assertInstanceOf(ModelEvent.Completed.class, events.getLast());
        } finally { server.stop(0); }
    }

    @Test void cancelledHttpStreamReportsSafeErrorAndDoesNotCompletePartialToolCall() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(frame("{\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            List<ModelEvent> events = new CopyOnWriteArrayList<>();
            var control = new TaskControl(10000);
            var gateway = new HttpModelGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                    "test", "test", new ResponsesAdapter(), 5000, 0);
            var stopped = assertThrows(TaskControl.Stopped.class, () -> gateway.complete(new ModelGateway.Request(
                    List.of(), List.of(), 128, false, "auto", true), control, event -> {
                events.add(event);
                if (event instanceof ModelEvent.TextDelta) control.stop("CANCELLED");
            }));
            assertEquals("CANCELLED", stopped.getMessage());
            assertInstanceOf(ModelEvent.Error.class, events.getLast());
            assertTrue(events.stream().noneMatch(e -> e instanceof ModelEvent.Completed || e instanceof ModelEvent.ToolCalls));
        } finally { release.countDown(); server.stop(0); executor.close(); }
    }
}
