package top.guoziyang.mydb.client;

import top.guoziyang.mydb.transport.Package;
import top.guoziyang.mydb.transport.Packager;

/**
 * MYDB客户端核心类 - 提供与服务器通信的简洁接口
 *
 * 功能概述：
 * - 封装与MYDB服务器的网络通信细节
 * - 提供简单的SQL执行接口
 * - 处理请求-响应的往返通信
 * - 管理客户端连接的生命周期
 *
 * 设计思想：
 * 类似于MySQL的客户端库（如mysql-connector-java），但极大简化：
 * - MySQL客户端支持连接池、SSL、字符集转换等复杂特性
 * - MYDB客户端专注于基本的SQL执行功能
 * - MySQL客户端有复杂的协议握手和认证流程
 * - MYDB采用简化的数据包通信协议
 *
 * 架构特点：
 * - 薄客户端设计：大部分逻辑在服务器端
 * - 同步通信：每次SQL执行都等待服务器响应
 * - 异常透明：将服务器异常传播给客户端应用
 * - 资源管理：自动处理连接清理
 *
 * @author guoziyang
 * @see RoundTripper 往返通信处理器
 * @see Packager 数据包装器
 * @see Package 数据包封装
 */
public class Client {
    /**
     * 往返通信处理器
     * 负责处理请求-响应的网络通信细节
     */
    private RoundTripper rt;

    /**
     * 构造客户端实例
     *
     * @param packager 数据包装器，封装了网络传输和数据编码功能
     */
    public Client(Packager packager) {
        this.rt = new RoundTripper(packager);
    }

    /**
     * 执行SQL语句
     *
     * 执行流程：
     * 1. 将SQL语句包装成数据包
     * 2. 通过RoundTripper发送给服务器
     * 3. 等待服务器响应
     * 4. 检查响应中的错误信息
     * 5. 返回执行结果或抛出异常
     *
     * 通信协议：
     * - 请求：包含SQL字节数组，无错误信息
     * - 响应：包含结果字节数组或错误信息
     * - 同步模式：发送后阻塞等待响应
     *
     * 与MySQL客户端对比：
     * MySQL客户端协议更复杂，包括：
     * - 多种数据类型的编码
     * - 预编译语句支持
     * - 批量操作优化
     * - 流式结果集处理
     * MYDB简化为字节数组的简单交换
     *
     * @param stat SQL语句的字节数组形式
     * @return 服务器返回的执行结果字节数组
     * @throws Exception 网络异常或SQL执行异常
     */
    public byte[] execute(byte[] stat) throws Exception {
        // 创建请求数据包，包含SQL语句，无错误信息
        Package pkg = new Package(stat, null);
        
        // 执行往返通信，发送请求并等待响应
        Package resPkg = rt.roundTrip(pkg);
        
        // 检查服务器是否返回了错误
        if(resPkg.getErr() != null) {
            // 将服务器异常传播给客户端
            throw resPkg.getErr();
        }
        
        // 返回执行结果数据
        return resPkg.getData();
    }

    /**
     * 关闭客户端连接
     *
     * 清理逻辑：
     * - 关闭往返通信处理器
     * - 释放网络连接资源
     * - 忽略关闭过程中的异常
     *
     * 异常处理：
     * 关闭操作通常在finally块或资源清理时调用，
     * 此时抛出异常可能会掩盖原始异常，所以选择静默处理。
     *
     * 与MySQL客户端对比：
     * MySQL客户端关闭时还需要：
     * - 回滚未提交事务
     * - 清理预编译语句
     * - 释放连接池资源
     * - 发送连接关闭协议包
     * MYDB简化为直接关闭网络连接
     */
    public void close() {
        try {
            rt.close();
        } catch (Exception e) {
            // 静默处理关闭异常
            // 避免在资源清理时抛出异常
        }
    }
}
