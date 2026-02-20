package net.rain.api.coremod;

public interface ICoreClassTransformer {
    byte[] transform(String className, byte[] basicClass);
}