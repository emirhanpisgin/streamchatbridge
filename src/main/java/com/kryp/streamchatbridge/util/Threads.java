package com.kryp.streamchatbridge.util;

public final class Threads {

    private Threads() {
    }

    public static void start(String name, Runnable task) {
        Thread thread = new Thread(task, name);

        thread.setDaemon(true);

        thread.start();
    }
}
