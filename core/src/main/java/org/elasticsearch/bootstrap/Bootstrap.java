/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.bootstrap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.PidFile;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.inject.CreationException;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.IfConfig;
import org.elasticsearch.common.settings.KeyStoreWrapper;
import org.elasticsearch.common.settings.SecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.env.Environment;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.monitor.os.OsProbe;
import org.elasticsearch.monitor.process.ProcessProbe;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeValidationException;
import org.elasticsearch.node.InternalSettingsPreparer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * Internal startup code.
 */
final class Bootstrap {

    private static volatile Bootstrap INSTANCE;
    private volatile Node node;
    private final CountDownLatch keepAliveLatch = new CountDownLatch(1);
    private final Thread keepAliveThread;
    private final Spawner spawner = new Spawner();

    /** creates a new instance */
    Bootstrap() {
        keepAliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    keepAliveLatch.await();
                } catch (InterruptedException e) {
                    // bail out
                }
            }
        }, "elasticsearch[keepAlive/" + Version.CURRENT + "]");
        keepAliveThread.setDaemon(false);
        // keep this thread alive (non daemon thread) until we shutdown
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                keepAliveLatch.countDown();
            }
        });
    }

    /** initialize native resources */
    public static void initializeNatives(Path tmpFile, boolean mlockAll, boolean systemCallFilter, boolean ctrlHandler) {
        final Logger logger = Loggers.getLogger(Bootstrap.class);

        // check if the user is running as root, and bail
        if (Natives.definitelyRunningAsRoot()) {
            throw new RuntimeException("can not run elasticsearch as root");
        }

        // enable system call filter
        if (systemCallFilter) {
            Natives.tryInstallSystemCallFilter(tmpFile);
        }

        // mlockall if requested
        if (mlockAll) {
            if (Constants.WINDOWS) {
               Natives.tryVirtualLock();
            } else {
               Natives.tryMlockall();
            }
        }

        // listener for windows close event
        if (ctrlHandler) {
            Natives.addConsoleCtrlHandler(new ConsoleCtrlHandler() {
                @Override
                public boolean handle(int code) {
                    if (CTRL_CLOSE_EVENT == code) {
                        logger.info("running graceful exit on windows");
                        try {
                            Bootstrap.stop();
                        } catch (IOException e) {
                            throw new ElasticsearchException("failed to stop node", e);
                        }
                        return true;
                    }
                    return false;
                }
            });
        }

        // force remainder of JNA to be loaded (if available).
        try {
            JNAKernel32Library.getInstance();
        } catch (Exception ignored) {
            // we've already logged this.
        }

        Natives.trySetMaxNumberOfThreads();
        Natives.trySetMaxSizeVirtualMemory();
        Natives.trySetMaxFileSize();

        // init lucene random seed. it will use /dev/urandom where available:
        StringHelper.randomId();
    }

    static void initializeProbes() {
        // Force probes to be loaded
        ProcessProbe.getInstance();
        OsProbe.getInstance();
        JvmInfo.jvmInfo();
    }

    private void setup(boolean addShutdownHook, Environment environment) throws BootstrapException {
        Settings settings = environment.settings();

        try {
            /**
             * $$$ 每一个插件都会生成一个新的进程。记录在spawner的List<Process> processes下
             */
            spawner.spawnNativePluginControllers(environment);
        } catch (IOException e) {
            throw new BootstrapException(e);
        }

        /**
         * $$$ 在elasticsearch.yml 中多个参数配置：
         * bootstrap.memory_lock：设置为true来锁住内存。因为当jvm开始swapping时es的效率会降低，所以要保证它不swap，
         * 可以把ES_MIN_MEM和ES_MAX_MEM两个环境变量设置成同一个值，并且保证机器有足够的内存分配给es。
         * bootstrap.system_call_filter：系统调用过滤器，建议禁用该项检查，因为很多检查项需要Linux 3.5以上的内核。
         * bootstrap.ctrlhandler: 这个参数似乎特别少用，google和baidu中相关文档极少。不大清楚含义
         */
        initializeNatives(
                environment.tmpFile(),
                BootstrapSettings.MEMORY_LOCK_SETTING.get(settings),
                BootstrapSettings.SYSTEM_CALL_FILTER_SETTING.get(settings),
                BootstrapSettings.CTRLHANDLER_SETTING.get(settings));

        // initialize probes before the security manager is installed
        initializeProbes();

        /**
         * $$$ 添加钩子，退出时关闭node进程和插件进程
         */
        if (addShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        IOUtils.close(node, spawner);
                        LoggerContext context = (LoggerContext) LogManager.getContext(false);
                        Configurator.shutdown(context);
                    } catch (IOException ex) {
                        throw new ElasticsearchException("failed to stop node", ex);
                    }
                }
            });
        }

