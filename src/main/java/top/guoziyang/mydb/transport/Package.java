package top.guoziyang.mydb.transport;

/**
 * 数据包封装类 - 统一的网络传输数据容器
 *
 * 功能概述：
 * - 封装客户端与服务器之间传输的数据
 * - 统一处理正常数据和异常信息
 * - 提供类型安全的数据访问接口
 * - 简化上层应用的错误处理逻辑
 *
 * 设计思想：
 * 实现Union类型模式（类似Rust的Result类型）：
 * - 一个Package要么包含有效数据，要么包含错误信息
 * - 避免同时存在数据和错误的模糊状态
 * - 强制上层代码进行错误检查
 * - 简化异常传播机制
 *
 * 使用模式：
 * 1. 成功情况：data != null, err == null
 * 2. 失败情况：data == null, err != null
 * 3. 请求包：data != null, err == null（客户端发送）
 * 4. 响应包：根据执行结果包含数据或错误
 *
 * 与其他系统对比：
 * - HTTP响应：通过状态码和响应体分别表示状态和数据
 * - RPC框架：通常有专门的错误字段和响应字段
 * - MYDB：简化为data/err二选一的模式
 *
 * @author guoziyang
 * @see Encoder 负责Package的序列化和反序列化
 * @see Packager 负责Package的网络传输
 */
public class Package {
    /**
     * 数据载荷
     * - 客户端请求：包含SQL语句的字节数组
     * - 服务器响应：包含查询结果的字节数组
     * - 错误情况：为null
     */
    byte[] data;
    
    /**
     * 错误信息
     * - 正常情况：为null
     * - 异常情况：包含具体的异常对象
     * - 用于在客户端和服务器之间传递错误信息
     */
    Exception err;

    /**
     * 构造数据包
     *
     * 参数约束：
     * - data和err不应同时为非null（虽然代码层面不强制）
     * - data和err可以同时为null（表示空响应）
     * - 通常按照成功/失败互斥的原则使用
     *
     * 典型用法：
     * - new Package(sqlBytes, null)：客户端请求
     * - new Package(resultBytes, null)：服务器成功响应
     * - new Package(null, exception)：服务器错误响应
     *
     * @param data 数据载荷，成功时的业务数据
     * @param err 错误信息，失败时的异常对象
     */
    public Package(byte[] data, Exception err) {
        this.data = data;
        this.err = err;
    }

    /**
     * 获取数据载荷
     *
     * 使用建议：
     * - 调用前应先检查getErr()是否为null
     * - 如果有错误，data通常为null或无意义
     * - 返回的字节数组可能为null，需要null检查
     *
     * 数据格式：
     * - 客户端->服务器：UTF-8编码的SQL字符串
     * - 服务器->客户端：查询结果的序列化数据
     *
     * @return 数据字节数组，可能为null
     */
    public byte[] getData() {
        return data;
    }

    /**
     * 获取错误信息
     *
     * 错误检查模式：
     * ```java
     * Package pkg = receive();
     * if (pkg.getErr() != null) {
     *     // 处理错误情况
     *     throw pkg.getErr();
     * } else {
     *     // 处理正常数据
     *     byte[] result = pkg.getData();
     * }
     * ```
     *
     * 错误类型：
     * - 网络传输错误
     * - SQL语法错误
     * - 数据库执行错误
     * - 系统内部错误
     *
     * @return 异常对象，正常情况下为null
     */
    public Exception getErr() {
        return err;
    }
}
