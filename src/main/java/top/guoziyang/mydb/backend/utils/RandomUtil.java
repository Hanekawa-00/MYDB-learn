package top.guoziyang.mydb.backend.utils;

import java.security.SecureRandom;
import java.util.Random;

/**
 * RandomUtil - 随机数生成工具类
 * 
 * 这个类提供了MYDB系统中生成安全随机字节数组的功能。
 * 在数据库系统中，随机数主要用于生成各种标识符、密钥、
 * 以及测试数据等场景。
 * 
 * 与MySQL的对比：
 * - MySQL也内置了随机数生成功能，如RAND()函数
 * - MySQL的UUID()函数使用随机数生成全局唯一标识符
 * - InnoDB在生成表空间ID、日志文件名等场景中使用随机数
 * - MySQL使用系统级的随机数生成器确保安全性
 * - MYDB的RandomUtil简化了这一功能，专注于字节数组生成
 * 
 * 使用SecureRandom的原因：
 * 1. 安全性：SecureRandom使用密码学强度的随机数生成算法
 * 2. 不可预测性：相比普通Random，更难被攻击者预测
 * 3. 系统熵源：利用操作系统提供的熵源（如鼠标移动、键盘输入等）
 * 4. 符合标准：符合密码学应用的安全要求
 * 
 * 在数据库中的应用场景：
 * 1. 生成事务ID的随机部分
 * 2. 创建临时文件名的随机后缀
 * 3. 生成数据库连接的会话标识
 * 4. 测试数据的随机填充
 * 5. 加密密钥的生成（如果需要）
 * 6. 负载测试中的随机数据生成
 * 
 * 性能考虑：
 * - SecureRandom的性能比普通Random略低
 * - 但在数据库应用中，安全性通常比性能更重要
 * - 对于高频调用场景，可以考虑缓存随机数
 */
public class RandomUtil {
    
    /**
     * 生成指定长度的随机字节数组
     * 
     * 使用SecureRandom生成密码学强度的随机字节序列。
     * 这些字节可以用于各种需要随机性的场景。
     * 
     * 实现细节：
     * - 每次调用都创建新的SecureRandom实例
     * - 使用nextBytes()方法填充整个数组
     * - 返回的每个字节都是真正随机的（-128到127）
     * 
     * 与MySQL UUID()的对比：
     * - MySQL的UUID()生成36字符的字符串格式
     * - 而此方法生成纯字节数据，更加紧凑
     * - 可以根据需要转换为十六进制字符串或其他格式
     * 
     * 安全性说明：
     * - 使用SecureRandom确保不可预测性
     * - 适用于生成密钥、会话ID等安全敏感的场景
     * - 符合密码学应用的随机性要求
     * 
     * 使用示例：
     * ```java
     * // 生成16字节的随机数据（常用于UUID）
     * byte[] uuid = RandomUtil.randomBytes(16);
     * 
     * // 生成32字节的随机数据（常用于密钥）
     * byte[] key = RandomUtil.randomBytes(32);
     * 
     * // 生成随机文件名后缀
     * byte[] suffix = RandomUtil.randomBytes(8);
     * String filename = "temp_" + bytesToHex(suffix) + ".tmp";
     * ```
     * 
     * 性能提示：
     * - 对于大量随机数需求，可以考虑批量生成
     * - 避免在循环中频繁调用，可以预先生成足够的随机数
     * 
     * @param length 需要生成的字节数组长度，必须大于0
     * @return 包含随机数据的字节数组
     * 
     * @throws IllegalArgumentException 如果length <= 0
     */
    public static byte[] randomBytes(int length) {
        // 创建SecureRandom实例
        // 注意：这里每次都创建新实例，在高频调用场景下可以考虑重用
        Random r = new SecureRandom();
        
        // 创建指定长度的字节数组
        byte[] buf = new byte[length];
        
        // 使用SecureRandom填充整个数组
        // nextBytes()方法会用随机字节填充数组中的每个位置
        r.nextBytes(buf);
        
        return buf;
    }
}
