package top.guoziyang.mydb.backend.im;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.backend.common.SubArray;
import top.guoziyang.mydb.backend.dm.DataManager;
import top.guoziyang.mydb.backend.dm.dataItem.DataItem;
import top.guoziyang.mydb.backend.im.Node.InsertAndSplitRes;
import top.guoziyang.mydb.backend.im.Node.LeafSearchRangeRes;
import top.guoziyang.mydb.backend.im.Node.SearchNextRes;
import top.guoziyang.mydb.backend.tm.TransactionManagerImpl;
import top.guoziyang.mydb.backend.utils.Parser;

/**
 * B+树索引实现类
 *
 * MYDB的索引管理模块采用B+树数据结构来实现高效的数据索引。B+树是一种自平衡的多路搜索树，
 * 广泛应用于数据库和文件系统中，具有良好的查询、插入和删除性能。
 *
 * 核心特性：
 * 1. 所有数据都存储在叶子节点中，内部节点只存储索引键
 * 2. 叶子节点通过链表连接，支持范围查询
 * 3. 树的高度较低，减少磁盘I/O次数
 * 4. 支持并发访问，使用锁机制保证数据一致性
 *
 * 与MySQL对比：
 * - MySQL的InnoDB引擎也使用B+树作为主要索引结构
 * - MYDB简化了实现，主要支持long类型的键值
 * - MySQL支持更复杂的数据类型和索引策略
 *
 * 存储结构：
 * - bootUid：指向根节点的引导信息
 * - 每个节点都是一个DataItem，存储在数据管理器中
 * - 使用固定大小的节点，便于管理和缓存
 *
 * @author MYDB Project
 */
public class BPlusTree {
    /** 数据管理器，负责节点的持久化存储 */
    DataManager dm;
    
    /** 引导信息的UID，指向存储根节点UID的数据项 */
    long bootUid;
    
    /** 引导数据项，包含根节点的UID信息 */
    DataItem bootDataItem;
    
    /** 引导信息的并发访问锁，保护根节点指针的修改 */
    Lock bootLock;

    /**
     * 创建新的B+树索引
     *
     * 创建过程：
     * 1. 创建一个空的根节点（叶子节点）
     * 2. 将根节点存储到数据管理器中
     * 3. 创建引导数据项，存储根节点的UID
     * 4. 返回引导数据项的UID
     *
     * 与MySQL对比：
     * - MySQL在创建索引时也会先创建根节点
     * - MySQL支持更复杂的索引类型（聚簇索引、非聚簇索引等）
     * - MYDB简化为统一的B+树结构
     *
     * @param dm 数据管理器，用于存储节点数据
     * @return 引导数据项的UID，用于后续加载B+树
     * @throws Exception 创建过程中的异常
     */
    public static long create(DataManager dm) throws Exception {
        // 创建空的根节点（叶子节点，包含0个键值对）
        byte[] rawRoot = Node.newNilRootRaw();
        // 将根节点存储到数据管理器中，获取其UID
        long rootUid = dm.insert(TransactionManagerImpl.SUPER_XID, rawRoot);
        // 创建引导数据项，存储根节点的UID，并返回引导数据项的UID
        return dm.insert(TransactionManagerImpl.SUPER_XID, Parser.long2Byte(rootUid));
    }

    /**
     * 从存储中加载已有的B+树索引
     *
     * 加载过程：
     * 1. 根据bootUid读取引导数据项
     * 2. 从引导数据项中解析出根节点的UID
     * 3. 初始化B+树对象的各个字段
     * 4. 创建并发访问锁
     *
     * 设计思想：
     * - 使用引导数据项间接指向根节点，便于根节点的动态更新
     * - 引导数据项的UID在B+树创建后保持不变
     * - 根节点可能因为分裂而改变，但引导信息保持稳定
     *
     * @param bootUid 引导数据项的UID
     * @param dm 数据管理器实例
     * @return 初始化完成的B+树对象
     * @throws Exception 加载过程中的异常
     */
    public static BPlusTree load(long bootUid, DataManager dm) throws Exception {
        // 读取引导数据项
        DataItem bootDataItem = dm.read(bootUid);
        assert bootDataItem != null;
        
        // 初始化B+树对象
        BPlusTree t = new BPlusTree();
        t.bootUid = bootUid;
        t.dm = dm;
        t.bootDataItem = bootDataItem;
        t.bootLock = new ReentrantLock();
        return t;
    }

