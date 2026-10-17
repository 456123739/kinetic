package com.kinetic.util;

public interface Closeable extends AutoCloseable {
    @Override
    void close();
}
