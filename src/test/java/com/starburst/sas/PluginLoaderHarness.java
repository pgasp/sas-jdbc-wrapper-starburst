package com.starburst.sas;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Reproduces, in one JVM and without SAS, how Trino/SEP loads a connector plugin and which thread context class loader
 * (TCCL) the split-runner threads have.
 *
 * <ul>
 *   <li>{@code plugin}: a {@link URLClassLoader} whose parent is the <em>platform</em> class loader, like Trino's
 *       {@code PluginClassLoader} ("plugins should not have access to the system (application) class loader"). It
 *       holds its own copy of the wrapper classes and of the fake driver, and optionally the glassfish-corba jars.</li>
 *   <li>the TCCL of the worker thread is a different loader ({@link #appLoader}), the stand-in for Trino's
 *       application class loader: it cannot see the wrapper, the fake driver nor (by default) any CORBA class.</li>
 * </ul>
 * The wrapper classes come from {@code target/classes}, or from the directory/jar given by the system property
 * {@code sas.test.wrapperClasses} (used once to replay the same scenario against the 0.2.0 classes).
 */
final class PluginLoaderHarness implements AutoCloseable {

    static final String DRIVER_CLASS = "com.starburst.sas.SasJdbcWrapperDriver";

    final URLClassLoader plugin;
    private final String oldDelegate;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    /** @param corbaInPlugin glassfish-corba jars next to the wrapper in the plugin directory. */
    PluginLoaderHarness(boolean corbaInPlugin) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(wrapperClasses());
        urls.add(codeSource(FakeCorbaDriver.class));
        if (corbaInPlugin) {
            urls.addAll(corbaJars());
        }
        plugin = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        toClose.add(plugin);
        oldDelegate = System.getProperty("sas.wrapper.delegateClass");
        System.setProperty("sas.wrapper.delegateClass", FakeCorbaDriver.class.getName());
    }

    /** The wrapper driver, loaded (and statically initialized) by the plugin class loader. */
    Driver wrapperDriver() throws Exception {
        return (Driver) Class.forName(DRIVER_CLASS, true, plugin).getDeclaredConstructor().newInstance();
    }

    /** The fake delegate alone, loaded by the plugin class loader (no wrapper in between). */
    Driver bareFakeDriver() throws Exception {
        return (Driver) Class.forName(FakeCorbaDriver.class.getName(), true, plugin).getDeclaredConstructor().newInstance();
    }

    /**
     * Stand-in for Trino's application class loader (the JVM's built-in "app" loader, the one named in the client's
     * stack trace). It has no CORBA class: the test classpath does not contain the glassfish-corba jars (they are only
     * copied to a directory), and the plugin loader's own classes are invisible to it.
     */
    ClassLoader appLoader() {
        return ClassLoader.getSystemClassLoader();
    }

    /** An "application class loader" with the CORBA jars appended to it (the "copy the jars into lib/" idea). */
    ClassLoader appLoaderWithCorbaJars() throws Exception {
        URLClassLoader l = new URLClassLoader(corbaJars().toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        toClose.add(l);
        return l;
    }

    /** Runs {@code body} on a new thread whose TCCL is {@code tccl}; rethrows what the body throws. */
    static <T> T onThreadWithTccl(ClassLoader tccl, Callable<T> body) throws Throwable {
        FutureTask<T> task = new FutureTask<>(body);
        Thread t = new Thread(task, "split-runner-like");
        t.setContextClassLoader(tccl);
        t.start();
        try {
            return task.get();
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    static Connection connect(Driver driver, String url) throws Exception {
        return driver.connect(url, new Properties());
    }

    /** All throwables of the cause chain, outermost first. */
    static List<Throwable> chain(Throwable t) {
        List<Throwable> out = new ArrayList<>();
        for (Throwable c = t; c != null && !out.contains(c); c = c.getCause()) {
            out.add(c);
        }
        return out;
    }

    static boolean chainHas(Throwable t, String exceptionClassName) {
        return chain(t).stream().anyMatch(c -> c.getClass().getName().equals(exceptionClassName));
    }

    static boolean stackHas(Throwable t, String className, String methodName) {
        return chain(t).stream().flatMap(c -> java.util.Arrays.stream(c.getStackTrace()))
                .anyMatch(f -> f.getClassName().equals(className) && f.getMethodName().equals(methodName));
    }

    /** One line per cause: class: message, for the report. */
    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c : chain(t)) {
            sb.append(sb.length() == 0 ? "" : " <- caused by ").append(c.getClass().getName()).append(": ").append(c.getMessage());
        }
        return sb.toString();
    }

    private static URL wrapperClasses() throws MalformedURLException, URISyntaxException {
        String override = System.getProperty("sas.test.wrapperClasses");
        if (override != null && !override.isEmpty()) {
            return new File(override).toURI().toURL();
        }
        return codeSource(SasJdbcWrapperDriver.class);
    }

    private static URL codeSource(Class<?> c) {
        return c.getProtectionDomain().getCodeSource().getLocation();
    }

    private static List<URL> corbaJars() throws IOException {
        String dir = System.getProperty("sas.test.corbaLibs", "target/corba-libs");
        List<URL> jars = new ArrayList<>();
        Path libs = Paths.get(dir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libs, "*.jar")) {
            for (Path p : ds) {
                jars.add(p.toUri().toURL());
            }
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("No CORBA jar in " + libs.toAbsolutePath() + " (run through Maven: they are copied by the build)");
        }
        return jars;
    }

    @Override
    public void close() throws Exception {
        if (oldDelegate == null) {
            System.clearProperty("sas.wrapper.delegateClass");
        } else {
            System.setProperty("sas.wrapper.delegateClass", oldDelegate);
        }
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }
}
