package de.ernaehrung.nano;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.*;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongConsumer;

/** Direct official SDK, no reflection, mock model, HTTP fallback or booking.
 * Only Java platform types cross this ABI. Construction/check/download/inference
 * are separate explicit operations; a configured stage never proves a version. */
public final class NanoRuntime implements AutoCloseable {
    public static final int UNAVAILABLE = 0, DOWNLOADABLE = 1, DOWNLOADING = 2, AVAILABLE = 3;
    private final GenerativeModelFutures client;
    private final AtomicBoolean closed = new AtomicBoolean();

    public static final class Failure extends RuntimeException {
        public final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    public static final class Reply {
        public final String text;
        public final boolean complete;
        public final Integer finishCode;
        Reply(String text, Integer finishCode) {
            this.text = text; this.finishCode = finishCode;
            this.complete = finishCode != null && finishCode == Candidate.FinishReason.STOP;
        }
        @Override public String toString() { return "NanoReply(content redacted)"; }
    }
    public NanoRuntime(boolean previewFast) {
        try {
            ModelConfig.Builder model = new ModelConfig.Builder();
            model.setReleaseStage(previewFast ? ModelReleaseStage.PREVIEW : ModelReleaseStage.STABLE);
            model.setPreference(previewFast ? ModelPreference.FAST : ModelPreference.FULL);
            GenerationConfig.Builder config = new GenerationConfig.Builder();
            config.setModelConfig(model.build());
            client = GenerativeModelFutures.from(Generation.INSTANCE.getClient(config.build()));
        } catch (RuntimeException failure) { throw safe(failure); }
    }
    private static Failure safe(Throwable failure) {
        while (failure instanceof ExecutionException || failure instanceof CompletionException) {
            if (failure.getCause() == null) break;
            failure = failure.getCause();
        }
        if (failure instanceof Failure) return (Failure) failure;
        if (failure instanceof GenAiException) return new Failure("SDK_" + ((GenAiException) failure).getErrorCode());
        return new Failure("SDK_UNAVAILABLE"); // Never expose SDK prompt/error text.
    }
    private void open() { if (closed.get()) throw new Failure("CLOSED"); }
    private <T,R> CompletableFuture<R> map(ListenableFuture<T> pending, Function<T,R> transform) {
        CompletableFuture<R> result = new CompletableFuture<>();
        result.whenComplete((value, error) -> { if (result.isCancelled()) pending.cancel(true); });
        pending.addListener(() -> {
            if (closed.get()) { result.cancel(false); return; }
            try { result.complete(transform.apply(pending.get())); }
            catch (CancellationException cancelled) { result.cancel(false); }
            catch (Exception failure) { result.completeExceptionally(safe(failure)); }
        }, Runnable::run);
        return result;
    }
    public CompletableFuture<Integer> checkStatus() { open(); return map(client.checkStatus(), value -> value); }
    public CompletableFuture<String> modelName() { open(); return map(client.getBaseModelName(), value -> value); }
    public CompletableFuture<Integer> tokenLimit() { open(); return map(client.getTokenLimit(), value -> value); }
    private GenerateContentRequest request(String prompt, int outputTokens) {
        open();
        if (prompt == null || prompt.trim().isEmpty() || prompt.length() > 65536 || outputTokens < 1 || outputTokens > 2048)
            throw new Failure("REQUEST_LIMIT");
        GenerateContentRequest.Builder request = new GenerateContentRequest.Builder(new TextPart(prompt));
        request.setCandidateCount(1); request.setMaxOutputTokens(outputTokens);
        return request.build();
    }
    public CompletableFuture<Integer> countTokens(String prompt, int outputTokens) {
        return map(client.countTokens(request(prompt, outputTokens)), value -> value.getTotalTokens());
    }
    public CompletableFuture<Reply> generate(String prompt, int outputTokens) {
        return map(client.generateContent(request(prompt, outputTokens)), response -> {
            if (response.getCandidates().size() != 1) throw new Failure("AMBIGUOUS_REPLY");
            Candidate candidate = response.getCandidates().get(0);
            String text = candidate.getText();
            if (text == null || text.trim().isEmpty() || text.length() > 262144) throw new Failure("REPLY_LIMIT");
            return new Reply(text, candidate.getFinishReason());
        });
    }
    public CompletableFuture<Void> download(LongConsumer progress) {
        open();
        return map(client.download(new DownloadCallback() {
            @Override public void onDownloadProgress(long bytes) { if (!closed.get()) progress.accept(bytes); }
        }), value -> null);
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) client.getGenerativeModel().close();
    }
}
