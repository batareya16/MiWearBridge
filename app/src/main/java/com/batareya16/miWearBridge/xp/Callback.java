package com.batareya16.miWearBridge.xp;

import org.jetbrains.annotations.Nullable;

/**
 * @author user
 */
public interface Callback<T> {
    /**
     * Error message callback
     *
     * @param msg error description
     */
    void onError(String msg, @Nullable Throwable e);

    /**
     * Success callback
     *
     * @param obj parameter
     */
    void onSuccess(T obj);
}