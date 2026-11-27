package com.tianmu.agent.evaluation.admission;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/** Optional binding at the last HTTP boundary of an evaluation-owned product client. */
public interface ProviderHttpTransport {
    default void beforeAdmission() {}

    <T> CompletableFuture<HttpResponse<T>> send(HttpRequest request, HttpResponse.BodyHandler<T> handler,
            byte[] payload, EvaluationFeeGate.Reservation reservation);

    static ProviderHttpTransport using(HttpClient client) {
        return new ProviderHttpTransport() {
            @Override
            public <T> CompletableFuture<HttpResponse<T>> send(HttpRequest request,
                    HttpResponse.BodyHandler<T> handler, byte[] payload, EvaluationFeeGate.Reservation reservation) {
                return client.sendAsync(request, handler);
            }
        };
    }

    static HttpRequest.BodyPublisher oneShot(byte[] payload) {
        var delegate = HttpRequest.BodyPublishers.ofByteArray(payload);
        var sent = new java.util.concurrent.atomic.AtomicBoolean();
        return new HttpRequest.BodyPublisher() {
            public long contentLength() { return delegate.contentLength(); }

            public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                if (!sent.compareAndSet(false, true)) {
                    subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                        public void request(long count) {}
                        public void cancel() {}
                    });
                    subscriber.onError(new IllegalStateException("Provider request cannot be replayed"));
                    return;
                }
                delegate.subscribe(subscriber);
            }
        };
    }
}
