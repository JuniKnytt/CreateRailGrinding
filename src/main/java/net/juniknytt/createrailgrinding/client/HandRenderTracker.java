package net.juniknytt.createrailgrinding.client;

public final class HandRenderTracker {
    private static int depth;

    private HandRenderTracker() {}

    public static void push() { depth++; }
    public static void pop() { if (depth > 0) depth--; }
    public static boolean isRendering() { return depth > 0; }
}
