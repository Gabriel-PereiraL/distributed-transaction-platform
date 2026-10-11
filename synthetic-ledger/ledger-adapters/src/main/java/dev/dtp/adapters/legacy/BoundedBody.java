package dev.dtp.adapters.legacy;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels the network subscription before retaining an oversized body. */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int maximum;
    private int received;
    private Flow.Subscription subscription;
    private boolean failed;
    BoundedBody(int maximum) { this.maximum = maximum; }
    @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
    @Override public void onNext(List<ByteBuffer> buffers) {
        if (failed) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > maximum - received) {
                failed = true; subscription.cancel(); delegate.onError(new IOException("Legacy body too large.")); return;
            }
            received += buffer.remaining();
        }
        delegate.onNext(buffers);
    }
    @Override public void onError(Throwable failure) { if (!failed) { failed = true; delegate.onError(failure); } }
    @Override public void onComplete() { if (!failed) delegate.onComplete(); }
}
