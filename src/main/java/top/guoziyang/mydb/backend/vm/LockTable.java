package top.guoziyang.mydb.backend.vm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.common.Error;

/**
 * LockTable类 - 死锁检测和锁管理
 * 
 * LockTable维护了一个依赖等待图，用于进行死锁检测。
 * 当多个事务竞争同一资源时，通过等待图检测是否会形成环路（死锁）。
 * 
 * 与MySQL InnoDB锁管理的对比：
 * - MySQL InnoDB: 复杂的锁管理器，支持多种锁类型和锁升级
 * - MYDB LockTable: 简化的锁管理，专注于死锁检测
 * 
 * 核心概念：
 * 1. 资源持有图：记录每个事务持有哪些资源
 * 2. 等待图：记录每个事务等待哪些资源
 * 3. 死锁检测：通过DFS算法检测等待图中的环路
 * 4. 死锁解决：抛出异常，由上层选择回滚事务
 */
public class LockTable {
    
    /**
     * X2U映射：事务ID -> 该事务已获得的资源UID列表
     * 
     * 用途：
     * 1. 记录每个事务持有的所有资源
     * 2. 事务结束时释放所有持有的资源
     * 3. 死锁检测时遍历依赖关系
     */
    private Map<Long, List<Long>> x2u;
    
    /**
     * U2X映射：资源UID -> 持有该资源的事务ID
     * 
     * 用途：
     * 1. 快速查找某个资源被哪个事务持有
     * 2. 新事务请求资源时判断是否冲突
     * 3. 死锁检测时查找资源的持有者
     */
    private Map<Long, Long> u2x;
    
    /**
     * WAIT映射：资源UID -> 等待该资源的事务ID列表
     * 
     * 用途：
     * 1. 记录每个资源的等待队列
     * 2. 资源释放时选择下一个获得资源的事务
     * 3. 死锁检测时构建等待关系
     */
    private Map<Long, List<Long>> wait;
    
    /**
     * 等待锁映射：事务ID -> 该事务的等待锁对象
     * 
     * 用途：
     * 1. 让等待的事务阻塞在锁上
     * 2. 资源可用时通过unlock()唤醒等待的事务
     * 3. 死锁发生时清理等待锁
     */
    private Map<Long, Lock> waitLock;
    
    /**
     * 等待资源映射：事务ID -> 该事务正在等待的资源UID
     * 
     * 用途：
     * 1. 记录每个事务等待的具体资源
     * 2. 死锁检测时构建等待图
     * 3. 事务取消等待时清理映射
     */
    private Map<Long, Long> waitU;
    
    /**
     * 全局锁，保护所有内部数据结构的并发安全
     */
    private Lock lock;

    /**
     * 构造函数 - 初始化所有映射表和锁
     */
    public LockTable() {
        x2u = new HashMap<>();
        u2x = new HashMap<>();
        wait = new HashMap<>();
        waitLock = new HashMap<>();
        waitU = new HashMap<>();
        lock = new ReentrantLock();
    }