    /**
     * 获取当前根节点的UID
     *
     * 实现细节：
     * 1. 使用锁保护并发访问
     * 2. 从引导数据项中解析根节点UID
     * 3. 引导数据项存储8字节的long值（根节点UID）
     *
     * 线程安全：
     * - 使用bootLock确保多线程环境下的数据一致性
     * - 读取操作也需要加锁，防止读取过程中根节点被更新
     *
     * @return 当前根节点的UID
     */
    private long rootUid() {
        bootLock.lock();
        try {
            SubArray sa = bootDataItem.data();
            // 解析引导数据项中的8字节long值（根节点UID）
            return Parser.parseLong(Arrays.copyOfRange(sa.raw, sa.start, sa.start+8));
        } finally {
            bootLock.unlock();
        }
    }

    /**
     * 更新根节点UID，当根节点分裂时调用
     *
     * 根节点分裂场景：
     * 1. 当前根节点已满，需要插入新的键值对
     * 2. 创建新的根节点，原根节点和分裂出的新节点成为子节点
     * 3. 更新引导数据项，指向新的根节点
     *
     * 参数说明：
     * - left: 原来的根节点UID（现在成为左子节点）
     * - right: 分裂产生的新节点UID（现在成为右子节点）
     * - rightKey: 右子节点的最小键值，用作分割键
     *
     * 事务处理：
     * - 使用SUPER_XID执行操作，确保系统级事务
     * - 采用before/after机制保证操作的原子性
     *
     * @param left 左子节点UID
     * @param right 右子节点UID
     * @param rightKey 右子节点的最小键值
     * @throws Exception 更新过程中的异常
     */
    private void updateRootUid(long left, long right, long rightKey) throws Exception {
        bootLock.lock();
        try {
            // 创建新的根节点，包含两个子节点的引用
            byte[] rootRaw = Node.newRootRaw(left, right, rightKey);
            // 将新根节点存储到数据管理器中
            long newRootUid = dm.insert(TransactionManagerImpl.SUPER_XID, rootRaw);
            
            // 更新引导数据项，使其指向新的根节点
            bootDataItem.before();  // 开始事务日志记录
            SubArray diRaw = bootDataItem.data();
            // 将新根节点的UID写入引导数据项
            System.arraycopy(Parser.long2Byte(newRootUid), 0, diRaw.raw, diRaw.start, 8);
            bootDataItem.after(TransactionManagerImpl.SUPER_XID);  // 提交事务日志
        } finally {
            bootLock.unlock();
        }
    }

    /**
     * 递归查找包含指定键的叶子节点
     *
     * B+树搜索原理：
     * 1. 从根节点开始，逐层向下搜索
     * 2. 在内部节点中，找到合适的子节点指针
     * 3. 递归搜索，直到到达叶子节点
     * 4. 叶子节点包含实际的数据项UID
     *
     * 算法特点：
     * - 时间复杂度：O(log N)，其中N为总键数
     * - 树的高度通常很小（3-4层），I/O次数少
     * - 每次只需要读取路径上的节点，内存友好
     *
     * 与MySQL对比：
     * - MySQL InnoDB的搜索路径类似，但支持更复杂的键类型
     * - MySQL有页面缓存优化，减少重复I/O
     *
     * @param nodeUid 当前搜索节点的UID
     * @param key 要搜索的键值
     * @return 包含该键的叶子节点UID
     * @throws Exception 搜索过程中的异常
     */
    private long searchLeaf(long nodeUid, long key) throws Exception {
        Node node = Node.loadNode(this, nodeUid);
        boolean isLeaf = node.isLeaf();
        node.release();

        if(isLeaf) {
            // 到达叶子节点，返回该节点UID
            return nodeUid;
        } else {
            // 内部节点，继续向下搜索
            long next = searchNext(nodeUid, key);
            return searchLeaf(next, key);
        }
    }

