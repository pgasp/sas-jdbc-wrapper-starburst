package com.starburst.sas;

import java.sql.SQLException;

/**
 * Thread context class loader (TCCL) handling.
 *
 * <p>The SAS IOM driver initializes a CORBA ORB ({@code org.omg.CORBA.ORB.init}), and the glassfish-corba ORB
 * looks its implementation classes up through the TCCL. In Trino/SEP the connector plugin is loaded by a
 * dedicated class loader (the plugin class loader, which has no access to the application class loader), but
 * split-runner threads of the workers run with the application class loader as TCCL: the glassfish-corba jars
 * sitting in the plugin directory are then invisible to the ORB ({@code ClassNotFoundException:
 * com.sun.corba.ee.impl.orb.ORBSingleton}). Every call delegated to the real driver therefore runs with the TCCL
 * set to the class loader that defined this wrapper (the plugin class loader when deployed as a plugin), and the
 * previous TCCL is restored in a {@code finally} block.
 *
 * <p>Overhead: one {@code getContextClassLoader()} and one reference comparison per call; the TCCL is only
 * written when it differs from the wrapper's own loader.
 */
final class ContextClassLoaders {

    /** Class loader that defined the wrapper. */
    static final ClassLoader OWN = ContextClassLoaders.class.getClassLoader();

    @FunctionalInterface
    interface SqlCall<T> {
        T call() throws SQLException;
    }

    private ContextClassLoaders() {
    }

    /** Runs {@code call} with the TCCL set to the wrapper's own class loader; the previous TCCL is always restored. */
    static <T> T withOwnLoader(SqlCall<T> call) throws SQLException {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        if (previous == OWN) {
            return call.call();
        }
        thread.setContextClassLoader(OWN);
        try {
            return call.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Short, non-sensitive description of a class loader for trace lines. */
    static String describe(ClassLoader loader) {
        if (loader == null) {
            return "null(bootstrap)";
        }
        String name = loader.getName();
        return loader.getClass().getName() + (name == null ? "" : "[" + name + "]")
                + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
}
