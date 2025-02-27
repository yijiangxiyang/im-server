/*
 * Copyright (c) 2012-2017 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.moquette.server;

import cn.wildfirechat.proto.ProtoConstants;
import cn.wildfirechat.push.PushServer;
import cn.wildfirechat.server.ThreadPoolExecutorWrapper;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.FileSystemXmlConfig;
import com.hazelcast.core.*;
import com.xiaoleilu.loServer.LoServer;
import com.xiaoleilu.loServer.ServerSetting;
import com.xiaoleilu.loServer.action.Action;
import com.xiaoleilu.loServer.action.admin.AdminAction;
import io.moquette.BrokerConstants;
import io.moquette.persistence.*;
import io.moquette.connections.IConnectionsManager;
import io.moquette.interception.*;
import io.moquette.server.config.*;
import io.moquette.server.netty.NettyAcceptor;
import io.moquette.spi.IStore;
import io.moquette.spi.impl.ProtocolProcessor;
import io.moquette.spi.impl.ProtocolProcessorBootstrapper;
import io.moquette.spi.impl.security.AES;
import io.moquette.spi.security.IAuthenticator;
import io.moquette.spi.security.IAuthorizator;
import io.moquette.spi.security.ISslContextCreator;
import io.moquette.spi.security.Tokenor;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.StringUtil;
import win.liyufan.im.DBUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import win.liyufan.im.MessageShardingUtil;
import win.liyufan.im.Utility;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;

import static io.moquette.BrokerConstants.*;
import static io.moquette.logging.LoggingUtils.getInterceptorIds;

/**
 * Launch a configured version of the server.
 */
public class Server {
    private final static String BANNER =
        "            _  _      _   __  _                    _             _   \n" +
        " __      __(_)| |  __| | / _|(_) _ __  ___    ___ | |__    __ _ | |_ \n" +
        " \\ \\ /\\ / /| || | / _` || |_ | || '__|/ _ \\  / __|| '_ \\  / _` || __|\n" +
        "  \\ V  V / | || || (_| ||  _|| || |  |  __/ | (__ | | | || (_| || |_ \n" +
        "   \\_/\\_/  |_||_| \\__,_||_|  |_||_|   \\___|  \\___||_| |_| \\__,_| \\__|\n";


    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    private static Server instance;

    public static Server getServer() {
        return instance;
    }

    private ServerAcceptor m_acceptor;
    private long startTime;

    private boolean m_shutdowning = false;

    public volatile boolean m_initialized;

    private ProtocolProcessor m_processor;

    private HazelcastInstance hazelcastInstance;

    private ProtocolProcessorBootstrapper m_processorBootstrapper;

    private ThreadPoolExecutorWrapper dbScheduler;
    private ThreadPoolExecutorWrapper imBusinessScheduler;
    private ThreadPoolExecutorWrapper callbackScheduler;

    private IConfig mConfig;

    private IStore m_store;
    static {
        System.out.println(BANNER);
    }

    public Server() {
        startTime = System.currentTimeMillis();
    }

    public static void start(String[] args) throws IOException {
        instance = new Server();
        final IConfig config = defaultConfig();

        System.setProperty("hazelcast.logging.type", "none" );
        instance.mConfig = config;
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);

        instance.startServer(config);

        int httpLocalPort = Integer.parseInt(config.getProperty(BrokerConstants.HTTP_LOCAL_PORT));
        int httpAdminPort = Integer.parseInt(config.getProperty(BrokerConstants.HTTP_ADMIN_PORT));

        AdminAction.setSecretKey(config.getProperty(HTTP_SERVER_SECRET_KEY));
        AdminAction.setNoCheckTime(config.getProperty(HTTP_SERVER_API_NO_CHECK_TIME));

        final LoServer httpServer = new LoServer(httpLocalPort, httpAdminPort, instance.m_processor.getMessagesStore(), instance.m_store.sessionsStore());
        try {
            httpServer.start();
        } catch (InterruptedException e) {
            e.printStackTrace();
            Utility.printExecption(LOG, e);
        }

        final PushServer pushServer = PushServer.getServer();
        pushServer.init(config, instance.getStore().sessionsStore());

        //Bind  a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(instance::stopServer));
        Runtime.getRuntime().addShutdownHook(new Thread(httpServer::shutdown));

