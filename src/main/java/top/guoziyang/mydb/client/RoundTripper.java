package top.guoziyang.mydb.client;

import top.guoziyang.mydb.transport.Package;
import top.guoziyang.mydb.transport.Packager;

/**
 * 往返通信处理器 - 实现客户端与服务器的请求-响应通信模式
 *
 * 功能概述：
 * - 封装同步请求-响应通信逻辑
 * - 提供简单的往返通信接口
 * - 管理数据包的发送和接收
 * - 隐藏底层网络通信细节
 *
 * 设计思想：
 * 实现经典的Request-Response通信模式：
 * - 类似于HTTP的请求-响应模型
 * - 每个请求都等待对应的响应
 * - 同步阻塞式通信，简化客户端编程
 * - 一对一的消息映射关系
 *
 * 与MySQL协议对比：
 * MySQL客户端协议支持：
 * - 流水线请求（pipeline）
 * - 异步响应处理
 * - 多结果集返回
 * - 长连接状态管理
 * MYDB采用最简化的同步模型，易于理解和实现
 *
 * 架构位置：
 * 在客户端架构中位于Client和Packager之间：
 * Client -> RoundTripper -> Packager -> Network
 *
 * @author guoziyang
 * @see Client 客户端核心类
 * @see Packager 数据包装器
 * @see Package 数据包封装
 */
public class RoundTripper {
    /**
     * 数据包装器引用
     * 负责具体的数据包发送和接收操作
     */
    private Packager packager;

    /**
     * 构造往返通信处理器
     *
     * @param packager 数据包装器实例，提供底层通信能力
     */
    public RoundTripper(Packager packager) {
        this.packager = packager;
    }

    /**
     * 执行一次完整的往返通信
     *
     * 通信流程：
     * 1. 发送请求包到服务器
     * 2. 阻塞等待服务器响应
     * 3. 接收并返回响应包
     *
     * 同步语义：
     * - 方法调用会阻塞直到收到响应
     * - 保证请求和响应的一一对应关系
     * - 不支持并发请求处理
     *
     * 异常处理：
     * - 网络发送异常：立即抛出，连接可能已断开
     * - 网络接收异常：立即抛出，可能是超时或连接断开
     * - 协议异常：由上层Client处理
     *
     * 与MySQL对比：
     * MySQL客户端可以：
     * - 发送多个请求后批量接收响应
     * - 处理服务器主动推送的消息
     * - 支持长时间运行的查询取消
     * MYDB简化为严格的一发一收模式
     *
     * @param pkg 要发送的请求数据包
     * @return 服务器返回的响应数据包
     * @throws Exception 网络通信异常或协议异常
     */
    public Package roundTrip(Package pkg) throws Exception {
        // 发送请求包到服务器
        packager.send(pkg);
        
        // 阻塞等待并接收响应包
        return packager.receive();
    }

    /**
     * 关闭往返通信处理器
     *
     * 清理逻辑：
     * - 关闭底层的数据包装器
     * - 释放网络连接资源
     * - 清理缓冲区和状态信息
     *
     * 调用时机：
     * - 客户端正常关闭时
     * - 连接异常需要清理时
     * - 应用程序退出前的资源清理
     *
     * 异常传播：
     * 此方法允许异常向上传播，调用者需要处理：
     * - 网络关闭异常
     * - 资源释放异常
     *
     * @throws Exception 关闭过程中的异常
     */
    public void close() throws Exception {
        packager.close();
    }
}
