package top.guoziyang.mydb.backend.common;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.common.Error;

/**
 * AbstractCache - 抽象缓存基类
 * 
 * 这个抽象类实现了一个基于引用计数策略的缓存框架。它为MYDB系统
 * 提供了统一的缓存管理机制，支持多线程安全的资源获取和释放。
 * 
 * 与MySQL的对比：
 * - InnoDB缓冲池（Buffer Pool）也使用类似的缓存机制
 * - MySQL的表缓存、查询缓存都有引用计数机制
 * - InnoDB使用LRU算法管理缓存页面，而MYDB使用引用计数
 * - MySQL的缓存支持脏页写回，MYDB通过抽象方法实现
 * - 两者都需要处理并发访问的线程安全问题
 * 
 * 设计模式：
 * - 模板方法模式：定义缓存操作的骨架，具体加载和释放由子类实现
 * - 策略模式：不同的缓存实现可以有不同的加载和释放策略
 * 
 * 核心特性：
 * 1. 引用计数：跟踪每个缓存项的使用情况
 * 2. 线程安全：使用ReentrantLock保证并发安全
 * 3. 延迟加载：资源在首次访问时才加载
 * 4. 自动管理：引用计数为0时自动释放资源
 * 5. 容量控制：支持最大缓存数量限制
 * 
 * 适用场景：
 * - 页面缓存（PageCache）
 * - 数据项缓存（DataItem Cache）
 * - 表元数据缓存
 * - 索引节点缓存
 */
public abstract class AbstractCache<T> {
    
    /**
     * 实际缓存的数据存储
     * Key: 资源的唯一标识符（如页号、数据项ID等）
     * Value: 缓存的资源对象
     */
    private HashMap<Long, T> cache;
    
    /**
     * 资源引用计数表
     * Key: 资源的唯一标识符
     * Value: 当前引用该资源的数量
     * 
     * 引用计数的作用：
     * - 防止正在使用的资源被意外释放
     * - 实现资源的自动管理
     * - 支持资源的延迟释放
     */
    private HashMap<Long, Integer> references;
    
    /**
     * 资源获取状态跟踪表
     * Key: 资源的唯一标识符
     * Value: true表示有线程正在获取该资源
     * 
     * 这个设计解决了并发场景下的竞态条件：
     * - 防止多个线程同时加载同一个资源
     * - 确保资源只被加载一次
     * - 避免重复的I/O操作
     */
    private HashMap<Long, Boolean> getting;

    /**
     * 缓存的最大资源数量限制
     * - 0或负数表示无限制
     * - 正数表示具体的容量限制
     * 
     * 与MySQL对比：
     * - 类似InnoDB的innodb_buffer_pool_size配置
     * - MySQL也有各种缓存大小的限制参数
     */
    private int maxResource;
    
    /**
     * 当前缓存中的资源数量
     * 用于容量控制和统计信息
     */
    private int count = 0;
    
    /**
     * 并发控制锁
     * 使用ReentrantLock而不是synchronized的原因：
     * - 更好的性能特性
     * - 支持公平锁和非公平锁
     * - 提供更丰富的锁操作API
     */
    private Lock lock;

    /**
     * 构造函数 - 初始化缓存
     * 
     * @param maxResource 最大资源数量，0表示无限制
     */
    public AbstractCache(int maxResource) {
        this.maxResource = maxResource;
        cache = new HashMap<>();
        references = new HashMap<>();
        getting = new HashMap<>();
        lock = new ReentrantLock();
    }

    /**
     * 获取缓存资源的核心方法
     * 
     * 这个方法实现了复杂的并发控制逻辑，确保：
     * 1. 多线程安全地访问缓存
     * 2. 避免重复加载同一资源
     * 3. 正确管理引用计数
     * 4. 处理缓存容量限制
     * 
     * 执行流程：
     * 1. 检查是否有其他线程正在获取该资源
     * 2. 检查资源是否已在缓存中
     * 3. 检查缓存容量是否已满
     * 4. 加载新资源并更新缓存
     * 
     * 与MySQL对比：
     * - 类似InnoDB中读取数据页的逻辑
     * - MySQL也需要处理页面的并发访问
     * - InnoDB使用更复杂的锁机制（如latch）
     * 
     * @param key 资源的唯一标识符
     * @return 请求的资源对象
     * @throws Exception 当缓存已满或加载失败时抛出异常
     */
    protected T get(long key) throws Exception {
        while(true) {
            lock.lock();
            if(getting.containsKey(key)) {
                // 有其他线程正在获取该资源，需要等待
                // 这种设计避免了多个线程同时加载同一个资源
                lock.unlock();
                try {
                    // 短暂休眠后重试，避免忙等待
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    continue;
                }
                continue;
            }

            if(cache.containsKey(key)) {
                // 资源已在缓存中，直接返回并增加引用计数
                T obj = cache.get(key);
                references.put(key, references.get(key) + 1);
                lock.unlock();
                return obj;
            }

            // 需要加载新资源，首先检查容量限制
            if(maxResource > 0 && count == maxResource) {
                lock.unlock();
                throw Error.CacheFullException;
            }
            
            // 标记正在获取该资源，防止其他线程重复加载
            count ++;
            getting.put(key, true);
            lock.unlock();
            break;
        }

        // 在锁外执行资源加载，避免长时间持有锁
        T obj = null;
        try {
            obj = getForCache(key);
        } catch(Exception e) {
            // 加载失败时恢复状态
            lock.lock();
            count --;
            getting.remove(key);
            lock.unlock();
            throw e;
        }

        // 加载成功，更新缓存状态
        lock.lock();
        getting.remove(key);  // 移除获取状态标记
        cache.put(key, obj);  // 添加到缓存
        references.put(key, 1);  // 设置初始引用计数
        lock.unlock();
        
        return obj;
    }

