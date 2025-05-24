package top.guoziyang.mydb.backend.im;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import top.guoziyang.mydb.backend.common.SubArray;
import top.guoziyang.mydb.backend.dm.dataItem.DataItem;
import top.guoziyang.mydb.backend.tm.TransactionManagerImpl;
import top.guoziyang.mydb.backend.utils.Parser;

/**
 * B+树节点实现类
 *
 * B+树节点是B+树的基本组成单元，负责存储键值对和维护树的结构。
 * MYDB的B+树节点设计简洁高效，支持内部节点和叶子节点的统一处理。
 *
 * 节点存储结构：
 * [LeafFlag][KeyNumber][SiblingUid]
 * [Son0][Key0][Son1][Key1]...[SonN][KeyN]
 *
 * 存储布局详解：
 * - LeafFlag(1字节)：标识是否为叶子节点，1为叶子节点，0为内部节点
 * - KeyNumber(2字节)：当前节点中存储的键值对数量
 * - SiblingUid(8字节)：指向右兄弟节点的UID，用于构建叶子节点链表
 * - 键值对数组：交替存储子节点UID和键值，支持快速二分查找
 *
 * 设计特点：
 * 1. 固定大小节点，便于内存管理和磁盘I/O
 * 2. 兄弟节点链表，支持高效的范围查询
 * 3. 统一的节点结构，简化了代码复杂度
 * 4. 平衡因子控制，保证树的平衡性
 *
 * 与MySQL对比：
 * - MySQL InnoDB使用16KB的页作为节点，包含更多元数据
 * - MYDB使用固定的节点大小，简化了页分裂和合并逻辑
 * - MySQL支持变长记录，MYDB只支持long类型的键值
 * - 两者都采用兄弟节点链表支持范围查询
 *
 * @author MYDB Project
 */
public class Node {
    /** 叶子标志位在节点中的偏移量 */
    static final int IS_LEAF_OFFSET = 0;
    /** 键数量字段在节点中的偏移量 */
    static final int NO_KEYS_OFFSET = IS_LEAF_OFFSET+1;
    /** 兄弟节点UID在节点中的偏移量 */
    static final int SIBLING_OFFSET = NO_KEYS_OFFSET+2;
    /** 节点头部大小（元数据部分） */
    static final int NODE_HEADER_SIZE = SIBLING_OFFSET+8;

    /** 平衡因子：每个节点最多包含的键值对数量的一半 */
    static final int BALANCE_NUMBER = 32;
    /** 节点总大小：头部 + 键值对数组（每个键值对占16字节：8字节UID + 8字节Key） */
    static final int NODE_SIZE = NODE_HEADER_SIZE + (2*8)*(BALANCE_NUMBER*2+2);

    /** 所属的B+树实例 */
    BPlusTree tree;
    /** 节点对应的数据项 */
    DataItem dataItem;
    /** 节点的原始字节数据 */
    SubArray raw;
    /** 节点的UID标识 */
    long uid;

    /**
     * 设置节点的叶子标志位
     *
     * 叶子标志位是节点类型识别的关键字段：
     * - true：叶子节点，存储实际数据项的UID
     * - false：内部节点，存储子节点的UID作为索引
     *
     * @param raw 节点的原始字节数组
     * @param isLeaf 是否为叶子节点
     */
    static void setRawIsLeaf(SubArray raw, boolean isLeaf) {
        if(isLeaf) {
            raw.raw[raw.start + IS_LEAF_OFFSET] = (byte)1;
        } else {
            raw.raw[raw.start + IS_LEAF_OFFSET] = (byte)0;
        }
    }

    /**
     * 获取节点的叶子标志位
     *
     * @param raw 节点的原始字节数组
     * @return true表示叶子节点，false表示内部节点
     */
    static boolean getRawIfLeaf(SubArray raw) {
        return raw.raw[raw.start + IS_LEAF_OFFSET] == (byte)1;
    }

