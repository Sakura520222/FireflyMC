package firefly520.fireflymc.ai.model;

import com.google.gson.JsonParser;
import firefly520.fireflymc.ai.task.TaskControl;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** Bounded HTTP transport. Redirects are disabled so Authorization cannot leave the configured host. */
public final class HttpModelGateway implements ModelGateway {
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final ModelProtocol protocol;
    private final long timeoutMillis;
    private final int retries;
    private final String providerFingerprint;

    public HttpModelGateway(String baseUrl, String key, String model, ModelProtocol protocol,
                            long timeoutMillis, int retries) {
        URI base = URI.create(baseUrl.replaceAll("/+$", ""));
        if (!List.of("https", "http").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("INVALID_MODEL_ENDPOINT");
        this.endpoint = URI.create(base.toString().endsWith(protocol.path())
                ? base.toString() : base + protocol.path());
        this.key = key;
        this.model = model;
        this.protocol = protocol;
        this.timeoutMillis = timeoutMillis;
        this.retries = Math.clamp(retries, 0, 2);
        providerFingerprint = firefly520.fireflymc.ai.storage.AiJson.digest(endpoint + "\n" + model + "\n" + key);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public int maximumAttempts() { return retries + 1; }

    @Override
    public Reply complete(Request request, TaskControl control) throws Exception {
        return complete(request, control, ignored -> {});
    }

    @Override
    public Reply complete(Request request, TaskControl control, Consumer<ModelEvent> listener) throws Exception {
        try {
            return perform(request, control, listener);
        } catch (Failure failure) {
            listener.accept(new ModelEvent.Error(failure.getMessage()));
            throw failure;
        } catch (TaskControl.Stopped stopped) {
            listener.accept(new ModelEvent.Error(stopped.getMessage()));
            throw stopped;
        }
    }

    private Reply perform(Request request, TaskControl control, Consumer<ModelEvent> listener) throws Exception {
        if (key == null || key.isBlank() || "your-api-key-here".equals(key))
            throw new Failure("API_KEY_NOT_CONFIGURED");
        var compatible = request.messages().stream().map(message -> {
            String origin = ModelProtocol.string(message.providerState(), "_providerFingerprint", "");
            if (origin.equals(providerFingerprint) || message.responseItems().isEmpty() && message.providerState().isEmpty())
                return message;
            // Durable normalized text/calls/results survive a configuration change. Encrypted provider
            // state is meaningful only to the original endpoint/model/credential and is never forwarded.
            return new ModelMessage(message.role(), message.text(), message.calls(), message.toolCallId(), null, null);
        }).toList();
        Request wireRequest = new Request(compatible, request.tools(), request.maxOutputTokens(),
                request.parallelToolCalls(), request.toolChoice(), request.stream());
        for (int attempt = 0; attempt <= retries; attempt++) {
            control.check();
            HttpRequest http = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(Math.max(1, Math.min(timeoutMillis, control.remainingMillis()))))
                    .header("Content-Type", "application/json")
                    .header("Accept", request.stream() ? "text/event-stream" : "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(protocol.encode(model, wireRequest).toString())).build();
            HttpResponse<byte[]> response;
            try {
                response = control.await(client.sendAsync(http, info ->
                                info.statusCode() == 200 && info.headers().firstValue("Content-Type").orElse("")
                                        .toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")
                                        ? new StreamingBody(protocol.path().equals("/responses"), listener, control)
                                        : new LimitedBody(2 * 1024 * 1024)),
                        timeoutMillis);
            } catch (TaskControl.Stopped e) {
                throw e;
            } catch (TimeoutException | HttpTimeoutException e) {
                throw new Failure("MODEL_TIMEOUT");
            } catch (Failure e) {
                throw e;
            } catch (Exception e) {
                throw new Failure("MODEL_NETWORK_ERROR");
            }
            int status = response.statusCode();
            if (status == 200) {
                try {
                    Reply decoded = protocol.decode(firefly520.fireflymc.ai.storage.AiJson.object(new String(response.body(),
                            StandardCharsets.UTF_8), 2 * 1024 * 1024, 48));
                    var message = decoded.message();
                    var state = message.providerState().deepCopy();
                    state.addProperty("_providerFingerprint", providerFingerprint);
                    Reply reply = new Reply(new ModelMessage(message.role(), message.text(), message.calls(),
                            message.toolCallId(), message.responseItems(), state),
                            decoded.inputTokens(), decoded.outputTokens(), attempt + 1);
                    if (!reply.message().calls().isEmpty()) listener.accept(new ModelEvent.ToolCalls(reply.message().calls()));
                    request.messages().stream().filter(m -> m.toolCallId() != null)
                            .forEach(m -> listener.accept(new ModelEvent.ToolResultAck(m.toolCallId())));
                    listener.accept(new ModelEvent.Completed(reply));
                    return reply;
                } catch (RuntimeException e) {
                    throw new Failure("PROTOCOL_ERROR");
                }
            }
            if (List.of(429, 502, 503, 504).contains(status) && attempt < retries) {
                long delay = 250L * (attempt + 1);
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
                while (System.nanoTime() < until) {
                    control.check();
                    Thread.sleep(25);
                }
                continue;
            }
            throw new Failure(status == 401 || status == 403 ? "MODEL_AUTH_ERROR"
                    : status == 429 ? "MODEL_RATE_LIMIT" : "MODEL_HTTP_" + status);
        }
        throw new Failure("MODEL_UNAVAILABLE");
    }

    private static final class StreamingBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final StreamingResponseParser parser;
        private final TaskControl control;
        private Flow.Subscription subscription;
        StreamingBody(boolean responses, Consumer<ModelEvent> listener, TaskControl control) {
            parser = new StreamingResponseParser(responses, listener);
            this.control = control;
        }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            try {
                control.check();
                for (ByteBuffer buffer : items) while (buffer.hasRemaining()) parser.accept(buffer.get());
                if (parser.terminal()) {
                    complete();
                    subscription.cancel();
                } else subscription.request(1);
            } catch (Exception e) {
                subscription.cancel();
                result.completeExceptionally(e);
            }
        }
        private void complete() {
            try { result.complete(parser.finish().toString().getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { result.completeExceptionally(e); }
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { complete(); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer buffer : items) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new Failure("MODEL_RESPONSE_TOO_LARGE"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