    /**
     * 释放缓存资源
     * 
     * 这个方法实现了基于引用计数的资源管理：
     * 1. 减少资源的引用计数
     * 2. 当引用计数归零时释放资源
     * 3. 调用子类的释放回调方法
     * 
     * 与MySQL的对比：
     * - 类似InnoDB中释放数据页的逻辑
     * - MySQL使用更复杂的页面生命周期管理
     * - InnoDB会在适当时机将脏页写回磁盘
     * 
     * 使用注意事项：
     * - 每次get()调用都必须对应一次release()调用
     * - 不要释放已经释放过的资源
     * - 不要在资源释放后继续使用该资源
     * 
     * @param key 要释放的资源标识符
     */
    protected void release(long key) {
        lock.lock();
        try {
            int ref = references.get(key) - 1;
            if(ref == 0) {
                // 引用计数归零，释放资源
                T obj = cache.get(key);
                releaseForCache(obj);  // 调用子类的释放方法
                references.remove(key);
                cache.remove(key);
                count --;
            } else {
                // 还有其他引用，只更新计数
                references.put(key, ref);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭缓存并释放所有资源
     * 
     * 这个方法在系统关闭时调用，确保所有缓存的资源
     * 都能被正确释放，避免资源泄漏。
     * 
     * 执行步骤：
     * 1. 获取所有缓存的资源键
     * 2. 逐个调用释放方法
     * 3. 清空所有内部数据结构
     * 
     * 与MySQL的对比：
     * - 类似MySQL关闭时的缓存清理过程
     * - InnoDB在关闭时会将所有脏页写回磁盘
     * - MySQL会释放所有的缓存资源
     * 
     * 注意事项：
     * - 此方法调用后缓存将不可用
     * - 确保没有其他线程在使用缓存
     * - 子类的releaseForCache方法需要能处理批量释放
     */
    protected void close() {
        lock.lock();
        try {
            Set<Long> keys = cache.keySet();
            for (long key : keys) {
                T obj = cache.get(key);
                releaseForCache(obj);
                references.remove(key);
                cache.remove(key);
            }
        } finally {
            lock.unlock();
        }
    }


    /**
     * 抽象方法 - 当资源不在缓存时的获取行为
     * 
     * 子类必须实现此方法来定义如何加载资源。
     * 这个方法在缓存未命中时被调用。
     * 
     * 实现要求：
     * - 根据key加载对应的资源
     * - 可以是从磁盘读取、网络获取或计算生成
     * - 抛出异常表示加载失败
     * 
     * 实现示例：
     * - PageCache: 从磁盘文件读取数据页
     * - DataItemCache: 解析页面数据并创建DataItem
     * - TableCache: 读取表的元数据信息
     * 
     * @param key 资源的唯一标识符
     * @return 加载的资源对象
     * @throws Exception 加载失败时抛出异常
     */
    protected abstract T getForCache(long key) throws Exception;
    
    /**
     * 抽象方法 - 当资源被驱逐时的写回行为
     * 
     * 子类必须实现此方法来定义如何释放资源。
     * 这个方法在资源引用计数归零时被调用。
     * 
     * 实现要求：
     * - 执行必要的清理操作
     * - 如果资源有修改，需要写回持久化存储
     * - 释放相关的系统资源（如文件句柄、内存等）
     * 
     * 实现示例：
     * - PageCache: 将脏页写回磁盘文件
     * - DataItemCache: 清理内存引用，可能触发页面写回
     * - TableCache: 释放表结构占用的内存
     * 
     * @param obj 要释放的资源对象
     */
    protected abstract void releaseForCache(T obj);
}
