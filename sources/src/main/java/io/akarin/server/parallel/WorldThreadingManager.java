package io.akarin.server.parallel;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

import io.akarin.api.internal.Akari;
import io.akarin.server.core.AkarinGlobalConfig;
import net.minecraft.server.WorldServer;

public final class WorldThreadingManager {
    private static final Map<WorldServer, WorldTickExecutor> EXECUTORS = new ConcurrentHashMap<>();
    private static final ThreadLocal<WorldTickExecutor> CURRENT = new ThreadLocal<>();

    private WorldThreadingManager() {}

    public static void bindWorld(WorldServer world) {
        if (!AkarinGlobalConfig.parallelWorldEnabled) {
            return;
        }
        EXECUTORS.computeIfAbsent(world, key -> {
            WorldTickExecutor executor = new WorldTickExecutor(key, createThreadName(key));
            executor.start();
            return executor;
        });
    }

    public static void bindWorlds(List<WorldServer> worlds) {
        for (WorldServer world : worlds) {
            bindWorld(world);
        }
    }

    public static void unbindWorld(WorldServer world) {
        if (!AkarinGlobalConfig.parallelWorldEnabled) {
            return;
        }
        WorldTickExecutor executor = EXECUTORS.remove(world);
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            executor.awaitShutdown(5000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Akari.logger.warn("Interrupted while waiting for {} to stop", executor.getOwnerThread().getName(), ex);
        }
    }

    public static void shutdownAll() {
        for (WorldTickExecutor executor : EXECUTORS.values()) {
            executor.shutdown();
        }
        for (WorldTickExecutor executor : EXECUTORS.values()) {
            try {
                executor.awaitShutdown(5000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Akari.logger.warn("Interrupted while waiting for {} to stop", executor.getOwnerThread().getName(), ex);
                return;
            }
        }
        EXECUTORS.clear();
    }

    public static boolean isWorldThread(WorldServer world) {
        if (!AkarinGlobalConfig.parallelWorldEnabled) {
            return false;
        }
        WorldTickExecutor executor = EXECUTORS.get(world);
        return executor != null && executor.isWorldThread();
    }

    public static boolean isWorldThread(Thread thread) {
        if (!AkarinGlobalConfig.parallelWorldEnabled) {
            return false;
        }
        WorldTickExecutor current = CURRENT.get();
        return current != null && current.getOwnerThread() == thread;
    }

    public static void ensureWorldThread(WorldServer world) {
        if (!AkarinGlobalConfig.parallelWorldEnabled) {
            return;
        }
        WorldTickExecutor executor = EXECUTORS.get(world);
        if (executor == null) {
            throw new IllegalStateException("No world executor bound for " + world.worldData.getName());
        }
        executor.ensureWorldThread();
    }

    public static FutureTask<Void> execute(WorldServer world, Runnable task) {
        if (!AkarinGlobalConfig.parallelWorldEnabled) {
            FutureTask<Void> future = new FutureTask<>(task, null);
            future.run();
            return future;
        }
        WorldTickExecutor executor = getExecutor(world);
        return executor.submit(task);
    }

    public static <T> T callSync(WorldServer world, Callable<T> task) {
        if (!AkarinGlobalConfig.parallelWorldEnabled) {
            try {
                return task.call();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        return getExecutor(world).callSync(world, task);
    }

    public static Collection<WorldTickExecutor> executors() {
        return new ArrayList<>(EXECUTORS.values());
    }

    public static List<ThreadInfo> dumpThreadInfos() {
        List<ThreadInfo> infos = new ArrayList<>();
        for (WorldTickExecutor executor : EXECUTORS.values()) {
            Thread thread = executor.getOwnerThread();
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId(), Integer.MAX_VALUE);
            if (info != null) {
                infos.add(info);
            }
        }
        return infos;
    }

    public static String describeExecutor(WorldTickExecutor executor) {
        long startedAt = executor.getCurrentTaskStartedAt();
        long runningFor = startedAt == -1L ? 0L : Math.max(0L, System.currentTimeMillis() - startedAt);
        return executor.getOwnerThread().getName() + " world=" + executor.getWorld().worldData.getName()
                + " task=" + executor.getCurrentTaskName()
                + " queued=" + executor.getQueuedTasks()
                + " runningForMs=" + runningFor;
    }

    private static WorldTickExecutor getExecutor(WorldServer world) {
        WorldTickExecutor executor = EXECUTORS.get(world);
        if (executor == null) {
            throw new IllegalStateException("No world executor bound for " + world.worldData.getName());
        }
        return executor;
    }

    private static String createThreadName(WorldServer world) {
        switch (world.dimension) {
            case 0:
                return "WorldThread-overworld";
            case -1:
                return "WorldThread-the_nether";
            case 1:
                return "WorldThread-the_end";
            default:
                return "WorldThread-" + world.dimension;
        }
    }

    static void setCurrentExecutor(WorldTickExecutor executor) {
        CURRENT.set(executor);
    }

    static void clearCurrentExecutor(WorldTickExecutor executor) {
        WorldTickExecutor current = CURRENT.get();
        if (current == executor) {
            CURRENT.remove();
        }
    }
}
