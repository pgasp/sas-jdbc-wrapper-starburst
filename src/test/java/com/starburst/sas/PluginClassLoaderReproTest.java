package com.starburst.sas;

import static com.starburst.sas.PluginLoaderHarness.chainHas;
import static com.starburst.sas.PluginLoaderHarness.connect;
import static com.starburst.sas.PluginLoaderHarness.describe;
import static com.starburst.sas.PluginLoaderHarness.onThreadWithTccl;
import static com.starburst.sas.PluginLoaderHarness.stackHas;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Class loader reproduction, no SAS and no Trino: see {@link PluginLoaderHarness}.
 *
 * <p>Field symptom: on Trino worker split-runner threads the thread context class loader (TCCL) is the application
 * class loader, so {@code org.omg.CORBA.ORB.init()} (called by the SAS driver, glassfish-corba jars in the plugin
 * directory) fails in {@code ORB.create_impl} with {@code ClassNotFoundException: com.sun.corba.ee.impl.orb.ORBSingleton}.
 */
class PluginClassLoaderReproTest {

    private static final String ORB_SINGLETON = "com.sun.corba.ee.impl.orb.ORBSingleton";
    private static final String URL_CONNECT = FakeCorbaDriver.URL_PREFIX + "?at=connect";

    // ---- 1. the problem, without the wrapper's TCCL handling ----

    @Test
    void withoutFix_corbaJarsInPluginDir_tcclIsAppLoader_failsLikeTheClientTrace() throws Throwable {
        try (PluginLoaderHarness h = new PluginLoaderHarness(true)) {
            Driver bare = h.bareFakeDriver();
            Throwable t = failure(() -> onThreadWithTccl(h.appLoader(), () -> connect(bare, URL_CONNECT)));
            String d = describe(t);
            assertEquals("org.omg.CORBA.INITIALIZE", t.getClass().getName(), d);
            assertTrue(chainHas(t, "java.lang.ClassNotFoundException"), d);
            assertTrue(d.contains(ORB_SINGLETON), d);
            assertTrue(stackHas(t, "org.omg.CORBA.ORB", "create_impl"), d);
        }
    }

    @Test
    void sanityCheck_sameJarsAndSameDriver_workWhenTcclIsThePluginLoader() throws Throwable {
        try (PluginLoaderHarness h = new PluginLoaderHarness(true)) {
            Driver bare = h.bareFakeDriver();
            Connection c = onThreadWithTccl(h.plugin, () -> connect(bare, URL_CONNECT));
            assertNotNull(c);
        }
    }

    // ---- 2. with the wrapper ----

    @Test
    void withFix_connectWorksOnAThreadWhoseTcclIsTheAppLoader_andTcclIsRestored() throws Throwable {
        try (PluginLoaderHarness h = new PluginLoaderHarness(true)) {
            Driver wrapper = h.wrapperDriver();
            ClassLoader app = h.appLoader();
            ClassLoader[] seen = new ClassLoader[2];
            Connection c = onThreadWithTccl(app, () -> {
                seen[0] = Thread.currentThread().getContextClassLoader();
                Connection conn = connect(wrapper, URL_CONNECT);
                seen[1] = Thread.currentThread().getContextClassLoader();
                return conn;
            });
            assertNotNull(c);
            assertSame(app, seen[0]);
            assertSame(app, seen[1], "the previous TCCL must be restored after connect");
        }
    }

    /** The SAS driver can reach the ORB later than connect (e.g. create_any in IOM flows): every proxy layer is covered. */
    @ParameterizedTest
    @ValueSource(strings = {"connection", "statement", "resultset", "metadata"})
    void withFix_orbTouchedLaterThanConnect_isCoveredOnEveryProxyLayer(String at) throws Throwable {
        try (PluginLoaderHarness h = new PluginLoaderHarness(true)) {
            Driver wrapper = h.wrapperDriver();
            ClassLoader app = h.appLoader();
            ClassLoader[] after = new ClassLoader[1];
            onThreadWithTccl(app, () -> {
                Connection c = connect(wrapper, FakeCorbaDriver.URL_PREFIX + "?at=" + at);
                c.createStatement().executeQuery("SELECT 1").next();
                c.getMetaData().getSchemas().next();
                after[0] = Thread.currentThread().getContextClassLoader();
                return null;
            });
            assertSame(app, after[0], "the TCCL of the caller is restored after every delegated call");
        }
    }

