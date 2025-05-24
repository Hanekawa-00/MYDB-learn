package top.guoziyang.mydb.transport;

/**
 * 数据包装器 - 组合传输器和编码器，提供高级的Package传输接口
 *
 * 功能概述：
 * - 整合网络传输和数据编码功能
 * - 提供Package级别的发送和接收操作
 * - 隐藏底层字节数组处理细节
 * - 简化上层应用的网络通信代码
 *
 * 设计思想：
 * 实现Facade（外观）设计模式：
 * - 为复杂的传输+编码子系统提供简单接口
 * - 客户端无需了解编码和传输的具体实现
 * - 统一异常处理和资源管理
 * - 便于后续扩展协议功能
 *
 * 组件协作：
 * Package -> Encoder -> bytes -> Transporter -> Network
 * Network -> Transporter -> bytes -> Encoder -> Package
 *
 * 架构位置：
 * 在传输层架构中起到协调器作用：
 * - 向上：为Client/Server提供Package接口
 * - 向下：协调Encoder和Transporter的工作
 *
 * 与类似系统对比：
 * - HTTP客户端：类似于HttpClient，封装请求响应处理
 * - RPC框架：类似于Stub/Proxy，提供透明的远程调用
 * - MYDB：专注于Package的可靠传输
 *
 * @author guoziyang
 * @see Package 数据包封装
 * @see Encoder 数据编码器
 * @see Transporter 网络传输器
 */
public class Packager {
    /**
     * 网络传输器
     * 负责字节数组级别的网络通信
     */
    private Transporter transpoter;
    
    /**
     * 数据编码器
     * 负责Package与字节数组的转换
     */
    private Encoder encoder;

    /**
     * 构造数据包装器
     *
     * 组件依赖：
     * - Transporter：提供可靠的字节流传输
     * - Encoder：提供Package序列化能力
     * - 两个组件必须协同工作，确保数据完整性
     *
     * 初始化原则：
     * - 传输器应已建立连接
     * - 编码器应配置正确的协议格式
     * - 不进行额外的初始化，保持轻量级
     *
     * @param transpoter 网络传输器实例（注意原代码中的拼写错误）
     * @param encoder 数据编码器实例
     */
    public Packager(Transporter transpoter, Encoder encoder) {
        this.transpoter = transpoter;
        this.encoder = encoder;
    }

    /**
     * 发送Package数据包
     *
     * 发送流程：
     * 1. 使用Encoder将Package编码为字节数组
     * 2. 使用Transporter将字节数组发送到网络
     * 3. 等待发送操作完成
     *
     * 数据流转：
     * Package -> Encoder.encode() -> byte[] -> Transporter.send() -> Network
     *
     * 异常处理：
     * - 编码异常：Package格式错误或编码器故障
     * - 传输异常：网络连接断开或发送缓冲区满
     * - 所有异常都向上传播，由调用者处理
     *
     * 同步语义：
     * - 方法是同步的，发送完成后才返回
     * - 不保证对端已接收，只保证本地发送完成
     * - 网络延迟和缓冲可能影响实际传输时间
     *
     * @param pkg 要发送的Package数据包
     * @throws Exception 编码异常或网络传输异常
     */
    public void send(Package pkg) throws Exception {
        // 将Package编码为字节数组
        byte[] data = encoder.encode(pkg);
        
        // 通过网络传输器发送数据
        transpoter.send(data);
    }

    /**
     * 接收Package数据包
     *
     * 接收流程：
     * 1. 使用Transporter从网络接收字节数组
     * 2. 使用Encoder将字节数组解码为Package
     * 3. 返回解码后的Package对象
     *
     * 数据流转：
     * Network -> Transporter.receive() -> byte[] -> Encoder.decode() -> Package
     *
     * 阻塞行为：
     * - 方法会阻塞直到接收到完整的数据包
     * - 如果对端关闭连接，可能返回null或抛出异常
     * - 网络延迟会影响方法返回时间
     *
     * 异常处理：
     * - 传输异常：网络连接断开或接收超时
     * - 解码异常：数据格式错误或协议不匹配
     * - 连接关闭：Transporter检测到连接断开
     *
     * 数据完整性：
     * - 依赖Transporter保证数据完整性
     * - 依赖Encoder检查数据格式有效性
     * - 应用层可通过Package.getErr()检查业务错误
     *
     * @return 接收到的Package数据包，可能包含数据或错误信息
     * @throws Exception 网络传输异常或数据解码异常
     */
    public Package receive() throws Exception {
        // 从网络接收字节数据
        byte[] data = transpoter.receive();
        
        // 将字节数据解码为Package对象
        return encoder.decode(data);
    }

    /**
     * 关闭数据包装器，释放所有资源
     *
     * 清理范围：
     * - 关闭底层的网络传输器
     * - 释放网络连接和相关资源
     * - 清理缓冲区和状态信息
     *
     * 调用时机：
     * - 通信会话结束时
     * - 应用程序关闭前
     * - 发生不可恢复错误时
     * - 连接超时或异常时
     *
     * 资源管理：
     * - 确保Socket连接正确关闭
     * - 释放操作系统网络资源
     * - 避免端口和文件描述符泄漏
     *
     * 异常传播：
     * - 关闭操作可能失败，异常会向上传播
     * - 通常在finally块中调用，需要妥善处理异常
     * - 可以考虑记录日志而不是抛出异常
     *
     * @throws Exception 关闭网络资源时的异常
     */
    public void close() throws Exception {
        // 关闭底层传输器，释放网络连接
        transpoter.close();
    }
}
