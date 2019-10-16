/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.redis;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.rpc.RpcException;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RECONNECT_PERIOD;
import static org.apache.dubbo.registry.Constants.DEFAULT_SESSION_TIMEOUT;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER;
import static org.apache.dubbo.registry.Constants.REGISTRY_RECONNECT_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SESSION_TIMEOUT_KEY;
import static org.apache.dubbo.registry.Constants.UNREGISTER;

/**
 * RedisRegistry
 */
public class RedisRegistry extends FailbackRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RedisRegistry.class);

    /** 默认连接端口 */
    private static final int DEFAULT_REDIS_PORT = 6379;
    /**
     * 默认redis根节点,涉及到的是dubbo的分组配置
     */
    private final static String DEFAULT_ROOT = "dubbo";

    /**
     * 任务调度器
     */
    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryExpireTimer", true));

    /**
     * Redis Key 过期机制执行器
     */
    private final ScheduledFuture<?> expireFuture;

    /**
     * Redis 根节点
     */
    private final String root;
    /**
     * JedisPool集合，map 的key为 "ip:port"的形式
     */
    private final Map<String, JedisPool> jedisPools = new ConcurrentHashMap<>();

    /**
     * 通知器集合，key为 Root + Service的形式
     * 例如 /dubbo/com.alibaba.dubbo.demo.DemoService
     */
    private final ConcurrentMap<String, Notifier> notifiers = new ConcurrentHashMap<>();

    /**
     * 重连间隔时间 ms
     */
    private final int reconnectPeriod;
    /**
     * 间隔过期时间 ms
     */
    private final int expirePeriod;

    /**
     * 是否通过监控中心，用于判断脏数据，脏数据由监控中心删除
     */
    private volatile boolean admin = false;
    /**
     * 是否复制模式
     */
    private boolean replicate;

    //构造函数中主要完成了redis连接相关的操作
    public RedisRegistry(URL url) {
        //调用父类注册(内存)
        super(url);

        logger.info("创建Redis注册中心 " + url);

        //判断地址是否为空
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        logger.info("创建redis 连接池");
        //实例化对象池
        GenericObjectPoolConfig config = new GenericObjectPoolConfig();
        //如果testOnBorrower被设置,pool会在borrowerObject返回对象之前使用,PoolableObjectFactory的 validateObject 来验证这个对象是否有效
        //要是对象没通过验证,这对象会被丢弃,然后重新选择一个新的对象
        config.setTestOnBorrow(url.getParameter("test.on.borrow", true));
        //如果testOnReturn被设置,pool会在returnObject的时候通过PoolableObjectFactory的的validateObject 方法验证对象
        //如果对象没有通过验证,对象会被丢弃,不会被放到池中
        config.setTestOnReturn(url.getParameter("test.on.return", false));
        //指定空闲对象是否应该使用,PoolableObjectFactory 的 validateObject 校验，如果校验失败，这个对象会从对象池中被清除。
        //这个设置仅在timeBetweenEvictionRunsMillis 被设置成正值（ >0） 的时候才会生效.
        config.setTestWhileIdle(url.getParameter("test.while.idle", false));
        if (url.getParameter("max.idle", 0) > 0) {
            //控制一个pool最多有多少个状态为空闲的jedis实例
            config.setMaxIdle(url.getParameter("max.idle", 0));
        }
        if (url.getParameter("min.idle", 0) > 0) {
            //控制一个pool最少有多少个状态为空闲的jedis实例
            config.setMinIdle(url.getParameter("min.idle", 0));
        }
        if (url.getParameter("max.active", 0) > 0) {
            //控制一个pool最多有多少个jedis实例
            config.setMaxTotal(url.getParameter("max.active", 0));
        }
        if (url.getParameter("max.total", 0) > 0) {
            config.setMaxTotal(url.getParameter("max.total", 0));
        }
        if (url.getParameter("max.wait", url.getParameter("timeout", 0)) > 0) {
            //表示当引入一个jedis实例时,最大的等待时间,如果超过等待时间,则直接抛出exception
            config.setMaxWaitMillis(url.getParameter("max.wait", url.getParameter("timeout", 0)));
        }
        if (url.getParameter("num.tests.per.eviction.run", 0) > 0) {
            config.setNumTestsPerEvictionRun(url.getParameter("num.tests.per.eviction.run", 0));
        }
        if (url.getParameter("time.between.eviction.runs.millis", 0) > 0) {
            //指定驱逐线程的休眠时间。如果这个值不是正数（ >0），不会有驱逐线程运行。
            config.setTimeBetweenEvictionRunsMillis(url.getParameter("time.between.eviction.runs.millis", 0));
        }
        if (url.getParameter("min.evictable.idle.time.millis", 0) > 0) {
            // 指定最小的空闲驱逐的时间间隔（空闲超过指定的时间的对象，会被清除掉）。
            // 这个设置仅在 timeBetweenEvictionRunsMillis 被设置成正值（ >0）的时候才会生效
            config.setMinEvictableIdleTimeMillis(url.getParameter("min.evictable.idle.time.millis", 0));
        }

        String cluster = url.getParameter("cluster", "failover");
        if (!"failover".equals(cluster) && !"replicate".equals(cluster)) {
            throw new IllegalArgumentException("Unsupported redis cluster: " + cluster + ". The redis cluster only supported failover or replicate.");
        }

        //设置是否为复制模式
        replicate = "replicate".equals(cluster);

        List<String> addresses = new ArrayList<>();
        addresses.add(url.getAddress());

        String[] backups = url.getParameter(RemotingConstants.BACKUP_KEY, new String[0]);
        if (ArrayUtils.isNotEmpty(backups)) {
            addresses.addAll(Arrays.asList(backups));
        }

        //分隔地址
        for (String address : addresses) {
            int i = address.indexOf(':');
            String host;
            int port;
            if (i > 0) {
                host = address.substring(0, i);
                port = Integer.parseInt(address.substring(i + 1));
            } else {
                host = address;
                //没有端口的置为默认端口
                port = DEFAULT_REDIS_PORT;
            }
            //创建连接池,并加入集合
            this.jedisPools.put(address, new JedisPool(config, host, port,
                    url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT), StringUtils.isEmpty(url.getPassword()) ? null : url.getPassword(),
                    url.getParameter("db.index", 0)));
        }

        //设置url携带的连接超时时间,如果没有设置,则默认3s
        this.reconnectPeriod = url.getParameter(REGISTRY_RECONNECT_PERIOD_KEY, DEFAULT_REGISTRY_RECONNECT_PERIOD);
        //url中的默认分组设置,默认为dubbo
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);

        //分组强制以'/'开头
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        //分组强制以'/'结尾
        if (!group.endsWith(PATH_SEPARATOR)) {
            group = group + PATH_SEPARATOR;
        }
        //修改根节点信息
        this.root = group;

        //获取过期周期配置,默认60s
        this.expirePeriod = url.getParameter(SESSION_TIMEOUT_KEY, DEFAULT_SESSION_TIMEOUT);

        //创建过期机制执行器
        this.expireFuture = expireExecutor.scheduleWithFixedDelay(() -> {
            try {
                //延长到期时间
                deferExpired(); // Extend the expiration time
            } catch (Throwable t) { // Defensive fault tolerance
                logger.error("Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
            }
        }, expirePeriod / 2, expirePeriod / 2, TimeUnit.MILLISECONDS);
    }

    /**
     * 该方法实现了延长到期时间的逻辑,遍历了已经注册的服务url.
     * 这里会有一个是否为非动态管理模式的判断,也就是判断该节点是否为动态节点,只有动态节点需要延长过期时间,因为动态节点需要人工删除节点.
     * 延长过期时间就是重新注册一次.因而其它的节点会被监控中心清除.也就是调用了clean方法.
     */
    private void deferExpired() {
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                try (Jedis jedis = jedisPool.getResource()) {
                    //遍历已经注册的服务url列表
                    for (URL url : new HashSet<>(getRegistered())) {
                        //如果是非动态管理模式
                        if (url.getParameter(DYNAMIC_KEY, true)) {
                            //获得分类路径
                            String key = toCategoryPath(url);
                            //以hash散列表的形式存储
                            if (jedis.hset(key, url.toFullString(), String.valueOf(System.currentTimeMillis() + expirePeriod)) == 1) {
                                //发布redis注册事件
                                jedis.publish(key, REGISTER);
                            }
                        }
                    }
                    //如果通过监控中心
                    if (admin) {
                        //删除过时的脏数据
                        clean(jedis);
                    }
                    //如果服务器端已经同步数据,只需写入单台机器
                    if (!replicate) {
                        break;//  If the server side has synchronized data, just write a single machine
                    }
                }
            } catch (Throwable t) {
                logger.warn("Failed to write provider heartbeat to redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
            }
        }
    }

    // 该方法用来清理过期数据,脏数据由监控中心删除出,那么判断过期就是通过map的value来判断.
    // 逻辑就是在redis中先把记录删除,然后再取消订阅
    private void clean(Jedis jedis) {
        // 获取所有服务
        // @todo 竟然使用了keys
        Set<String> keys = jedis.keys(root + ANY_VALUE);
        if (CollectionUtils.isNotEmpty(keys)) {
            for (String key : keys) {
                Map<String, String> values = jedis.hgetAll(key);
                if (CollectionUtils.isNotEmptyMap(values)) {
                    boolean delete = false;
                    //遍历每个值,判断是否过期
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        URL url = URL.valueOf(entry.getKey());
                        if (url.getParameter(DYNAMIC_KEY, true)) {
                            long expire = Long.parseLong(entry.getValue());
                            if (expire < now) {
                                //删除过期的值
                                jedis.hdel(key, entry.getKey());
                                delete = true;
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Delete expired key: " + key + " -> value: " + entry.getKey() + ", expire: " + new Date(expire) + ", now: " + new Date(now));
                                }
                            }
                        }
                    }
                    //取消注册
                    if (delete) {
                        jedis.publish(key, UNREGISTER);
                    }
                }
            }
        }
    }

    /**
     * 判断注册中心可用是否可用,通过redis是否连接来判断.只要有一台redis可连接,就算注册中心可用.
     * @return
     */
    @Override
    public boolean isAvailable() {
        //遍历连接池集合
        for (JedisPool jedisPool : jedisPools.values()) {
            //从连接吃吃获取到jedis实例
            try (Jedis jedis = jedisPool.getResource()) {
                //判断redis服务器被连接着
                //只要有一台连接,则算注册中心可用
                if (jedis.isConnected()) {
                    return true; // At least one single machine is available.
                }
            } catch (Throwable t) {
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            //关闭过期执行器
            expireFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            //关闭通知器
            for (Notifier notifier : notifiers.values()) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                //销毁连接池
                jedisPool.destroy();
            } catch (Throwable t) {
                logger.warn("Failed to destroy the redis registry client. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
            }
        }
        //销毁任务调度器
        ExecutorUtil.gracefulShutdown(expireExecutor, expirePeriod);
    }

    /**
     * 具体的逻辑是先将需要注册的服务信息保存到redis,然后发布redis注册事件
     * @param url
     */
    @Override
    public void doRegister(URL url) {
        //获得分类路径
        String key = toCategoryPath(url);
        logger.info("分类路径:"+key);
        //获得url字符串作为value
        String value = url.toFullString();
        logger.info("url:"+value);
        //计算超时时间
        String expire = String.valueOf(System.currentTimeMillis() + expirePeriod);
        logger.info("超时时间:"+expire);

        boolean success = false;
        RpcException exception = null;
        //遍历连接
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                try (Jedis jedis = jedisPool.getResource()) {
                    //写入redis map
                    jedis.hset(key, value, expire);
                    //发布redis注册事件
                    //这样订阅该key的服务消费者和监控中心,就会实时读取该服务的最新数据
                    jedis.publish(key, REGISTER);
                    success = true;
                    //如果服务器端已同步数据,只需要写入单台机器
                    if (!replicate) {
                        break; //  If the server side has synchronized data, just write a single machine
                    }
                }
            } catch (Throwable t) {
                exception = new RpcException("Failed to register service to redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    @Override
    public void doUnregister(URL url) {
        //获取分类路径
        String key = toCategoryPath(url);
        //获取url字符串作为value
        String value = url.toFullString();
        RpcException exception = null;
        boolean success = false;

        //遍历连接
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                try (Jedis jedis = jedisPool.getResource()) {
                    //删除redis中的路径
                    jedis.hdel(key, value);
                    //发布redis取消注册事件
                    jedis.publish(key, UNREGISTER);
                    success = true;
                    if (!replicate) {
                        break; //  If the server side has synchronized data, just write a single machine
                    }
                }
            } catch (Throwable t) {
                exception = new RpcException("Failed to unregister service to redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        //返回服务地址
        String service = toServicePath(url);
        logger.info("service:"+service);
        //获得通知器
        Notifier notifier = notifiers.get(service);
        if (notifier == null) {
            //创建通知器
            Notifier newNotifier = new Notifier(service);
            notifiers.putIfAbsent(service, newNotifier);
            notifier = notifiers.get(service);
            //保证并发情况下,有且只有一个通知器启动
            if (notifier == newNotifier) {
                notifier.start();
            }
        }

        boolean success = false;
        RpcException exception = null;
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                try (Jedis jedis = jedisPool.getResource()) {
                    //是否以*结尾
                    if (service.endsWith(ANY_VALUE)) {
                        admin = true;
                        Set<String> keys = jedis.keys(service);
                        if (CollectionUtils.isNotEmpty(keys)) {
                            Map<String, Set<String>> serviceKeys = new HashMap<>();
                            for (String key : keys) {
                                String serviceKey = toServicePath(key);
                                logger.info("service key = " + serviceKey);
                                Set<String> sk = serviceKeys.computeIfAbsent(serviceKey, k -> new HashSet<>());
                                sk.add(key);
                            }
                            for (Set<String> sk : serviceKeys.values()) {
                                //通知订阅者
                                doNotify(jedis, sk, url, Collections.singletonList(listener));
                            }
                        }
                    } else {
                        doNotify(jedis, jedis.keys(service + PATH_SEPARATOR + ANY_VALUE), url, Collections.singletonList(listener));
                    }
                    success = true;
                    break; // Just read one server's data
                }
            } catch (Throwable t) { // Try the next server
                exception = new RpcException("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
    }

    private void doNotify(Jedis jedis, String key) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<>(getSubscribed()).entrySet()) {
            doNotify(jedis, Collections.singletonList(key), entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    private void doNotify(Jedis jedis, Collection<String> keys, URL url, Collection<NotifyListener> listeners) {
        if (keys == null || keys.isEmpty()
                || listeners == null || listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<URL> result = new ArrayList<>();
        List<String> categories = Arrays.asList(url.getParameter(CATEGORY_KEY, new String[0]));
        String consumerService = url.getServiceInterface();
        for (String key : keys) {
            if (!ANY_VALUE.equals(consumerService)) {
                String providerService = toServiceName(key);
                if (!providerService.equals(consumerService)) {
                    continue;
                }
            }
            String category = toCategoryName(key);
            if (!categories.contains(ANY_VALUE) && !categories.contains(category)) {
                continue;
            }
            List<URL> urls = new ArrayList<>();
            Map<String, String> values = jedis.hgetAll(key);
            if (CollectionUtils.isNotEmptyMap(values)) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    URL u = URL.valueOf(entry.getKey());
                    if (!u.getParameter(DYNAMIC_KEY, true)
                            || Long.parseLong(entry.getValue()) >= now) {
                        if (UrlUtils.isMatch(url, u)) {
                            urls.add(u);
                        }
                    }
                }
            }
            if (urls.isEmpty()) {
                urls.add(URLBuilder.from(url)
                        .setProtocol(EMPTY_PROTOCOL)
                        .setAddress(ANYHOST_VALUE)
                        .setPath(toServiceName(key))
                        .addParameter(CATEGORY_KEY, category)
                        .build());
            }
            result.addAll(urls);
            if (logger.isInfoEnabled()) {
                logger.info("redis notify: " + key + " = " + urls);
            }
        }
        if (CollectionUtils.isEmpty(result)) {
            return;
        }
        for (NotifyListener listener : listeners) {
            notify(url, listener, result);
        }
    }

    private String toServiceName(String categoryPath) {
        String servicePath = toServicePath(categoryPath);
        return servicePath.startsWith(root) ? servicePath.substring(root.length()) : servicePath;
    }

    private String toCategoryName(String categoryPath) {
        int i = categoryPath.lastIndexOf(PATH_SEPARATOR);
        return i > 0 ? categoryPath.substring(i + 1) : categoryPath;
    }

    private String toServicePath(String categoryPath) {
        int i;
        if (categoryPath.startsWith(root)) {
            i = categoryPath.indexOf(PATH_SEPARATOR, root.length());
        } else {
            i = categoryPath.indexOf(PATH_SEPARATOR);
        }
        return i > 0 ? categoryPath.substring(0, i) : categoryPath;
    }

    private String toServicePath(URL url) {
        return root + url.getServiceInterface();
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    private class NotifySub extends JedisPubSub {

        private final JedisPool jedisPool;

        public NotifySub(JedisPool jedisPool) {
            this.jedisPool = jedisPool;
        }

        @Override
        public void onMessage(String key, String msg) {
            if (logger.isInfoEnabled()) {
                logger.info("redis event: " + key + " = " + msg);
            }
            if (msg.equals(REGISTER)
                    || msg.equals(UNREGISTER)) {
                try {
                    Jedis jedis = jedisPool.getResource();
                    try {
                        doNotify(jedis, key);
                    } finally {
                        jedis.close();
                    }
                } catch (Throwable t) { // TODO Notification failure does not restore mechanism guarantee
                    logger.error(t.getMessage(), t);
                }
            }
        }

        @Override
        public void onPMessage(String pattern, String key, String msg) {
            onMessage(key, msg);
        }

        @Override
        public void onSubscribe(String key, int num) {
        }

        @Override
        public void onPSubscribe(String pattern, int num) {
        }

        @Override
        public void onUnsubscribe(String key, int num) {
        }

        @Override
        public void onPUnsubscribe(String pattern, int num) {
        }

    }

    private class Notifier extends Thread {


        private final String service;
        private final AtomicInteger connectSkip = new AtomicInteger();
        private final AtomicInteger connectSkipped = new AtomicInteger();
        private volatile Jedis jedis;
        private volatile boolean first = true;
        private volatile boolean running = true;
        private volatile int connectRandom;

        public Notifier(String service) {
            super.setDaemon(true);
            super.setName("DubboRedisSubscribe");
            this.service = service;
        }

        private void resetSkip() {
            connectSkip.set(0);
            connectSkipped.set(0);
            connectRandom = 0;
        }

        private boolean isSkip() {
            int skip = connectSkip.get(); // Growth of skipping times
            if (skip >= 10) { // If the number of skipping times increases by more than 10, take the random number
                if (connectRandom == 0) {
                    connectRandom = ThreadLocalRandom.current().nextInt(10);
                }
                skip = 10 + connectRandom;
            }
            if (connectSkipped.getAndIncrement() < skip) { // Check the number of skipping times
                return true;
            }
            connectSkip.incrementAndGet();
            connectSkipped.set(0);
            connectRandom = 0;
            return false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    if (!isSkip()) {
                        try {
                            for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
                                JedisPool jedisPool = entry.getValue();
                                try {
                                    jedis = jedisPool.getResource();
                                    try {
                                        if (service.endsWith(ANY_VALUE)) {
                                            if (first) {
                                                first = false;
                                                Set<String> keys = jedis.keys(service);
                                                if (CollectionUtils.isNotEmpty(keys)) {
                                                    for (String s : keys) {
                                                        doNotify(jedis, s);
                                                    }
                                                }
                                                resetSkip();
                                            }
                                            jedis.psubscribe(new NotifySub(jedisPool), service); // blocking
                                        } else {
                                            if (first) {
                                                first = false;
                                                doNotify(jedis, service);
                                                resetSkip();
                                            }
                                            //订阅主题,可以使用通配符
                                            logger.info("订阅主题: "+service + PATH_SEPARATOR + ANY_VALUE);
                                            jedis.psubscribe(new NotifySub(jedisPool), service + PATH_SEPARATOR + ANY_VALUE); // blocking
                                        }
                                        break;
                                    } finally {
                                        jedis.close();
                                    }
                                } catch (Throwable t) { // Retry another server
                                    logger.warn("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
                                    // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                    sleep(reconnectPeriod);
                                }
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        public void shutdown() {
            try {
                running = false;
                jedis.disconnect();
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

    }

}