    @Test
    void tcclIsRestoredWhenTheDelegateThrows() throws Throwable {
        try (PluginLoaderHarness h = new PluginLoaderHarness(false)) { // no CORBA anywhere visible: connect must fail
            Driver wrapper = h.wrapperDriver();
            ClassLoader app = h.appLoader();
            ClassLoader[] after = new ClassLoader[1];
            Throwable t = failure(() -> onThreadWithTccl(app, () -> {
                try {
                    return connect(wrapper, URL_CONNECT);
                } finally {
                    after[0] = Thread.currentThread().getContextClassLoader();
                }
            }));
            assertTrue(chainHas(t, "java.lang.ClassNotFoundException"), describe(t));
            assertSame(app, after[0]);
        }
    }

    // ---- 3. variants of the "put the jars in lib/" idea (observed results, documented as tests) ----

    /**
     * CORBA jars only on the application class path (copied to {@code lib/}), none next to the wrapper: the plugin
     * class loader has the platform loader as its parent and cannot see the application loader, so the SAS classes
     * (in the plugin loader) do not find {@code org.omg.CORBA.ORB} at all. Neither the TCCL nor the wrapper changes that.
     */
    @Test
    void variantLibOnly_corbaJarsOnAppClasspathOnly_pluginLoaderCannotSeeThem() throws Throwable {
        try (PluginLoaderHarness h = new PluginLoaderHarness(false)) {
            ClassLoader appWithCorba = h.appLoaderWithCorbaJars();
            Driver bare = h.bareFakeDriver();
            Throwable bareFailure = failure(() -> onThreadWithTccl(appWithCorba, () -> connect(bare, URL_CONNECT)));
            assertTrue(describe(bareFailure).contains("java.lang.ClassNotFoundException: org.omg.CORBA.ORB"), describe(bareFailure));

            Driver wrapper = h.wrapperDriver();
            Throwable wrappedFailure = failure(() -> onThreadWithTccl(appWithCorba, () -> connect(wrapper, URL_CONNECT)));
            assertTrue(describe(wrappedFailure).contains("java.lang.ClassNotFoundException: org.omg.CORBA.ORB"), describe(wrappedFailure));
        }
    }

    /**
     * CORBA jars in both places (plugin directory and application class path). Without the fix the ORB class of the
     * plugin loader instantiates the ORBSingleton found through the TCCL, which belongs to the application loader's own
     * copy of the ORB hierarchy: ClassCastException (two distinct {@code org.omg.CORBA.ORB} classes). With the TCCL set
     * to the plugin loader, the plugin copy is used consistently.
     */
    @Test
    void variantBothPlaces_classIdentitySplit_withoutFix_classCastException_withFix_ok() throws Throwable {
        try (PluginLoaderHarness h = new PluginLoaderHarness(true)) {
            ClassLoader appWithCorba = h.appLoaderWithCorbaJars();
            Driver bare = h.bareFakeDriver();
            Throwable t = failure(() -> onThreadWithTccl(appWithCorba, () -> connect(bare, URL_CONNECT)));
            assertEquals("org.omg.CORBA.INITIALIZE", t.getClass().getName(), describe(t));
            assertTrue(chainHas(t, "java.lang.ClassCastException"), describe(t));
            assertTrue(describe(t).contains(ORB_SINGLETON), describe(t));

            Driver wrapper = h.wrapperDriver();
            assertNotNull(onThreadWithTccl(appWithCorba, () -> connect(wrapper, URL_CONNECT)));
        }
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static Throwable failure(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected the call to fail");
    }
}
