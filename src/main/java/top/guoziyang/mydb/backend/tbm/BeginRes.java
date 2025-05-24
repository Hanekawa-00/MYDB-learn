package top.guoziyang.mydb.backend.tbm;

/**
 * BeginRes类 - Begin操作的返回结果
 * 
 * 这是一个简单的数据容器类，用于封装BEGIN事务操作的返回信息。
 * 在MYDB中，开始事务操作会返回事务ID和操作结果。
 * 
 * 与MySQL的对比：
 * - MySQL在BEGIN操作后，客户端会收到OK包，包含影响行数、警告等信息
 * - MYDB简化了这个过程，只返回事务ID和基本结果信息
 * 
 * 应用场景：
 * 1. 客户端执行BEGIN语句时的响应封装
 * 2. 事务开始后向客户端传递必要的事务信息
 */
public class BeginRes {
    
    /**
     * 事务ID
     * 
     * 新创建的事务的唯一标识符。
     * 对应MySQL中的事务ID概念，用于在整个事务生命周期中追踪事务。
     * 
     * 作用：
     * - 唯一标识一个事务
     * - 用于后续的COMMIT/ROLLBACK操作
     * - 在MVCC中用于版本控制和可见性判断
     */
    public long xid;
    
    /**
     * 操作结果数据
     * 
     * 包含BEGIN操作的执行结果，通常是成功消息或错误信息的字节数组。
     * 这个字段会被发送给客户端，告知操作是否成功。
     * 
     * 内容示例：
     * - 成功时：包含"begin"等成功提示信息
     * - 失败时：包含具体的错误信息
     */
    public byte[] result;
}
