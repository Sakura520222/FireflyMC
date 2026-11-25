package firefly520.fireflymc.ai;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import firefly520.fireflymc.ai.model.*;
import firefly520.fireflymc.ai.task.TaskControl;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelProtocolTest {
    private JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private ModelGateway.Request request(List<ModelMessage> messages) {
        return new ModelGateway.Request(messages, List.of(), 128, false);
    }
    private static final String TEXT = """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"answer"}}],
            "usage":{"prompt_tokens":12,"completion_tokens":7}}
            """;

    @Test void chatNullContentMultipleCallsAndAllResultsRoundTrip() throws Exception {
        var adapter = new ChatCompletionsAdapter();
        var reply = adapter.decode(json("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                {"id":"a","type":"function","function":{"name":"one","arguments":"{}"}},
                {"id":"b","type":"function","function":{"name":"two","arguments":"{\\"x\\":2}"}}]}}]}
                """));
        assertEquals(2, reply.message().calls().size());
        var wire = adapter.encode("test", request(List.of(reply.message(),
                ModelMessage.result("a", "first"), ModelMessage.result("b", "second"))));
        assertEquals(2, wire.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("tool_calls").size());
        assertEquals("b", wire.getAsJsonArray("messages").get(2).getAsJsonObject().get("tool_call_id").getAsString());
        assertFalse(wire.has("tools"));
    }

    @Test void responsesRetainsOpaqueReasoningAndUsesCallIdNotOutputItemId() throws Exception {
        var adapter = new ResponsesAdapter();
        var reply = adapter.decode(json("""
                {"status":"completed","output":[
                {"id":"rs1","type":"reasoning","summary":[],"encrypted_content":"opaque-state"},
                {"id":"fc1","type":"function_call","call_id":"call1","name":"lookup","arguments":"{}"}],
                "usage":{"input_tokens":10,"output_tokens":5}}
                """));
        var wire = adapter.encode("test", request(List.of(reply.message(), ModelMessage.result("call1", "ok"))));
        JsonArray input = wire.getAsJsonArray("input");
        assertEquals("opaque-state", input.get(0).getAsJsonObject().get("encrypted_content").getAsString());
        assertEquals("call1", input.get(2).getAsJsonObject().get("call_id").getAsString());
        assertEquals("function_call_output", input.get(2).getAsJsonObject().get("type").getAsString());
        assertFalse(wire.has("previous_response_id"));
        assertFalse(wire.get("store").getAsBoolean());
    }

    @Test void invalidProtocolIncompleteOutputAndDuplicateIdsFailBeforeToolExecution() {
        var chat = new ChatCompletionsAdapter();
        for (String body : List.of("{}", "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}",
                """
                {"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[
                {"id":"a","type":"function","function":{"name":"one","arguments":"{}"}},
                {"id":"a","type":"function","function":{"name":"two","arguments":"{}"}}]}}]}
                """)) {
            assertThrows(ModelGateway.Failure.class, () -> chat.decode(json(body)));
        }
        assertThrows(ModelGateway.Failure.class, () -> new ResponsesAdapter().decode(json(
                "{\"status\":\"incomplete\",\"output\":[]}")));
    }

    @Test void httpRetriesOnlyBoundedTransientResponsesAndDoesNotExposeBodyOrKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            int attempt = calls.incrementAndGet();
            byte[] body = (attempt < 3 ? "{\"error\":\"secret-test-value\"}" : TEXT).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt == 1 ? 429 : attempt == 2 ? 502 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new HttpModelGateway("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    "test-key", "test", new ChatCompletionsAdapter(), 3000, 2);
            var reply = gateway.complete(request(List.of(ModelMessage.text("user", "hello"))), new TaskControl(10000));
            assertEquals("answer", reply.message().text());
            assertEquals(3, calls.get());
            assertEquals("Bearer test-key", authorization.get());
        } finally { server.stop(0); }
    }

    @Test void unauthorizedResponseIsNotRetriedAndDoesNotExposeSecrets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"error\":\"secret-key-must-not-escape\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new HttpModelGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                    "secret-key-must-not-escape", "test", new ChatCompletionsAdapter(), 3000, 2);
            var error = assertThrows(ModelGateway.Failure.class,
                    () -> gateway.complete(request(List.of()), new TaskControl(10000)));
            assertEquals("MODEL_AUTH_ERROR", error.getMessage());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test void stalledBodyAndOversizedBodyCannotOccupyWorkerIndefinitely() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/slow/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.createContext("/large/chat/completions", exchange -> {
            byte[] bytes = new byte[2 * 1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); }
            catch (java.io.IOException ignored) { /* client is expected to abort the oversized body */ }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var slow = new HttpModelGateway(base + "/slow", "test", "test", new ChatCompletionsAdapter(), 1000, 0);
            var timeout = assertThrows(ModelGateway.Failure.class,
                    () -> slow.complete(request(List.of()), new TaskControl(10000)));
            assertEquals("MODEL_TIMEOUT", timeout.getMessage());
            release.countDown();
            var large = new HttpModelGateway(base + "/large", "test", "test", new ChatCompletionsAdapter(), 5000, 0);
            assertThrows(ModelGateway.Failure.class, () -> large.complete(request(List.of()), new TaskControl(10000)));
        } finally {
            release.countDown();
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).close();
        }
    }

    @Test void changingProviderIdentityDropsOpaqueStateButRetainsCallsAndResultsWithoutRewritingHistory() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonObject> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/responses", exchange -> {
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response = """
                    {"status":"completed","output":[{"type":"reasoning","id":"r","summary":[],"encrypted_content":"opaque-provider-state"},
                    {"type":"function_call","id":"f","call_id":"call","name":"lookup","arguments":"{}"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var original = new HttpModelGateway(endpoint, "credential-A", "test", new ResponsesAdapter(), 3000, 0);
            var first = original.complete(request(List.of(ModelMessage.text("user", "inspect"))), new TaskControl(10000));
            var history = List.of(first.message(), ModelMessage.result("call", "verified"));
            original.complete(request(history), new TaskControl(10000));
            var changed = new HttpModelGateway(endpoint, "credential-B", "test", new ResponsesAdapter(), 3000, 0);
            changed.complete(request(history), new TaskControl(10000));
            assertTrue(requests.get(1).toString().contains("opaque-provider-state"));
            assertFalse(requests.get(2).toString().contains("opaque-provider-state"));
            assertTrue(requests.get(2).toString().contains("function_call_output"));
            assertTrue(requests.get(2).toString().contains("lookup"));
            assertTrue(first.message().responseItems().toString().contains("opaque-provider-state"));
            assertFalse(first.message().providerState().toString().contains("credential-A"));
        } finally { server.stop(0); }
    }
}
