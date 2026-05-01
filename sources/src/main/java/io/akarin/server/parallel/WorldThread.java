package io.akarin.server.parallel;

final class WorldThread extends Thread {
    private final WorldTickExecutor executor;

    WorldThread(WorldTickExecutor executor, String name, int priority) {
        super(name);
        this.executor = executor;
        setDaemon(false);
        setPriority(priority);
    }

    @Override
    public void run() {
        executor.runLoop(this);
    }
}
