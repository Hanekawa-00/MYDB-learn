package top.guoziyang.mydb.backend.server;

import top.guoziyang.mydb.backend.parser.Parser;
import top.guoziyang.mydb.backend.parser.statement.Abort;
import top.guoziyang.mydb.backend.parser.statement.Begin;
import top.guoziyang.mydb.backend.parser.statement.Commit;
import top.guoziyang.mydb.backend.parser.statement.Create;
import top.guoziyang.mydb.backend.parser.statement.Delete;
import top.guoziyang.mydb.backend.parser.statement.Insert;
import top.guoziyang.mydb.backend.parser.statement.Select;
import top.guoziyang.mydb.backend.parser.statement.Show;
import top.guoziyang.mydb.backend.parser.statement.Update;
import top.guoziyang.mydb.backend.tbm.BeginRes;
import top.guoziyang.mydb.backend.tbm.TableManager;
import top.guoziyang.mydb.common.Error;

/**
 * SQL执行器 - 负责解析和执行SQL语句
 *
 * 功能概述：
 * - 管理单个客户端连接的事务状态
 * - 解析SQL语句并分发到相应的处理方法
 * - 自动管理临时事务的生命周期
 * - 处理事务控制语句（BEGIN、COMMIT、ABORT）
 * - 执行数据操作语句（SELECT、INSERT、UPDATE、DELETE等）
 *
 * 设计思想：
 * 类似于MySQL的SQL Layer中的语句执行器，但简化了很多特性：
 * - MySQL支持复杂的权限检查、查询优化、缓存等
 * - MYDB专注于基本的SQL解析和执行
 * - MySQL有更完善的事务隔离级别控制
 * - MYDB使用简化的事务管理机制
 *
 * 事务管理特点：
 * - 显式事务：客户端主动BEGIN/COMMIT/ABORT
 * - 隐式事务：对于非事务控制语句，自动创建临时事务
 * - 连接级事务状态：每个连接维护独立的事务上下文
 * - 异常处理：连接断开时自动回滚未提交事务
 *
 * @author guoziyang
 * @see Parser SQL解析器
 * @see TableManager 表管理器
 * @see HandleSocket 连接处理器
 */
public class Executor {
    /**
     * 当前事务ID
     * - 0表示没有活跃事务
     * - 非0表示当前连接正在执行的事务ID
     *
     * 与MySQL对比：
     * MySQL为每个连接维护更复杂的事务状态，包括：
     * - 自动提交模式（autocommit）
     * - 事务隔离级别
     * - 只读事务标记
     * - 事务启动时间戳
     * MYDB简化为只使用事务ID标识
     */
    private long xid;
    
    /**
     * 表管理器引用
     * 负责实际的SQL执行和事务管理
     */
    TableManager tbm;

    /**
     * 构造SQL执行器
     *
     * @param tbm 表管理器实例
     */
    public Executor(TableManager tbm) {
        this.tbm = tbm;
        this.xid = 0;  // 初始化时没有活跃事务
    }

    /**
     * 关闭执行器，清理资源
     *
     * 清理逻辑：
     * - 如果有未提交的事务，自动回滚
     * - 这通常在客户端连接异常断开时调用
     * - 确保不会留下悬挂的事务
     *
     * 与MySQL对比：
     * MySQL在连接断开时也会自动回滚未提交事务，
     * 但还会清理更多资源如临时表、预编译语句等。
     */
    public void close() {
        if(xid != 0) {
            System.out.println("Abnormal Abort: " + xid);
            tbm.abort(xid);
        }
    }

