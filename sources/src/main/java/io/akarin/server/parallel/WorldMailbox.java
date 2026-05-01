package io.akarin.server.parallel;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RunnableFuture;

final class WorldMailbox {
    private final BlockingQueue<RunnableFuture<?>> queue = new LinkedBlockingQueue<>();

    void offer(RunnableFuture<?> task) {
        queue.offer(task);
    }

    RunnableFuture<?> take() throws InterruptedException {
        return queue.take();
    }

    int size() {
        return queue.size();
    }
}
