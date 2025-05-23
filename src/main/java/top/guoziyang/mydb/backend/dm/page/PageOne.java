package top.guoziyang.mydb.backend.dm.page;

import java.util.Arrays;

import top.guoziyang.mydb.backend.dm.pageCache.PageCache;
import top.guoziyang.mydb.backend.utils.RandomUtil;

/**
 * PageOne - 第一页特殊管理类
 * 
 * 第一页（页面0）在数据库中具有特殊意义，用于存储数据库的元信息和启动检查数据。
 * 
 * 与MySQL的对比：
 * - MySQL InnoDB的第一页（page 0）存储表空间头信息、段信息等关键元数据
 * - MySQL通过checksum和LSN来检测数据库异常关闭
 * - MYDB简化了这一设计，使用随机字节来检测异常关闭
 * 
 * ValidCheck机制详解：
 * - 正常启动：在100~107字节处写入随机字节
 * - 正常关闭：将100~107字节的内容复制到108~115字节
 * - 异常检测：启动时比较这两个区域，如果不相等说明异常关闭
 * 
 * 这种设计保证了：
 * 1. 数据库异常关闭的检测
 * 2. 崩溃恢复流程的触发
 * 3. 数据一致性的基础保障
 */
public class PageOne {
    /**
     * ValidCheck起始偏移量
     * 在页面的第100字节开始存储校验信息
     */
    private static final int OF_VC = 100;
    
    /**
     * ValidCheck长度
     * 使用8个字节存储随机校验数据
     */
    private static final int LEN_VC = 8;

    /**
     * 初始化第一页的原始数据
     * 
     * @return 初始化后的页面字节数组
     * 
     * 创建新数据库时调用，设置初始的ValidCheck标记
     */
    public static byte[] InitRaw() {
        byte[] raw = new byte[PageCache.PAGE_SIZE];
        setVcOpen(raw);
        return raw;
    }

    /**
     * 设置数据库开启状态的ValidCheck
     * 
     * @param pg 要设置的页面对象
     * 
     * 在数据库启动时调用，执行以下操作：
     * 1. 标记页面为脏页（需要写回磁盘）
     * 2. 在ValidCheck区域写入随机字节
     * 
     * 这相当于MySQL中事务开始时更新undo log header
     */
    public static void setVcOpen(Page pg) {
        pg.setDirty(true);
        setVcOpen(pg.getData());
    }

    /**
     * 在字节数组中设置开启状态的ValidCheck
     * 
     * @param raw 页面的原始字节数组
     * 
     * 实现细节：
     * - 生成8字节的随机数据
     * - 写入到100~107字节位置
     * - 这个随机数作为"活跃标记"
     */
    private static void setVcOpen(byte[] raw) {
        System.arraycopy(RandomUtil.randomBytes(LEN_VC), 0, raw, OF_VC, LEN_VC);
    }

    /**
     * 设置数据库关闭状态的ValidCheck
     * 
     * @param pg 要设置的页面对象
     * 
     * 在数据库正常关闭时调用，执行以下操作：
     * 1. 标记页面为脏页
     * 2. 复制ValidCheck数据，表示正常关闭
     * 
     * 这类似于MySQL正常关闭时的checkpoint操作
     */
    public static void setVcClose(Page pg) {
        pg.setDirty(true);
        setVcClose(pg.getData());
    }

    /**
     * 在字节数组中设置关闭状态的ValidCheck
     * 
     * @param raw 页面的原始字节数组
     * 
     * 实现细节：
     * - 将100~107字节的内容复制到108~115字节
     * - 这样两个区域的内容相同，表示正常关闭
     */
    private static void setVcClose(byte[] raw) {
        System.arraycopy(raw, OF_VC, raw, OF_VC+LEN_VC, LEN_VC);
    }

    /**
     * 检查数据库是否正常关闭
     * 
     * @param pg 要检查的页面对象
     * @return true表示正常关闭，false表示异常关闭
     * 
     * 用于数据库启动时的完整性检查
     */
    public static boolean checkVc(Page pg) {
        return checkVc(pg.getData());
    }

    /**
     * 在字节数组中检查ValidCheck
     * 
     * @param raw 页面的原始字节数组
     * @return true表示正常关闭，false表示异常关闭
     * 
     * 检查逻辑：
     * - 比较100~107字节和108~115字节的内容
     * - 相等：数据库正常关闭，无需恢复
     * - 不等：数据库异常关闭，需要执行崩溃恢复
     * 
     * 这是数据库启动时检测是否需要恢复的关键判断
     */
    private static boolean checkVc(byte[] raw) {
        return Arrays.equals(Arrays.copyOfRange(raw, OF_VC, OF_VC+LEN_VC), Arrays.copyOfRange(raw, OF_VC+LEN_VC, OF_VC+2*LEN_VC));
    }
}
