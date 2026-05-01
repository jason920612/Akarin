package io.akarin.server.parallel;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicLong;

import io.akarin.api.internal.Akari;
import io.akarin.server.core.AkarinGlobalConfig;
import net.minecraft.server.WorldServer;

public final class WorldTickExecutor {
    private final WorldServer world;
    private final WorldMailbox mailbox = new WorldMailbox();
    private final AtomicLong submittedTasks = new AtomicLong();
    private volatile boolean running = true;
    private volatile WorldThread ownerThread;
    private volatile String currentTaskName = "idle";
    private volatile long currentTaskStartedAt = -1L;

    WorldTickExecutor(WorldServer world, String threadName) {
        this.world = world;
        this.ownerThread = new WorldThread(this, threadName, AkarinGlobalConfig.primaryThreadPriority);
    }

    public WorldServer getWorld() {
        return world;
    }

    public Thread getOwnerThread() {
        return ownerThread;
    }

    public String getCurrentTaskName() {
        return currentTaskName;
    }

    public long getCurrentTaskStartedAt() {
        return currentTaskStartedAt;
    }

    public int getQueuedTasks() {
        return mailbox.size();
    }

    public void start() {
        ownerThread.start();
    }

    public boolean isWorldThread() {
        return Thread.currentThread() == ownerThread;
    }

    public void ensureWorldThread() {
        if (!isWorldThread()) {
            throw new IllegalStateException("World access for " + world.worldData.getName() + " from " + Thread.currentThread().getName() + ", expected " + ownerThread.getName());
        }
    }

    public void execute(WorldServer targetWorld, Runnable task) {
        if (targetWorld != world) {
            throw new IllegalArgumentException("Executor/world mismatch for " + targetWorld.worldData.getName());
        }
        submit(task);
    }

    public <T> T callSync(WorldServer targetWorld, Callable<T> task) {
        if (targetWorld != world) {
            throw new IllegalArgumentException("Executor/world mismatch for " + targetWorld.worldData.getName());
        }
        if (isWorldThread()) {
            try {
                return task.call();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        FutureTask<T> future = submit(task);
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for world task " + ownerThread.getName(), ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("Exception running world task on " + ownerThread.getName(), cause);
        }
    }

    public FutureTask<Void> submit(Runnable task) {
        FutureTask<Void> future = new FutureTask<>(task, null);
        enqueue(future);
        return future;
    }

    public <T> FutureTask<T> submit(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        enqueue(future);
        return future;
    }

    private void enqueue(FutureTask<?> future) {
        if (!running) {
            RejectedExecutionException rejected = new RejectedExecutionException("Rejected task for " + ownerThread.getName() + " because executor is shutting down");
            Akari.logger.error(rejected.getMessage(), rejected);
            throw rejected;
        }

        submittedTasks.incrementAndGet();
        mailbox.offer(future);
    }

    void runLoop(WorldThread thread) {
        ownerThread = thread;
        WorldThreadingManager.setCurrentExecutor(this);
        if (AkarinGlobalConfig.parallelWorldDebugLog) {
            Akari.logger.info("Started world thread {} for {}", thread.getName(), world.worldData.getName());
        }

        try {
            while (running || mailbox.size() > 0) {
                RunnableFuture<?> task;
                try {
                    task = mailbox.take();
                } catch (InterruptedException ex) {
                    if (!running) {
                        break;
                    }
                    continue;
                }

                runTask(task);
            }
        } finally {
            WorldThreadingManager.clearCurrentExecutor(this);
            if (AkarinGlobalConfig.parallelWorldDebugLog) {
                Akari.logger.info("Stopped world thread {} for {}", thread.getName(), world.worldData.getName());
            }
        }
    }

    private void runTask(RunnableFuture<?> task) {
        currentTaskStartedAt = System.currentTimeMillis();
        currentTaskName = "task-" + submittedTasks.get();
        try {
            task.run();
        } catch (Throwable throwable) {
            Akari.logger.error("Unhandled world task exception in {}", ownerThread.getName(), throwable);
            throw throwable;
        } finally {
            currentTaskName = "idle";
            currentTaskStartedAt = -1L;
        }
    }

    public void shutdown() {
        running = false;
        ownerThread.interrupt();
    }

    public void awaitShutdown(long joinMillis) throws InterruptedException {
        ownerThread.join(joinMillis);
    }
}