//        try {
//            // look for jar hell
//            JarHell.checkJarHell();
//        } catch (IOException | URISyntaxException e) {
//            throw new BootstrapException(e);
//        }

        // Log ifconfig output before SecurityManager is installed
        IfConfig.logIfNecessary();

        // install SM after natives, shutdown hooks, etc.
        /**
         * $$$ 与Security相关目前不清晰
         */
        try {
            Security.configure(environment, BootstrapSettings.SECURITY_FILTER_BAD_DEFAULTS_SETTING.get(settings));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BootstrapException(e);
        }

        node = new Node(environment) {
            @Override
            protected void validateNodeBeforeAcceptingRequests(
                final BootstrapContext context,
                final BoundTransportAddress boundTransportAddress, List<BootstrapCheck> checks) throws NodeValidationException {
                BootstrapChecks.check(context, boundTransportAddress, checks);
            }
        };
    }

    static SecureSettings loadSecureSettings(Environment initialEnv) throws BootstrapException {
        final KeyStoreWrapper keystore;
        try {
            keystore = KeyStoreWrapper.load(initialEnv.configFile());
        } catch (IOException e) {
            throw new BootstrapException(e);
        }
        if (keystore == null) {
            return null; // no keystore
        }

        try {
            keystore.decrypt(new char[0] /* TODO: read password from stdin */);
            KeyStoreWrapper.upgrade(keystore, initialEnv.configFile());
        } catch (Exception e) {
            throw new BootstrapException(e);
        }
        return keystore;
    }

    private static Environment createEnvironment(
            final boolean foreground,
            final Path pidFile,
            final SecureSettings secureSettings,
            final Settings initialSettings,
            final Path configPath) {
        Terminal terminal = foreground ? Terminal.DEFAULT : null;
        Settings.Builder builder = Settings.builder();
        if (pidFile != null) {
            builder.put(Environment.PIDFILE_SETTING.getKey(), pidFile);
        }
        builder.put(initialSettings);
        if (secureSettings != null) {
            builder.setSecureSettings(secureSettings);
        }
        return InternalSettingsPreparer.prepareEnvironment(builder.build(), terminal, Collections.emptyMap(), configPath);
    }

    private void start() throws NodeValidationException {
        node.start();
        keepAliveThread.start();
    }

    static void stop() throws IOException {
        try {
            IOUtils.close(INSTANCE.node, INSTANCE.spawner);
        } finally {
            INSTANCE.keepAliveLatch.countDown();
        }
    }

    /**
     * This method is invoked by {@link Elasticsearch#main(String[])} to startup elasticsearch.
     */
    static void init(
            final boolean foreground,
            final Path pidFile,
            final boolean quiet,
            final Environment initialEnv) throws BootstrapException, NodeValidationException, UserException {
        // force the class initializer for BootstrapInfo to run before
        // the security manager is installed
        /**
         * $$$ init() 没有进行任何操作。由于BootstrapInfo为final class，个人认为此处仅为初始化。
         * Bootstrap.init(true, null, false, initialEnv);
         */
        BootstrapInfo.init();

        /**
         * $$$ 该方法围绕3个语句来进行：初始化，配置，启动
         * INSTANCE = new Bootstrap();
         * INSTANCE.setup(true, environment);
         * INSTANCE.start();
         */
        INSTANCE = new Bootstrap();

        final SecureSettings keystore = loadSecureSettings(initialEnv);
        final Environment environment = createEnvironment(foreground, pidFile, keystore, initialEnv.settings(), initialEnv.configFile());
        try {
            LogConfigurator.configure(environment);
        } catch (IOException e) {
            throw new BootstrapException(e);
        }
        if (environment.pidFile() != null) {
            try {
                PidFile.create(environment.pidFile(), true);
            } catch (IOException e) {
                throw new BootstrapException(e);
            }
        }

        final boolean closeStandardStreams = (foreground == false) || quiet;
        try {
            if (closeStandardStreams) {
                final Logger rootLogger = ESLoggerFactory.getRootLogger();
                final Appender maybeConsoleAppender = Loggers.findAppender(rootLogger, ConsoleAppender.class);
                if (maybeConsoleAppender != null) {
                    Loggers.removeAppender(rootLogger, maybeConsoleAppender);
                }
                closeSystOut();
            }

            // fail if somebody replaced the lucene jars
            checkLucene();

            // install the default uncaught exception handler; must be done before security is
            // initialized as we do not want to grant the runtime permission
            // setDefaultUncaughtExceptionHandler
            Thread.setDefaultUncaughtExceptionHandler(
                new ElasticsearchUncaughtExceptionHandler(() -> Node.NODE_NAME_SETTING.get(environment.settings())));

            INSTANCE.setup(true, environment);

            try {
                // any secure settings must be read during node construction
                IOUtils.close(keystore);
            } catch (IOException e) {
                throw new BootstrapException(e);
            }

            INSTANCE.start();

            if (closeStandardStreams) {
                closeSysError();
            }
        } catch (NodeValidationException | RuntimeException e) {
            // disable console logging, so user does not see the exception twice (jvm will show it already)
            final Logger rootLogger = ESLoggerFactory.getRootLogger();
            final Appender maybeConsoleAppender = Loggers.findAppender(rootLogger, ConsoleAppender.class);
            if (foreground && maybeConsoleAppender != null) {
                Loggers.removeAppender(rootLogger, maybeConsoleAppender);
            }
            Logger logger = Loggers.getLogger(Bootstrap.class);
            if (INSTANCE.node != null) {
                logger = Loggers.getLogger(Bootstrap.class, Node.NODE_NAME_SETTING.get(INSTANCE.node.settings()));
            }
            // HACK, it sucks to do this, but we will run users out of disk space otherwise
            if (e instanceof CreationException) {
                // guice: log the shortened exc to the log file
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                PrintStream ps = null;
                try {
                    ps = new PrintStream(os, false, "UTF-8");
                } catch (UnsupportedEncodingException uee) {
                    assert false;
                    e.addSuppressed(uee);
                }
                new StartupException(e).printStackTrace(ps);
                ps.flush();
                try {
                    logger.error("Guice Exception: {}", os.toString("UTF-8"));
                } catch (UnsupportedEncodingException uee) {
                    assert false;
                    e.addSuppressed(uee);
                }
            } else if (e instanceof NodeValidationException) {
                logger.error("node validation exception\n{}", e.getMessage());
            } else {
                // full exception
                logger.error("Exception", e);
            }
            // re-enable it if appropriate, so they can see any logging during the shutdown process
            if (foreground && maybeConsoleAppender != null) {
                Loggers.addAppender(rootLogger, maybeConsoleAppender);
            }

            throw e;
        }
    }

    @SuppressForbidden(reason = "System#out")
    private static void closeSystOut() {
        System.out.close();
    }

    @SuppressForbidden(reason = "System#err")
    private static void closeSysError() {
        System.err.close();
    }

    private static void checkLucene() {
        if (Version.CURRENT.luceneVersion.equals(org.apache.lucene.util.Version.LATEST) == false) {
            throw new AssertionError("Lucene version mismatch this version of Elasticsearch requires lucene version ["
                + Version.CURRENT.luceneVersion + "]  but the current lucene version is [" + org.apache.lucene.util.Version.LATEST + "]");
        }
    }

}