        System.out.println("Wildfire IM server start success...");
    }

    /**
     * Starts Moquette bringing the configuration from the file located at m_config/wildfirechat.conf
     *
     * @throws IOException
     *             in case of any IO error.
     */
    public void startServer() throws IOException {
        final IConfig config = defaultConfig();
        startServer(config);
    }

    public static IConfig defaultConfig() {
        File defaultConfigurationFile = defaultConfigFile();
        LOG.info("Starting Moquette server. Configuration file path={}", defaultConfigurationFile.getAbsolutePath());
        IResourceLoader filesystemLoader = new FileResourceLoader(defaultConfigurationFile);
        return new ResourceLoaderConfig(filesystemLoader);
    }

    private static File defaultConfigFile() {
        String configPath = System.getProperty("wildfirechat.path", null);
        return new File(configPath, IConfig.DEFAULT_CONFIG);
    }


    /**
     * Starts Moquette bringing the configuration from the given file
     *
     * @param configFile
     *            text file that contains the configuration.
     * @throws IOException
     *             in case of any IO Error.
     */
    public void startServer(File configFile) throws IOException {
        LOG.info("Starting Moquette server. Configuration file path={}", configFile.getAbsolutePath());
        IResourceLoader filesystemLoader = new FileResourceLoader(configFile);
        final IConfig config = new ResourceLoaderConfig(filesystemLoader);
        startServer(config);
    }

    /**
     * Starts the server with the given properties.
     *
     *
     * @param configProps
     *            the properties map to use as configuration.
     * @throws IOException
     *             in case of any IO Error.
     */
    public void startServer(Properties configProps) throws IOException {
        LOG.info("Starting Moquette server using properties object");
        final IConfig config = new MemoryConfig(configProps);
        startServer(config);
    }

    /**
     * Starts Moquette bringing the configuration files from the given Config implementation.
     *
     * @param config
     *            the configuration to use to start the broker.
     * @throws IOException
     *             in case of any IO Error.
     */
    public void startServer(IConfig config) throws IOException {
        LOG.info("Starting Moquette server using IConfig instance...");
        startServer(config, null);
    }

    /**
     * Starts Moquette with config provided by an implementation of IConfig class and with the set
     * of InterceptHandler.
     *
     * @param config
     *            the configuration to use to start the broker.
     * @param handlers
     *            the handlers to install in the broker.
     * @throws IOException
     *             in case of any IO Error.
     */
    public void startServer(IConfig config, List<? extends InterceptHandler> handlers) throws IOException {
        LOG.info("Starting moquette server using IConfig instance and intercept handlers");
        startServer(config, handlers, null, null, null);
    }

    public void startServer(IConfig config, List<? extends InterceptHandler> handlers, ISslContextCreator sslCtxCreator,
            IAuthenticator authenticator, IAuthorizator authorizator) throws IOException {
        if (handlers == null) {
            handlers = Collections.emptyList();
        }
        DBUtil.init(config);

        LOG.info("Starting Moquette Server. MQTT message interceptors={}", getInterceptorIds(handlers));

        int threadNum = Runtime.getRuntime().availableProcessors() * 2;
        dbScheduler = new ThreadPoolExecutorWrapper(Executors.newScheduledThreadPool(threadNum), threadNum, "db");
        imBusinessScheduler = new ThreadPoolExecutorWrapper(Executors.newScheduledThreadPool(threadNum), threadNum, "business");
        callbackScheduler = new ThreadPoolExecutorWrapper(Executors.newScheduledThreadPool(1), 1, "callback");

        final String handlerProp = System.getProperty(BrokerConstants.INTERCEPT_HANDLER_PROPERTY_NAME);
        if (handlerProp != null) {
            config.setProperty(BrokerConstants.INTERCEPT_HANDLER_PROPERTY_NAME, handlerProp);
        }

        initMediaServerConfig(config);

        Action.init(config);
        String monitorEventAddress = config.getProperty(MONITOR_Exception_Event_Address);
        Utility.setMonitorEventAddress(monitorEventAddress);

        final String persistencePath = config.getProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME);
        LOG.info("Configuring Using persistent store file, path={}", persistencePath);
        m_store = initStore(config, this);
        m_processorBootstrapper = new ProtocolProcessorBootstrapper();

        boolean configured = configureCluster(config);

        m_store.initStore();
        final ProtocolProcessor processor = m_processorBootstrapper.init(config, handlers, authenticator, authorizator,
            this, m_store);
        LOG.info("Initialized MQTT protocol processor");
        if (sslCtxCreator == null) {
            LOG.warn("Using default SSL context creator");
            sslCtxCreator = new DefaultMoquetteSslContextCreator(config);
        }

        m_processor = processor;

        LOG.info("Binding server to the configured ports");
        m_acceptor = new NettyAcceptor();
        m_acceptor.initialize(processor, config, sslCtxCreator);


        LOG.info("Moquette server has been initialized successfully");
        m_initialized = configured;
    }

    private IStore initStore(IConfig props, Server server) {
        LOG.info("Initializing messages and sessions stores...");
        IStore store = instantiateConfiguredStore(props, server.getDbScheduler(), server);
        if (store == null) {
            throw new IllegalArgumentException("Can't start the persistence layer");
        }
        return store;
    }

    private IStore instantiateConfiguredStore(IConfig props,
                                              ThreadPoolExecutorWrapper scheduledExecutor, Server server) {
        return new MemoryStorageService(props, scheduledExecutor, server);
    }

    public IStore getStore() {
        return m_store;
    }

    private void initMediaServerConfig(IConfig config) {
    	MediaServerConfig.QINIU_ACCESS_KEY = config.getProperty(BrokerConstants.QINIU_ACCESS_KEY, MediaServerConfig.QINIU_ACCESS_KEY);
    	MediaServerConfig.QINIU_SECRET_KEY = config.getProperty(BrokerConstants.QINIU_SECRET_KEY, MediaServerConfig.QINIU_SECRET_KEY);
    	MediaServerConfig.QINIU_SERVER_URL = config.getProperty(BrokerConstants.QINIU_SERVER_URL, MediaServerConfig.QINIU_SERVER_URL);
    	if (MediaServerConfig.QINIU_SERVER_URL.contains("//")) {
            MediaServerConfig.QINIU_SERVER_URL = MediaServerConfig.QINIU_SERVER_URL.substring(MediaServerConfig.QINIU_SERVER_URL.indexOf("//") + 2);
        }

        MediaServerConfig.QINIU_BUCKET_GENERAL_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_GENERAL_NAME);
        MediaServerConfig.QINIU_BUCKET_GENERAL_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_GENERAL_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_IMAGE_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_IMAGE_NAME);
        MediaServerConfig.QINIU_BUCKET_IMAGE_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_IMAGE_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_VOICE_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_VOICE_NAME);
        MediaServerConfig.QINIU_BUCKET_VOICE_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_VOICE_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_VIDEO_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_VIDEO_NAME);
        MediaServerConfig.QINIU_BUCKET_VIDEO_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_VIDEO_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_FILE_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_FILE_NAME);
        MediaServerConfig.QINIU_BUCKET_FILE_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_FILE_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_STICKER_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_STICKER_NAME);
        MediaServerConfig.QINIU_BUCKET_STICKER_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_STICKER_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_MOMENTS_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_MOMENTS_NAME);
        MediaServerConfig.QINIU_BUCKET_MOMENTS_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_MOMENTS_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_PORTRAIT_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_PORTRAIT_NAME);
        MediaServerConfig.QINIU_BUCKET_PORTRAIT_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_PORTRAIT_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_FAVORITE_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_FAVORITE_NAME);
        MediaServerConfig.QINIU_BUCKET_FAVORITE_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_FAVORITE_DOMAIN);


    	MediaServerConfig.SERVER_IP = getServerIp(config);

        MediaServerConfig.HTTP_SERVER_PORT = Integer.parseInt(config.getProperty(BrokerConstants.HTTP_SERVER_PORT));

        MediaServerConfig.FILE_STROAGE_ROOT = config.getProperty(BrokerConstants.FILE_STORAGE_ROOT, MediaServerConfig.FILE_STROAGE_ROOT);
        File file = new File(MediaServerConfig.FILE_STROAGE_ROOT);
        if (!file.exists()) {
            file.mkdirs();
        }
        ServerSetting.setRoot(file);
        MediaServerConfig.FILE_STROAGE_REMOTE_SERVER_URL = config.getProperty(FILE_STORAGE_REMOTE_SERVER_URL);

        MediaServerConfig.USER_QINIU = Integer.parseInt(config.getProperty(BrokerConstants.USER_QINIU)) > 0;
        if(MediaServerConfig.USER_QINIU) {
            if(MediaServerConfig.QINIU_SERVER_URL.split(":").length == 2) {
                String error = "媒体存储服务只能填上Host，不能带端口";
                System.out.println(error);
                LOG.error(error);
            }
        }
    }
    
    private String getServerIp(IConfig config) {
        String serverIp = config.getProperty(BrokerConstants.SERVER_IP_PROPERTY_NAME);
        if (serverIp == null || serverIp.equals("0.0.0.0")) {
            serverIp = Utility.getLocalAddress().getHostAddress();
        }
        return serverIp;
    }

    private boolean configureCluster(IConfig config) throws FileNotFoundException {
        LOG.info("Configuring embedded Hazelcast instance");

        serverIp = getServerIp(config);
        String hzConfigPath = System.getProperty("hazelcast.configuration", "config/hazelcast.xml");
        if (hzConfigPath != null) {
            boolean isHzConfigOnClasspath = this.getClass().getClassLoader().getResource(hzConfigPath) != null;
            Config hzconfig = isHzConfigOnClasspath
                    ? new ClasspathXmlConfig(hzConfigPath)
                    : new FileSystemXmlConfig(hzConfigPath);
            LOG.info("Starting Hazelcast instance. ConfigurationFile={}", hzconfig);
            hazelcastInstance = Hazelcast.newHazelcastInstance(hzconfig);
        } else {
            LOG.info("Starting Hazelcast instance with default configuration");
            hazelcastInstance = Hazelcast.newHazelcastInstance();
        }

        longPort = config.getProperty(BrokerConstants.PORT_PROPERTY_NAME);
        shortPort = config.getProperty(BrokerConstants.HTTP_SERVER_PORT);
        boolean aes256 = Boolean.parseBoolean(config.getProperty(ENCRYPT_AES256, "false"));

        MessageShardingUtil.setNodeId(1);
        Tokenor.setKey(config.getProperty(BrokerConstants.TOKEN_SECRET_KEY));

        AES.useAes256(aes256);

        String expirTimeStr = config.getProperty(TOKEN_EXPIRE_TIME);
        if (!StringUtil.isNullOrEmpty(expirTimeStr)) {
            try {
                Tokenor.setExpiredTime(Long.parseLong(expirTimeStr));
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
        ServerAPIHelper.init(this);
        return true;
    }

    private String serverIp;
    private String longPort;
    private String shortPort;

    public String getServerIp() {
        return serverIp;
    }

    public String getLongPort() {
        return longPort;
    }

    public String getShortPort() {
        return shortPort;
    }

    public HazelcastInstance getHazelcastInstance() {
        return hazelcastInstance;
    }

    public void onApiMessage(String fromUser, String clientId, byte[] message, int messageId, String from, String request, ProtoConstants.RequestSourceType requestSourceType) {
        LOG.debug("onApiMessage");
        m_processor.onApiMessage(fromUser, clientId, message, messageId, from, request, requestSourceType);
    }

    public boolean isShutdowning() {
        return m_shutdowning;
    }

    public void stopServer() {
        System.out.println("Server will flush data to db before shutting down, please wait 5 seconds!");
        LOG.info("Unbinding server from the configured ports");
        m_shutdowning = true;
        DBUtil.SystemExiting = true;

        m_acceptor.close();
        LOG.trace("Stopping MQTT protocol processor");
        m_processorBootstrapper.shutdown();
        m_initialized = false;
        if (hazelcastInstance != null) {
            LOG.trace("Stopping embedded Hazelcast instance");
            try {
                hazelcastInstance.shutdown();
            } catch (HazelcastInstanceNotActiveException e) {
                LOG.warn("embedded Hazelcast instance is already shut down.");
            }
        }

        dbScheduler.shutdown();
        imBusinessScheduler.shutdown();
        callbackScheduler.shutdown();

        LOG.info("Moquette server has been stopped.");
    }

    /**
     * SPI method used by Broker embedded applications to add intercept handlers.
     *
     * @param interceptHandler
     *            the handler to add.
     */
    public void addInterceptHandler(InterceptHandler interceptHandler) {
        if (!m_initialized) {
            LOG.error("Moquette is not started, MQTT message interceptor cannot be added. InterceptorId={}",
                interceptHandler.getID());
            throw new IllegalStateException("Can't register interceptors on a server that is not yet started");
        }
        LOG.info("Adding MQTT message interceptor. InterceptorId={}", interceptHandler.getID());
        m_processor.addInterceptHandler(interceptHandler);
    }

    /**
     * SPI method used by Broker embedded applications to remove intercept handlers.
     *
     * @param interceptHandler
     *            the handler to remove.
     */
    public void removeInterceptHandler(InterceptHandler interceptHandler) {
        if (!m_initialized) {
            LOG.error("Moquette is not started, MQTT message interceptor cannot be removed. InterceptorId={}",
                interceptHandler.getID());
            throw new IllegalStateException("Can't deregister interceptors from a server that is not yet started");
        }
        LOG.info("Removing MQTT message interceptor. InterceptorId={}", interceptHandler.getID());
        m_processor.removeInterceptHandler(interceptHandler);
    }

    public IConfig getConfig() {
        return mConfig;
    }

    /**
     * Returns the connections manager of this broker.
     *
     * @return IConnectionsManager the instance used bt the broker.
     */
    public IConnectionsManager getConnectionsManager() {
        return m_processorBootstrapper.getConnectionDescriptors();
    }

    public ProtocolProcessor getProcessor() {
        return m_processor;
    }

    public ThreadPoolExecutorWrapper getDbScheduler() {
        return dbScheduler;
    }

    public ThreadPoolExecutorWrapper getImBusinessScheduler() {
        return imBusinessScheduler;
    }

    public ThreadPoolExecutorWrapper getCallbackScheduler() {
        return callbackScheduler;
    }
    public long getRunTime() {
        return (System.currentTimeMillis() - startTime)/1000;
    }
}