    /**
     * 执行SQL语句的主入口
     *
     * 执行流程：
     * 1. 解析SQL语句获得语句对象
     * 2. 根据语句类型进行分发处理
     * 3. 对于事务控制语句，直接处理
     * 4. 对于数据操作语句，调用execute2处理
     *
     * 事务控制语句处理：
     * - BEGIN：开始新事务（检查嵌套事务）
     * - COMMIT：提交当前事务
     * - ABORT：回滚当前事务
     *
     * @param sql SQL语句的字节数组形式
     * @return 执行结果的字节数组
     * @throws Exception SQL解析或执行异常
     */
    public byte[] execute(byte[] sql) throws Exception {
        System.out.println("Execute: " + new String(sql));
        
        // 使用Parser解析SQL语句
        Object stat = Parser.Parse(sql);
        
        // 处理BEGIN语句
        if(Begin.class.isInstance(stat)) {
            // 检查是否已经在事务中（不支持嵌套事务）
            if(xid != 0) {
                throw Error.NestedTransactionException;
            }
            
            // 开始新事务
            BeginRes r = tbm.begin((Begin)stat);
            xid = r.xid;  // 保存事务ID
            return r.result;
            
        // 处理COMMIT语句
        } else if(Commit.class.isInstance(stat)) {
            // 检查是否有活跃事务
            if(xid == 0) {
                throw Error.NoTransactionException;
            }
            
            // 提交事务
            byte[] res = tbm.commit(xid);
            xid = 0;  // 清除事务ID
            return res;
            
        // 处理ABORT语句
        } else if(Abort.class.isInstance(stat)) {
            // 检查是否有活跃事务
            if(xid == 0) {
                throw Error.NoTransactionException;
            }
            
            // 回滚事务
            byte[] res = tbm.abort(xid);
            xid = 0;  // 清除事务ID
            return res;
            
        // 处理其他数据操作语句
        } else {
            return execute2(stat);
        }
    }

    /**
     * 执行数据操作语句的内部方法
     *
     * 功能特点：
     * - 自动事务管理：如果没有活跃事务，创建临时事务
     * - 支持的操作：SHOW、CREATE、SELECT、INSERT、DELETE、UPDATE
     * - 异常处理：确保临时事务在异常时正确回滚
     *
     * 临时事务机制：
     * MySQL的autocommit模式类似，每个语句自动包装在事务中。
     * 区别在于MySQL可以配置autocommit，而MYDB总是自动管理。
     *
     * @param stat 已解析的语句对象
     * @return 执行结果的字节数组
     * @throws Exception SQL执行异常
     */
    private byte[] execute2(Object stat) throws Exception {
        boolean tmpTransaction = false;  // 标记是否创建了临时事务
        Exception e = null;
        
        // 如果没有活跃事务，创建临时事务
        if(xid == 0) {
            tmpTransaction = true;
            BeginRes r = tbm.begin(new Begin());
            xid = r.xid;
        }
        
        try {
            byte[] res = null;
            
            // 根据语句类型分发到相应的处理方法
            if(Show.class.isInstance(stat)) {
                // 显示所有表
                res = tbm.show(xid);
            } else if(Create.class.isInstance(stat)) {
                // 创建表
                res = tbm.create(xid, (Create)stat);
            } else if(Select.class.isInstance(stat)) {
                // 查询数据
                res = tbm.read(xid, (Select)stat);
            } else if(Insert.class.isInstance(stat)) {
                // 插入数据
                res = tbm.insert(xid, (Insert)stat);
            } else if(Delete.class.isInstance(stat)) {
                // 删除数据
                res = tbm.delete(xid, (Delete)stat);
            } else if(Update.class.isInstance(stat)) {
                // 更新数据
                res = tbm.update(xid, (Update)stat);
            }
            
            return res;
        } catch(Exception e1) {
            // 捕获异常，稍后在finally块中处理
            e = e1;
            throw e;
        } finally {
            // 处理临时事务的提交或回滚
            if(tmpTransaction) {
                if(e != null) {
                    // 有异常，回滚事务
                    tbm.abort(xid);
                } else {
                    // 没有异常，提交事务
                    tbm.commit(xid);
                }
                xid = 0;  // 清除事务ID
            }
        }
    }
}
