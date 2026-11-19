package com.mtm.citybuilder.platform;

import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * Loader-specific services. Each loader module provides exactly one implementation, registered under
 * META-INF/services.
 */
public interface Platform {
    String loaderName();

    Path configDir();

    boolean isDevelopmentEnvironment();

    boolean isModLoaded(String modId);

    static Platform get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final Platform INSTANCE = ServiceLoader.load(Platform.class, Platform.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Platform implementation found"));

        private Holder() {
        }
    }
}