    /**
     * 设置节点中键值对的数量
     *
     * 键数量是节点容量管理的重要指标，用于：
     * - 判断节点是否已满（达到BALANCE_NUMBER*2）
     * - 控制插入和分裂操作
     * - 优化搜索算法的边界条件
     *
     * @param raw 节点的原始字节数组
     * @param noKeys 键值对数量
     */
    static void setRawNoKeys(SubArray raw, int noKeys) {
        System.arraycopy(Parser.short2Byte((short)noKeys), 0, raw.raw, raw.start+NO_KEYS_OFFSET, 2);
    }

    /**
     * 获取节点中键值对的数量
     *
     * @param raw 节点的原始字节数组
     * @return 当前节点中的键值对数量
     */
    static int getRawNoKeys(SubArray raw) {
        return (int)Parser.parseShort(Arrays.copyOfRange(raw.raw, raw.start+NO_KEYS_OFFSET, raw.start+NO_KEYS_OFFSET+2));
    }

    /**
     * 设置兄弟节点的UID
     *
     * 兄弟节点链表是B+树的重要特性：
     * - 叶子节点通过兄弟指针形成有序链表
     * - 支持高效的范围查询和顺序访问
     * - 内部节点也可以有兄弟节点，用于插入时的负载均衡
     *
     * @param raw 节点的原始字节数组
     * @param sibling 兄弟节点的UID，0表示没有兄弟节点
     */
    static void setRawSibling(SubArray raw, long sibling) {
        System.arraycopy(Parser.long2Byte(sibling), 0, raw.raw, raw.start+SIBLING_OFFSET, 8);
    }

    /**
     * 获取兄弟节点的UID
     *
     * @param raw 节点的原始字节数组
     * @return 兄弟节点的UID，0表示没有兄弟节点
     */
    static long getRawSibling(SubArray raw) {
        return Parser.parseLong(Arrays.copyOfRange(raw.raw, raw.start+SIBLING_OFFSET, raw.start+SIBLING_OFFSET+8));
    }