    /**
     * 在内部节点中查找下一个要搜索的子节点
     *
     * 搜索策略：
     * 1. 在当前节点的键数组中查找合适的子节点
     * 2. 如果在当前节点找不到，检查兄弟节点
     * 3. 利用兄弟节点链表，实现水平搜索
     *
     * 处理边界情况：
     * - 键值大于当前节点所有键时，转到兄弟节点继续搜索
     * - 这种设计支持动态的节点分裂和合并
     *
     * @param nodeUid 当前内部节点的UID
     * @param key 要搜索的键值
     * @return 下一个要搜索的子节点UID
     * @throws Exception 搜索过程中的异常
     */
    private long searchNext(long nodeUid, long key) throws Exception {
        while(true) {
            Node node = Node.loadNode(this, nodeUid);
            SearchNextRes res = node.searchNext(key);
            node.release();
            
            if(res.uid != 0) {
                // 找到合适的子节点
                return res.uid;
            }
            // 转到兄弟节点继续搜索
            nodeUid = res.siblingUid;
        }
    }

    /**
     * 搜索指定键值的所有匹配项
     *
     * 这是一个便捷方法，内部调用范围搜索实现。
     * 等价于范围搜索 [key, key]。
     *
     * @param key 要搜索的键值
     * @return 匹配的数据项UID列表
     * @throws Exception 搜索过程中的异常
     */
    public List<Long> search(long key) throws Exception {
        return searchRange(key, key);
    }

    /**
     * 范围搜索：查找指定范围内的所有键值
     *
     * 范围搜索算法：
     * 1. 找到包含leftKey的起始叶子节点
     * 2. 从该叶子节点开始，顺序扫描
     * 3. 利用叶子节点间的链表结构，实现高效的范围遍历
     * 4. 当超出rightKey范围或到达链表末尾时停止
     *
     * B+树范围查询优势：
     * - 叶子节点链表支持顺序访问，不需要回溯到内部节点
     * - 局部性好，连续的键通常在相邻的叶子节点中
     * - 适合ORDER BY、GROUP BY等SQL操作
     *
     * 与MySQL对比：
     * - MySQL的范围查询原理相同，但有更多优化
     * - MySQL支持逆序扫描、索引覆盖等高级特性
     * - MYDB实现了核心的范围查询功能
     *
     * @param leftKey 范围的左边界（包含）
     * @param rightKey 范围的右边界（包含）
     * @return 范围内所有匹配的数据项UID列表
     * @throws Exception 搜索过程中的异常
     */
    public List<Long> searchRange(long leftKey, long rightKey) throws Exception {
        // 获取当前根节点
        long rootUid = rootUid();
        // 找到包含leftKey的叶子节点
        long leafUid = searchLeaf(rootUid, leftKey);
        
        List<Long> uids = new ArrayList<>();
        while(true) {
            // 加载当前叶子节点
            Node leaf = Node.loadNode(this, leafUid);
            // 在当前叶子节点中搜索范围内的键
            LeafSearchRangeRes res = leaf.leafSearchRange(leftKey, rightKey);
            leaf.release();
            
            // 收集匹配的UID
            uids.addAll(res.uids);
            
            if(res.siblingUid == 0) {
                // 没有更多兄弟节点，搜索结束
                break;
            } else {
                // 移动到下一个叶子节点继续搜索
                leafUid = res.siblingUid;
            }
        }
        return uids;
    }

    /**
     * 向B+树中插入新的键值对
     *
     * 插入操作是B+树最复杂的操作之一，可能触发节点分裂和树结构调整。
     * 整个插入过程需要保证B+树的平衡性和有序性。
     *
     * 插入流程：
     * 1. 从根节点开始，递归找到合适的叶子节点
     * 2. 在叶子节点中插入键值对
     * 3. 如果节点满了，进行节点分裂
     * 4. 分裂可能向上传播，直到根节点
     * 5. 根节点分裂时，创建新的根节点，树高度增加
     *
     * 与MySQL对比：
     * - MySQL的插入操作更复杂，需要考虑聚簇索引、外键约束等
     * - MySQL有页分裂优化，尽量避免页分裂带来的性能损失
     * - MYDB简化了插入逻辑，专注于核心的B+树维护
     *
     * @param key 要插入的键值
     * @param uid 与键值关联的数据项UID
     * @throws Exception 插入过程中的异常
     */
    public void insert(long key, long uid) throws Exception {
        long rootUid = rootUid();
        InsertRes res = insert(rootUid, uid, key);
        assert res != null;
        
        // 如果插入操作导致根节点分裂，需要创建新的根节点
        if(res.newNode != 0) {
            updateRootUid(rootUid, res.newNode, res.newKey);
        }
    }

