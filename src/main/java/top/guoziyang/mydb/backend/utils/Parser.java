package top.guoziyang.mydb.backend.utils;

import java.nio.ByteBuffer;
import java.util.Arrays;

import com.google.common.primitives.Bytes;

/**
 * Parser - 数据类型转换工具类
 * 
 * 这个类提供了MYDB系统中各种基本数据类型与字节数组之间的转换功能。
 * 在数据库系统中，所有数据最终都需要以字节形式存储在磁盘上，因此
 * 类型转换是非常核心的功能。
 * 
 * 与MySQL的对比：
 * - MySQL中InnoDB存储引擎也有类似的类型转换机制
 * - MySQL使用固定字节序（通常是小端序）来存储数值类型
 * - InnoDB的页面格式中，各种数据类型都有严格的字节表示规范
 * - MYDB简化了这一过程，提供了基本的类型转换功能
 * 
 * 支持的数据类型转换：
 * 1. short ↔ byte[] （2字节）
 * 2. int ↔ byte[] （4字节）
 * 3. long ↔ byte[] （8字节）
 * 4. String ↔ byte[] （长度前缀 + 内容）
 * 5. String → UID（哈希值）
 * 
 * 设计特点：
 * - 使用Java的ByteBuffer确保字节序一致性
 * - 字符串存储采用长度前缀格式，便于解析
 * - 提供字符串到唯一标识符的哈希转换
 */
public class Parser {

    /**
     * 将short类型转换为字节数组
     * 
     * 使用Java的ByteBuffer来确保字节序的一致性。
     * short类型占用2个字节（16位）。
     * 
     * 与MySQL对比：
     * - MySQL的SMALLINT类型也是2字节
     * - InnoDB存储时使用大端序（Big-Endian）
     * - MYDB使用Java默认的大端序
     * 
     * @param value 要转换的short值
     * @return 包含2个字节的数组
     */
    public static byte[] short2Byte(short value) {
        return ByteBuffer.allocate(Short.SIZE / Byte.SIZE).putShort(value).array();
    }

    /**
     * 将字节数组转换为short类型
     * 
     * 从字节数组的前2个字节解析出short值。
     * 使用ByteBuffer确保与short2Byte方法的一致性。
     * 
     * @param buf 包含short数据的字节数组（至少2字节）
     * @return 解析出的short值
     */
    public static short parseShort(byte[] buf) {
        ByteBuffer buffer = ByteBuffer.wrap(buf, 0, 2);
        return buffer.getShort();
    }

    /**
     * 将int类型转换为字节数组
     * 
     * int类型占用4个字节（32位）。
     * 在数据库中常用于存储页号、记录ID等信息。
     * 
     * 与MySQL对比：
     * - MySQL的INT类型也是4字节
     * - 页号、表空间ID等都使用4字节整数
     * - InnoDB的B+树节点指针也使用4字节
     * 
     * @param value 要转换的int值
     * @return 包含4个字节的数组
     */
    public static byte[] int2Byte(int value) {
        return ByteBuffer.allocate(Integer.SIZE / Byte.SIZE).putInt(value).array();
    }

    /**
     * 将字节数组转换为int类型
     * 
     * 从字节数组的前4个字节解析出int值。
     * 
     * @param buf 包含int数据的字节数组（至少4字节）
     * @return 解析出的int值
     */
    public static int parseInt(byte[] buf) {
        ByteBuffer buffer = ByteBuffer.wrap(buf, 0, 4);
        return buffer.getInt();
    }

    /**
     * 将字节数组转换为long类型
     * 
     * 从字节数组的前8个字节解析出long值。
     * long类型占用8个字节（64位）。
     * 
     * 在数据库中的用途：
     * - 时间戳存储
     * - 大数值计算
     * - 唯一标识符（如事务ID、版本号）
     * 
     * @param buf 包含long数据的字节数组（至少8字节）
     * @return 解析出的long值
     */
    public static long parseLong(byte[] buf) {
        ByteBuffer buffer = ByteBuffer.wrap(buf, 0, 8);
        return buffer.getLong();
    }

    /**
     * 将long类型转换为字节数组
     * 
     * @param value 要转换的long值
     * @return 包含8个字节的数组
     */
    public static byte[] long2Byte(long value) {
        return ByteBuffer.allocate(Long.SIZE / Byte.SIZE).putLong(value).array();
    }

    /**
     * 从字节数组中解析字符串
     * 
     * 字符串的存储格式：[长度(4字节)][内容(长度字节)]
     * 这种格式类似于MySQL中VARCHAR类型的存储方式。
     * 
     * 与MySQL对比：
     * - MySQL的VARCHAR使用1-2字节存储长度前缀
     * - InnoDB行格式中，变长字段都有长度信息
     * - MYDB简化使用固定4字节长度前缀
     * 
     * 解析过程：
     * 1. 读取前4字节获取字符串长度
     * 2. 根据长度读取实际字符串内容
     * 3. 返回字符串和总消耗的字节数
     * 
     * @param raw 包含字符串数据的字节数组
     * @return ParseStringRes对象，包含解析的字符串和消耗的字节数
     */
    public static ParseStringRes parseString(byte[] raw) {
        // 解析字符串长度（前4字节）
        int length = parseInt(Arrays.copyOf(raw, 4));
        
        // 提取实际的字符串内容
        String str = new String(Arrays.copyOfRange(raw, 4, 4+length));
        
        // 返回字符串和总的字节消耗（长度字段4字节 + 内容字节）
        return new ParseStringRes(str, length+4);
    }

    /**
     * 将字符串转换为字节数组
     * 
     * 存储格式：[长度(4字节)][内容]
     * 这确保了字符串可以被正确解析，即使在包含特殊字符的情况下。
     * 
     * 转换过程：
     * 1. 将字符串长度转换为4字节数组
     * 2. 获取字符串的UTF-8字节表示
     * 3. 将长度和内容拼接成最终的字节数组
     * 
     * @param str 要转换的字符串
     * @return 包含长度前缀和字符串内容的字节数组
     */
    public static byte[] string2Byte(String str) {
        // 字符串长度的字节表示
        byte[] l = int2Byte(str.length());
        
        // 使用Google Guava的Bytes.concat进行高效的字节数组拼接
        return Bytes.concat(l, str.getBytes());
    }

    /**
     * 将字符串转换为唯一标识符（UID）
     * 
     * 使用简单的哈希算法将字符串转换为long类型的唯一标识符。
     * 这在数据库中常用于：
     * - 表名到表ID的映射
     * - 字段名到字段ID的映射
     * - 索引名到索引ID的映射
     * 
     * 哈希算法特点：
     * - 使用种子值13331（一个质数）
     * - 对字符串的每个字节进行累积计算
     * - 算法简单但分布相对均匀
     * 
     * 与MySQL对比：
     * - MySQL内部也使用哈希来快速定位对象
     * - InnoDB的哈希索引使用类似的概念
     * - 区别在于MySQL使用更复杂的哈希算法
     * 
     * 注意：这是一个简化的哈希实现，在生产环境中可能需要考虑：
     * - 哈希冲突的处理
     * - 更好的分布特性
     * - 加密安全性（如果需要）
     * 
     * @param key 要转换的字符串键
     * @return 对应的长整型唯一标识符
     */
    public static long str2Uid(String key) {
        long seed = 13331;  // 质数种子，用于减少哈希冲突
        long res = 0;
        
        // 对字符串的每个字节进行哈希计算
        for(byte b : key.getBytes()) {
            res = res * seed + (long)b;
        }
        
        return res;
    }

}