    /**
     * 设置第k个子节点的UID
     *
     * 在B+树节点中，键值对按以下格式存储：
     * [Son0][Key0][Son1][Key1]...[SonN][KeyN]
     *
     * 对于叶子节点，Son存储的是数据项UID
     * 对于内部节点，Son存储的是子节点UID
     *
     * @param raw 节点的原始字节数组
     * @param uid 子节点或数据项的UID
     * @param kth 索引位置（从0开始）
     */
    static void setRawKthSon(SubArray raw, long uid, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2);
        System.arraycopy(Parser.long2Byte(uid), 0, raw.raw, offset, 8);
    }

    /**
     * 获取第k个子节点的UID
     *
     * @param raw 节点的原始字节数组
     * @param kth 索引位置（从0开始）
     * @return 子节点或数据项的UID
     */
    static long getRawKthSon(SubArray raw, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2);
        return Parser.parseLong(Arrays.copyOfRange(raw.raw, offset, offset+8));
    }

    /**
     * 设置第k个键值
     *
     * 键值是B+树排序和查找的基础：
     * - 节点内的键值保持有序
     * - 键值用于导航和范围查询
     * - 内部节点的键值作为分割键，指导子树选择
     *
     * @param raw 节点的原始字节数组
     * @param key 键值
     * @param kth 索引位置（从0开始）
     */
    static void setRawKthKey(SubArray raw, long key, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2)+8;
        System.arraycopy(Parser.long2Byte(key), 0, raw.raw, offset, 8);
    }

    /**
     * 获取第k个键值
     *
     * @param raw 节点的原始字节数组
     * @param kth 索引位置（从0开始）
     * @return 键值
     */
    static long getRawKthKey(SubArray raw, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2)+8;
        return Parser.parseLong(Arrays.copyOfRange(raw.raw, offset, offset+8));
    }

    /**
     * 从指定位置开始复制键值对数据
     *
     * 这个方法用于节点分裂时的数据迁移：
     * - 将源节点从第kth个位置开始的所有键值对复制到目标节点
     * - 目标节点从头部开始存储复制的数据
     * - 用于实现节点的均匀分割
     *
     * @param from 源节点的原始数据
     * @param to 目标节点的原始数据
     * @param kth 开始复制的位置索引
     */
    static void copyRawFromKth(SubArray from, SubArray to, int kth) {
        int offset = from.start+NODE_HEADER_SIZE+kth*(8*2);
        System.arraycopy(from.raw, offset, to.raw, to.start+NODE_HEADER_SIZE, from.end-offset);
    }

    /**
     * 向右移动键值对数据，为插入操作腾出空间
     *
     * 插入操作需要在指定位置插入新的键值对：
     * 1. 将第kth位置及之后的所有数据向右移动一个位置
     * 2. 为新的键值对腾出空间
     * 3. 保持数组的连续性和有序性
     *
     * 移动策略：
     * - 从数组末尾开始，逐个元素向右移动
     * - 每个键值对占用16字节（8字节UID + 8字节Key）
     * - 移动后原第kth位置变为空，可以插入新数据
     *
     * @param raw 节点的原始字节数组
     * @param kth 插入位置的索引
     */
    static void shiftRawKth(SubArray raw, int kth) {
        int begin = raw.start+NODE_HEADER_SIZE+(kth+1)*(8*2);
        int end = raw.start+NODE_SIZE-1;
        for(int i = end; i >= begin; i --) {
            raw.raw[i] = raw.raw[i-(8*2)];
        }
    }

    /**
     * 创建新的根节点
     *
     * 当原根节点分裂时，需要创建新的根节点来维护B+树的结构：
     * 1. 新根节点是内部节点（非叶子）
     * 2. 包含两个子节点：原根节点(left)和分裂产生的节点(right)
     * 3. 使用分割键(key)来区分两个子树的键值范围
     * 4. 右子树的最大键设为Long.MAX_VALUE，简化边界处理
     *
     * 根节点分裂是B+树增高的唯一方式：
     * - 分裂前：树高度为h，根节点已满
     * - 分裂后：树高度为h+1，新根节点包含两个子节点
     * - 保证了B+树的平衡性
     *
     * @param left 左子节点UID（原根节点）
     * @param right 右子节点UID（分裂产生的新节点）
     * @param key 分割键值，用于区分左右子树
     * @return 新根节点的原始字节数据
     */
    static byte[] newRootRaw(long left, long right, long key)  {
        SubArray raw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);

        setRawIsLeaf(raw, false);           // 根节点是内部节点
        setRawNoKeys(raw, 2);               // 包含两个键值对
        setRawSibling(raw, 0);              // 根节点没有兄弟节点
        setRawKthSon(raw, left, 0);         // 第0个子节点是左子树
        setRawKthKey(raw, key, 0);          // 分割键
        setRawKthSon(raw, right, 1);        // 第1个子节点是右子树
        setRawKthKey(raw, Long.MAX_VALUE, 1); // 右子树的最大边界

        return raw.raw;
    }

    /**
     * 创建空的初始根节点
     *
     * 当创建新的B+树时，需要一个空的根节点作为起点：
     * 1. 初始根节点是叶子节点（树只有一层）
     * 2. 不包含任何键值对（键数量为0）
     * 3. 没有兄弟节点
     *
     * 空根节点特点：
     * - 随着数据的插入，会逐渐填充键值对
     * - 当节点满时，会分裂并创建新的内部根节点
     * - 是B+树的初始状态，树高度为1
     *
     * @return 空根节点的原始字节数据
     */
    static byte[] newNilRootRaw()  {
        SubArray raw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);

        setRawIsLeaf(raw, true);    // 初始根节点是叶子节点
        setRawNoKeys(raw, 0);       // 没有键值对
        setRawSibling(raw, 0);      // 没有兄弟节点

        return raw.raw;
    }

    /**
     * 从数据管理器中加载节点
     *
     * 这是节点的工厂方法，负责：
     * 1. 从数据管理器读取指定UID的数据项
     * 2. 创建Node对象并初始化各个字段
     * 3. 建立节点与B+树的关联关系
     *
     * 加载过程：
     * - 通过数据管理器的缓存机制，减少磁盘I/O
     * - 初始化节点的元数据和原始数据引用
     * - 为后续的读写操作做准备
     *
     * 线程安全：
     * - 依赖数据管理器的并发控制机制
     * - 每个线程获得独立的Node对象实例
     *
     * @param bTree 所属的B+树实例
     * @param uid 节点的UID标识
     * @return 初始化完成的节点对象
     * @throws Exception 读取节点数据时的异常
     */
    static Node loadNode(BPlusTree bTree, long uid) throws Exception {
        DataItem di = bTree.dm.read(uid);
        assert di != null;
        
        Node n = new Node();
        n.tree = bTree;         // 建立与B+树的关联
        n.dataItem = di;        // 保存数据项引用
        n.raw = di.data();      // 获取原始字节数据
        n.uid = uid;            // 保存节点UID
        return n;
    }

    /**
     * 释放节点资源
     *
     * 当节点使用完毕后，需要释放相关资源：
     * - 释放数据项的引用，触发缓存管理
     * - 通知数据管理器该节点可以被回收或换出
     * - 是资源管理的良好实践
     */
    public void release() {
        dataItem.release();
    }

    /**
     * 判断当前节点是否为叶子节点
     *
     * 叶子节点和内部节点的区别：
     * - 叶子节点：存储实际数据项的UID，支持范围查询
     * - 内部节点：存储子节点的UID，用于索引导航
     *
     * 线程安全：
     * - 使用读锁保护，允许多个线程同时读取
     * - 避免读取过程中节点类型被修改
     *
     * @return true表示叶子节点，false表示内部节点
     */
    public boolean isLeaf() {
        dataItem.rLock();
        try {
            return getRawIfLeaf(raw);
        } finally {
            dataItem.rUnLock();
        }
    }

    /**
     * 搜索下一个节点的结果类
     *
     * 在内部节点中搜索时，可能出现两种情况：
     * 1. 找到合适的子节点：uid包含子节点UID，siblingUid为0
     * 2. 需要搜索兄弟节点：uid为0，siblingUid包含兄弟节点UID
     */
    class SearchNextRes {
        /** 下一个要搜索的子节点UID，0表示需要转到兄弟节点 */
        long uid;
        /** 兄弟节点UID，用于水平搜索 */
        long siblingUid;
    }

    /**
     * 在内部节点中搜索指定键的下一个节点
     *
     * B+树内部节点搜索算法：
     * 1. 顺序扫描当前节点的所有键值
     * 2. 找到第一个大于目标键的位置
     * 3. 返回对应的子节点UID
     * 4. 如果所有键都小于目标键，转到兄弟节点
     *
     * 搜索策略：
     * - 内部节点的键值起到"路标"作用
     * - 键值i指向包含所有小于等于键值i的子树
     * - 兄弟节点搜索处理节点分裂后的边界情况
     *
     * 并发控制：
     * - 使用读锁保护节点数据的一致性
     * - 允许多个查询线程同时访问
     *
     * @param key 要搜索的键值
     * @return 搜索结果，包含下一个节点的UID信息
     */
    public SearchNextRes searchNext(long key) {
        dataItem.rLock();
        try {
            SearchNextRes res = new SearchNextRes();
            int noKeys = getRawNoKeys(raw);
            
            // 顺序搜索，找到第一个大于key的键值
            for(int i = 0; i < noKeys; i ++) {
                long ik = getRawKthKey(raw, i);
                if(key < ik) {
                    // 找到合适的子节点
                    res.uid = getRawKthSon(raw, i);
                    res.siblingUid = 0;
                    return res;
                }
            }
            
            // 所有键都小于等于目标键，需要搜索兄弟节点
            res.uid = 0;
            res.siblingUid = getRawSibling(raw);
            return res;

        } finally {
            dataItem.rUnLock();
        }
    }

    class LeafSearchRangeRes {
        List<Long> uids;
        long siblingUid;
    }

    public LeafSearchRangeRes leafSearchRange(long leftKey, long rightKey) {
        dataItem.rLock();
        try {
            int noKeys = getRawNoKeys(raw);
            int kth = 0;
            while(kth < noKeys) {
                long ik = getRawKthKey(raw, kth);
                if(ik >= leftKey) {
                    break;
                }
                kth ++;
            }
            List<Long> uids = new ArrayList<>();
            while(kth < noKeys) {
                long ik = getRawKthKey(raw, kth);
                if(ik <= rightKey) {
                    uids.add(getRawKthSon(raw, kth));
                    kth ++;
                } else {
                    break;
                }
            }
            long siblingUid = 0;
            if(kth == noKeys) {
                siblingUid = getRawSibling(raw);
            }
            LeafSearchRangeRes res = new LeafSearchRangeRes();
            res.uids = uids;
            res.siblingUid = siblingUid;
            return res;
        } finally {
            dataItem.rUnLock();
        }
    }

    class InsertAndSplitRes {
        long siblingUid, newSon, newKey;
    }

    public InsertAndSplitRes insertAndSplit(long uid, long key) throws Exception {
        boolean success = false;
        Exception err = null;
        InsertAndSplitRes res = new InsertAndSplitRes();

        dataItem.before();
        try {
            success = insert(uid, key);
            if(!success) {
                res.siblingUid = getRawSibling(raw);
                return res;
            }
            if(needSplit()) {
                try {
                    SplitRes r = split();
                    res.newSon = r.newSon;
                    res.newKey = r.newKey;
                    return res;
                } catch(Exception e) {
                    err = e;
                    throw e;
                }
            } else {
                return res;
            }
        } finally {
            if(err == null && success) {
                dataItem.after(TransactionManagerImpl.SUPER_XID);
            } else {
                dataItem.unBefore();
            }
        }
    }

    private boolean insert(long uid, long key) {
        int noKeys = getRawNoKeys(raw);
        int kth = 0;
        while(kth < noKeys) {
            long ik = getRawKthKey(raw, kth);
            if(ik < key) {
                kth ++;
            } else {
                break;
            }
        }
        if(kth == noKeys && getRawSibling(raw) != 0) return false;

        if(getRawIfLeaf(raw)) {
            shiftRawKth(raw, kth);
            setRawKthKey(raw, key, kth);
            setRawKthSon(raw, uid, kth);
            setRawNoKeys(raw, noKeys+1);
        } else {
            long kk = getRawKthKey(raw, kth);
            setRawKthKey(raw, key, kth);
            shiftRawKth(raw, kth+1);
            setRawKthKey(raw, kk, kth+1);
            setRawKthSon(raw, uid, kth+1);
            setRawNoKeys(raw, noKeys+1);
        }
        return true;
    }

    private boolean needSplit() {
        return BALANCE_NUMBER*2 == getRawNoKeys(raw);
    }

    class SplitRes {
        long newSon, newKey;
    }

    private SplitRes split() throws Exception {
        SubArray nodeRaw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);
        setRawIsLeaf(nodeRaw, getRawIfLeaf(raw));
        setRawNoKeys(nodeRaw, BALANCE_NUMBER);
        setRawSibling(nodeRaw, getRawSibling(raw));
        copyRawFromKth(raw, nodeRaw, BALANCE_NUMBER);
        long son = tree.dm.insert(TransactionManagerImpl.SUPER_XID, nodeRaw.raw);
        setRawNoKeys(raw, BALANCE_NUMBER);
        setRawSibling(raw, son);

        SplitRes res = new SplitRes();
        res.newSon = son;
        res.newKey = getRawKthKey(nodeRaw, 0);
        return res;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Is leaf: ").append(getRawIfLeaf(raw)).append("\n");
        int KeyNumber = getRawNoKeys(raw);
        sb.append("KeyNumber: ").append(KeyNumber).append("\n");
        sb.append("sibling: ").append(getRawSibling(raw)).append("\n");
        for(int i = 0; i < KeyNumber; i ++) {
            sb.append("son: ").append(getRawKthSon(raw, i)).append(", key: ").append(getRawKthKey(raw, i)).append("\n");
        }
        return sb.toString();
    }

}