    /**
     * 插入操作的结果类
     *
     * 当插入操作导致节点分裂时，需要向上层节点传递分裂信息：
     * - newNode: 分裂产生的新节点UID
     * - newKey: 新节点的最小键值，用作父节点的分割键
     *
     * 如果没有发生分裂，newNode为0，表示插入完成且无需上层处理。
     */
    class InsertRes {
        /** 分裂产生的新节点UID，0表示未分裂 */
        long newNode;
        /** 新节点的最小键值，用作父节点的分割键 */
        long newKey;
    }

    /**
     * 递归插入操作的核心实现
     *
     * 这是一个递归方法，实现了B+树插入的核心逻辑：
     * 1. 如果是叶子节点，直接插入键值对
     * 2. 如果是内部节点，先递归插入到子节点
     * 3. 处理子节点分裂的传播
     *
     * 分裂传播机制：
     * - 子节点分裂后，需要在当前内部节点中插入新的键值对
     * - 如果当前节点也满了，继续分裂并向上传播
     * - 这个过程一直持续到某个节点不需要分裂为止
     *
     * @param nodeUid 当前操作的节点UID
     * @param uid 要插入的数据项UID
     * @param key 要插入的键值
     * @return 插入结果，包含可能的分裂信息
     * @throws Exception 插入过程中的异常
     */
    private InsertRes insert(long nodeUid, long uid, long key) throws Exception {
        Node node = Node.loadNode(this, nodeUid);
        boolean isLeaf = node.isLeaf();
        node.release();

        InsertRes res = null;
        if(isLeaf) {
            // 叶子节点：直接插入键值对
            res = insertAndSplit(nodeUid, uid, key);
        } else {
            // 内部节点：先递归插入到合适的子节点
            long next = searchNext(nodeUid, key);
            InsertRes ir = insert(next, uid, key);
            
            if(ir.newNode != 0) {
                // 子节点发生了分裂，需要在当前节点插入新的键值对
                res = insertAndSplit(nodeUid, ir.newNode, ir.newKey);
            } else {
                // 子节点没有分裂，插入操作完成
                res = new InsertRes();
            }
        }
        return res;
    }

    /**
     * 插入键值对并处理可能的节点分裂
     *
     * 这个方法处理实际的插入操作和分裂逻辑：
     * 1. 尝试在指定节点中插入键值对
     * 2. 如果节点已满，无法插入，转到兄弟节点尝试
     * 3. 如果插入成功但节点需要分裂，执行分裂操作
     *
     * 兄弟节点机制：
     * - 当节点已满时，先尝试在兄弟节点中插入
     * - 这种策略可以延迟分裂操作，提高空间利用率
     * - 类似于MySQL的页合并优化
     *
     * @param nodeUid 要插入的节点UID
     * @param uid 要插入的数据项UID
     * @param key 要插入的键值
     * @return 插入结果，包含可能的分裂信息
     * @throws Exception 插入过程中的异常
     */
    private InsertRes insertAndSplit(long nodeUid, long uid, long key) throws Exception {
        while(true) {
            Node node = Node.loadNode(this, nodeUid);
            InsertAndSplitRes iasr = node.insertAndSplit(uid, key);
            node.release();
            
            if(iasr.siblingUid != 0) {
                // 当前节点已满，转到兄弟节点尝试插入
                nodeUid = iasr.siblingUid;
            } else {
                // 插入成功，返回结果（可能包含分裂信息）
                InsertRes res = new InsertRes();
                res.newNode = iasr.newSon;
                res.newKey = iasr.newKey;
                return res;
            }
        }
    }

    /**
     * 关闭B+树，释放资源
     *
     * 主要释放引导数据项的引用，其他节点由数据管理器的缓存机制管理。
     * 这是一个清理方法，确保资源得到适当释放。
     */
    public void close() {
        bootDataItem.release();
    }
}