    /**
     * 请求获取资源锁
     * 
     * @param xid 请求事务的ID
     * @param uid 请求的资源UID
     * @return null表示立即获得锁，非null表示需要等待的锁对象
     * @throws Exception 如果检测到死锁则抛出异常
     * 
     * 请求流程：
     * 1. 检查事务是否已经持有该资源
     * 2. 检查资源是否空闲
     * 3. 如果空闲，直接分配给请求事务
     * 4. 如果被占用，加入等待队列并检测死锁
     * 5. 如果会死锁，清理状态并抛出异常
     * 6. 如果不会死锁，返回等待锁让事务阻塞
     */
    public Lock add(long xid, long uid) throws Exception {
        lock.lock();
        try {
            // 检查是否已经持有该资源
            if(isInList(x2u, xid, uid)) {
                return null;  // 已持有，直接返回
            }
            
            // 检查资源是否空闲
            if(!u2x.containsKey(uid)) {
                // 资源空闲，直接分配
                u2x.put(uid, xid);                    // 记录资源持有者
                putIntoList(x2u, xid, uid);           // 记录事务持有的资源
                return null;                          // 立即获得锁
            }
            
            // 资源被占用，需要等待
            waitU.put(xid, uid);                      // 记录等待的资源
            putIntoList(wait, uid, xid);              // 加入等待队列
            
            // 死锁检测
            if(hasDeadLock()) {
                // 检测到死锁，清理状态
                waitU.remove(xid);
                removeFromList(wait, uid, xid);
                throw Error.DeadlockException;        // 抛出死锁异常
            }
            
            // 创建等待锁
            Lock l = new ReentrantLock();
            l.lock();                                 // 立即获取锁（让调用者阻塞）
            waitLock.put(xid, l);                     // 记录等待锁
            return l;                                 // 返回锁对象

        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放事务持有的所有资源
     * 
     * @param xid 要释放资源的事务ID
     * 
     * 释放流程：
     * 1. 释放事务持有的所有资源
     * 2. 为每个释放的资源选择下一个等待者
     * 3. 清理事务的所有映射记录
     * 
     * 调用时机：
     * - 事务提交时
     * - 事务回滚时
     * - 事务发生异常时
     */
    public void remove(long xid) {
        lock.lock();
        try {
            // 释放该事务持有的所有资源
            List<Long> l = x2u.get(xid);
            if(l != null) {
                while(l.size() > 0) {
                    Long uid = l.remove(0);           // 移除持有的资源
                    selectNewXID(uid);                // 为资源选择新的持有者
                }
            }
            
            // 清理事务的所有映射
            waitU.remove(xid);                        // 清理等待资源
            x2u.remove(xid);                          // 清理持有资源
            waitLock.remove(xid);                     // 清理等待锁

        } finally {
            lock.unlock();
        }
    }

    /**
     * 从等待队列中选择一个事务来占用释放的资源
     * 
     * @param uid 被释放的资源UID
     * 
     * 选择策略：
     * 1. 从等待队列头部开始选择
     * 2. 跳过已经不在等待的事务
     * 3. 找到第一个有效的等待事务
     * 4. 唤醒该事务并分配资源
     */
    private void selectNewXID(long uid) {
        u2x.remove(uid);                              // 清除原持有者
        List<Long> l = wait.get(uid);                 // 获取等待队列
        if(l == null) return;
        assert l.size() > 0;

        // 从队列中找到第一个有效的等待事务
        while(l.size() > 0) {
            long xid = l.remove(0);                   // 取出队首事务
            if(!waitLock.containsKey(xid)) {
                continue;                             // 事务已不在等待，跳过
            } else {
                // 分配资源给该事务
                u2x.put(uid, xid);                    // 记录新持有者
                Lock lo = waitLock.remove(xid);       // 移除等待锁
                waitU.remove(xid);                    // 清除等待资源
                lo.unlock();                          // 唤醒等待的事务
                break;
            }
        }

        // 如果等待队列为空，清理映射
        if(l.size() == 0) wait.remove(uid);
    }

    /**
     * 死锁检测相关的时间戳映射
     * 用于DFS算法中标记访问状态
     */
    private Map<Long, Integer> xidStamp;
    private int stamp;

    /**
     * 检测是否存在死锁
     * 
     * @return true表示存在死锁
     * 
     * 算法：
     * 1. 使用DFS遍历等待图
     * 2. 如果发现环路则存在死锁
     * 3. 使用时间戳避免重复访问
     * 
     * 死锁形成条件：
     * - 事务A等待事务B持有的资源
     * - 事务B等待事务A持有的资源
     * - 形成循环等待
     */
    private boolean hasDeadLock() {
        xidStamp = new HashMap<>();
        stamp = 1;
        
        // 对所有持有资源的事务进行DFS
        for(long xid : x2u.keySet()) {
            Integer s = xidStamp.get(xid);
            if(s != null && s > 0) {
                continue;                             // 已经访问过
            }
            stamp ++;
            if(dfs(xid)) {
                return true;                          // 发现死锁
            }
        }
        return false;
    }

    /**
     * 深度优先搜索检测死锁
     * 
     * @param xid 当前访问的事务ID
     * @return true表示发现环路（死锁）
     * 
     * DFS逻辑：
     * 1. 如果当前事务已在当前路径中，发现环路
     * 2. 如果当前事务已在其他路径中访问过，无环路
     * 3. 标记当前事务，继续访问其等待的事务
     */
    private boolean dfs(long xid) {
        Integer stp = xidStamp.get(xid);
        if(stp != null && stp == stamp) {
            return true;                              // 在当前路径中，发现环路
        }
        if(stp != null && stp < stamp) {
            return false;                             // 在其他路径中访问过，无环路
        }
        
        xidStamp.put(xid, stamp);                     // 标记为当前路径访问

        Long uid = waitU.get(xid);                    // 获取等待的资源
        if(uid == null) return false;                 // 不等待任何资源
        
        Long x = u2x.get(uid);                        // 获取资源的持有者
        assert x != null;
        return dfs(x);                                // 递归检查持有者
    }

    /**
     * 从列表映射中移除指定项
     */
    private void removeFromList(Map<Long, List<Long>> listMap, long uid0, long uid1) {
        List<Long> l = listMap.get(uid0);
        if(l == null) return;
        Iterator<Long> i = l.iterator();
        while(i.hasNext()) {
            long e = i.next();
            if(e == uid1) {
                i.remove();
                break;
            }
        }
        if(l.size() == 0) {
            listMap.remove(uid0);
        }
    }

    /**
     * 向列表映射中添加项
     */
    private void putIntoList(Map<Long, List<Long>> listMap, long uid0, long uid1) {
        if(!listMap.containsKey(uid0)) {
            listMap.put(uid0, new ArrayList<>());
        }
        listMap.get(uid0).add(0, uid1);
    }

    /**
     * 检查列表映射中是否包含指定项
     */
    private boolean isInList(Map<Long, List<Long>> listMap, long uid0, long uid1) {
        List<Long> l = listMap.get(uid0);
        if(l == null) return false;
        Iterator<Long> i = l.iterator();
        while(i.hasNext()) {
            long e = i.next();
            if(e == uid1) {
                return true;
            }
        }
        return false;
    }

}
