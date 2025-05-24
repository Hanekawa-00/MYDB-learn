package top.guoziyang.mydb.backend.tbm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.primitives.Bytes;

import top.guoziyang.mydb.backend.parser.statement.Create;
import top.guoziyang.mydb.backend.parser.statement.Delete;
import top.guoziyang.mydb.backend.parser.statement.Insert;
import top.guoziyang.mydb.backend.parser.statement.Select;
import top.guoziyang.mydb.backend.parser.statement.Update;
import top.guoziyang.mydb.backend.parser.statement.Where;
import top.guoziyang.mydb.backend.tbm.Field.ParseValueRes;
import top.guoziyang.mydb.backend.tm.TransactionManagerImpl;
import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.backend.utils.ParseStringRes;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.common.Error;

/**
 * Table类 - 数据库表的核心实现
 * 
 * Table类代表数据库中的一个表，维护表的结构信息和提供所有表级别的操作。
 * 这是MYDB表管理模块的核心组件，负责协调字段管理、数据存储和查询处理。
 * 
 * 与MySQL的对比：
 * - MySQL中表的元信息存储在.frm文件和information_schema中
 * - MYDB将表信息直接存储在数据文件中，通过版本管理系统管理
 * 
 * 持久化格式：
 * [TableName][NextTable][Field1Uid][Field2Uid]...[FieldNUid]
 * - TableName: 表名称的字符串
 * - NextTable: 下一个表的UID（用于链表结构）
 * - FieldNUid: 各个字段的UID列表
 * 
 * 核心功能：
 * 1. 表结构管理（创建、加载、持久化）
 * 2. CRUD操作（增删改查）
 * 3. WHERE子句处理和索引查询
 * 4. 数据序列化和反序列化
 */
public class Table {
    
    /** 表管理器引用，用于访问底层存储和版本管理 */
    TableManager tbm;
    
    /** 表的唯一标识符 */
    long uid;
    
    /** 表名称 */
    String name;
    
    /** 表状态（预留字段，当前未使用） */
    byte status;
    
    /** 下一个表的UID，用于构建表的链表结构 */
    long nextUid;
    
    /** 表的字段列表，按照CREATE TABLE时的顺序存储 */
    List<Field> fields = new ArrayList<>();

    /**
     * 从存储中加载已存在的表
     * 
     * @param tbm 表管理器
     * @param uid 表的UID
     * @return 加载的Table对象
     * 
     * 加载过程：
     * 1. 从版本管理系统读取表的原始数据
     * 2. 解析表名、nextUid和字段UID列表
     * 3. 逐一加载每个字段对象
     * 
     * 对应MySQL中从.frm文件或数据字典加载表定义
     */
    public static Table loadTable(TableManager tbm, long uid) {
        byte[] raw = null;
        try {
            // 使用超级事务读取表元信息
            raw = ((TableManagerImpl)tbm).vm.read(TransactionManagerImpl.SUPER_XID, uid);
        } catch (Exception e) {
            Panic.panic(e);
        }
        assert raw != null;
        Table tb = new Table(tbm, uid);
        return tb.parseSelf(raw);
    }

    /**
     * 创建新表
     * 
     * @param tbm 表管理器
     * @param nextUid 下一个表的UID
     * @param xid 事务ID
     * @param create CREATE TABLE语句的解析结果
     * @return 新创建的Table对象
     * @throws Exception 创建失败
     * 
     * 创建过程：
     * 1. 创建Table对象并设置基本属性
     * 2. 根据CREATE语句创建所有字段
     * 3. 处理索引定义，为指定字段创建索引
     * 4. 持久化表信息到存储
     * 
     * 对应MySQL中的CREATE TABLE操作
     */
    public static Table createTable(TableManager tbm, long nextUid, long xid, Create create) throws Exception {
        Table tb = new Table(tbm, create.tableName, nextUid);
        
        // 创建所有字段
        for(int i = 0; i < create.fieldName.length; i ++) {
            String fieldName = create.fieldName[i];
            String fieldType = create.fieldType[i];
            
            // 检查该字段是否需要创建索引
            boolean indexed = false;
            for(int j = 0; j < create.index.length; j ++) {
                if(fieldName.equals(create.index[j])) {
                    indexed = true;
                    break;
                }
            }
            
            // 创建字段并添加到表中
            tb.fields.add(Field.createField(tb, xid, fieldName, fieldType, indexed));
        }

        // 持久化表信息
        return tb.persistSelf(xid);
    }

    /**
     * 构造函数 - 用于加载已存在的表
     * 
     * @param tbm 表管理器
     * @param uid 表UID
     */
    public Table(TableManager tbm, long uid) {
        this.tbm = tbm;
        this.uid = uid;
    }

    /**
     * 构造函数 - 用于创建新表
     * 
     * @param tbm 表管理器
     * @param tableName 表名称
     * @param nextUid 下一个表的UID
     */
    public Table(TableManager tbm, String tableName, long nextUid) {
        this.tbm = tbm;
        this.name = tableName;
        this.nextUid = nextUid;
    }

    /**
     * 解析表的原始数据
     * 
     * @param raw 从存储中读取的原始字节数组
     * @return 当前Table对象（链式调用）
     * 
     * 解析格式：[TableName][NextTable][Field1Uid][Field2Uid]...
     * 解析过程：
     * 1. 解析表名称字符串
     * 2. 解析下一个表的UID（8字节）
     * 3. 循环解析所有字段的UID并加载字段对象
     */
    private Table parseSelf(byte[] raw) {
        int position = 0;
        
        // 解析表名称
        ParseStringRes res = Parser.parseString(raw);
        name = res.str;
        position += res.next;
        
        // 解析下一个表的UID
        nextUid = Parser.parseLong(Arrays.copyOfRange(raw, position, position+8));
        position += 8;

        // 解析所有字段UID并加载字段
        while(position < raw.length) {
            long uid = Parser.parseLong(Arrays.copyOfRange(raw, position, position+8));
            position += 8;
            fields.add(Field.loadField(this, uid));
        }
        return this;
    }

    /**
     * 持久化表信息到存储
     * 
     * @param xid 事务ID
     * @return 当前Table对象
     * @throws Exception 持久化失败
     * 
     * 持久化过程：
     * 1. 序列化表名称
     * 2. 序列化nextUid
     * 3. 序列化所有字段的UID
     * 4. 通过版本管理系统插入到存储中
     */
    private Table persistSelf(long xid) throws Exception {
        byte[] nameRaw = Parser.string2Byte(name);
        byte[] nextRaw = Parser.long2Byte(nextUid);
        byte[] fieldRaw = new byte[0];
        
        // 序列化所有字段UID
        for(Field field : fields) {
            fieldRaw = Bytes.concat(fieldRaw, Parser.long2Byte(field.uid));
        }
        
        // 插入到存储并获得表的UID
        uid = ((TableManagerImpl)tbm).vm.insert(xid, Bytes.concat(nameRaw, nextRaw, fieldRaw));
        return this;
    }

    /**
     * 删除操作
     * 
     * @param xid 事务ID
     * @param delete DELETE语句的解析结果
     * @return 删除的记录数
     * @throws Exception 删除失败
     * 
     * 删除过程：
     * 1. 根据WHERE子句找到要删除的记录UID列表
     * 2. 逐一删除每条记录
     * 3. 返回实际删除的记录数
     * 
     * 对应MySQL中的DELETE操作
     * 注意：MYDB使用MVCC，删除是标记删除，不会真正删除数据
     */
    public int delete(long xid, Delete delete) throws Exception {
        List<Long> uids = parseWhere(delete.where);
        int count = 0;
        for (Long uid : uids) {
            if(((TableManagerImpl)tbm).vm.delete(xid, uid)) {
                count ++;
            }
        }
        return count;
    }

    /**
     * 更新操作
     * 
     * @param xid 事务ID
     * @param update UPDATE语句的解析结果
     * @return 更新的记录数
     * @throws Exception 更新失败
     * 
     * 更新过程：
     * 1. 根据WHERE子句找到要更新的记录
     * 2. 检查要更新的字段是否存在
     * 3. 对每条记录执行"删除-插入"操作（MVCC特性）
     * 4. 更新所有相关索引
     * 
     * 对应MySQL中的UPDATE操作
     * 注意：MYDB的更新是通过删除旧记录、插入新记录实现的
     */
    public int update(long xid, Update update) throws Exception {
        List<Long> uids = parseWhere(update.where);
        
        // 查找要更新的字段
        Field fd = null;
        for (Field f : fields) {
            if(f.fieldName.equals(update.fieldName)) {
                fd = f;
                break;
            }
        }
        if(fd == null) {
            throw Error.FieldNotFoundException;
        }
        
        Object value = fd.string2Value(update.value);
        int count = 0;
        
        for (Long uid : uids) {
            // 读取原记录
            byte[] raw = ((TableManagerImpl)tbm).vm.read(xid, uid);
            if(raw == null) continue;

            // 删除原记录
            ((TableManagerImpl)tbm).vm.delete(xid, uid);

            // 更新记录内容
            Map<String, Object> entry = parseEntry(raw);
            entry.put(fd.fieldName, value);
            raw = entry2Raw(entry);
            
            // 插入新记录
            long uuid = ((TableManagerImpl)tbm).vm.insert(xid, raw);
            count ++;

            // 更新所有索引
            for (Field field : fields) {
                if(field.isIndexed()) {
                    field.insert(entry.get(field.fieldName), uuid);
                }
            }
        }
        return count;
    }

    /**
     * 查询操作
     * 
     * @param xid 事务ID
     * @param read SELECT语句的解析结果
     * @return 查询结果的字符串表示
     * @throws Exception 查询失败
     * 
     * 查询过程：
     * 1. 根据WHERE子句找到满足条件的记录UID
     * 2. 读取每条记录的数据
     * 3. 解析记录内容并格式化输出
     * 
     * 对应MySQL中的SELECT操作
     */
    public String read(long xid, Select read) throws Exception {
        List<Long> uids = parseWhere(read.where);
        StringBuilder sb = new StringBuilder();
        
        for (Long uid : uids) {
            byte[] raw = ((TableManagerImpl)tbm).vm.read(xid, uid);
            if(raw == null) continue;
            
            Map<String, Object> entry = parseEntry(raw);
            sb.append(printEntry(entry)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 插入操作
     * 
     * @param xid 事务ID
     * @param insert INSERT语句的解析结果
     * @throws Exception 插入失败
     * 
     * 插入过程：
     * 1. 将字符串值转换为对应的Java对象
     * 2. 序列化记录为字节数组
     * 3. 通过版本管理系统插入记录
     * 4. 更新所有相关索引
     * 
     * 对应MySQL中的INSERT操作
     */
    public void insert(long xid, Insert insert) throws Exception {
        Map<String, Object> entry = string2Entry(insert.values);
        byte[] raw = entry2Raw(entry);
        long uid = ((TableManagerImpl)tbm).vm.insert(xid, raw);
        
        // 更新所有索引
        for (Field field : fields) {
            if(field.isIndexed()) {
                field.insert(entry.get(field.fieldName), uid);
            }
        }
    }

    /**
     * 将字符串数组转换为记录对象
     * 
     * @param values 字符串形式的字段值数组
     * @return 记录的Map表示
     * @throws Exception 值数量不匹配或类型转换失败
     * 
     * 转换过程：
     * 1. 检查值的数量是否与字段数量匹配
     * 2. 根据字段类型将每个字符串值转换为对应的Java对象
     * 3. 构建字段名到值的映射
     */
    private Map<String, Object> string2Entry(String[] values) throws Exception {
        if(values.length != fields.size()) {
            throw Error.InvalidValuesException;
        }
        
        Map<String, Object> entry = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            Object v = f.string2Value(values[i]);
            entry.put(f.fieldName, v);
        }
        return entry;
    }

    /**
     * 解析WHERE子句并返回满足条件的记录UID列表
     * 
     * @param where WHERE子句的解析结果
     * @return 满足条件的记录UID列表
     * @throws Exception 字段不存在、字段无索引或查询失败
     * 
     * 解析逻辑：
     * 1. 如果没有WHERE子句，扫描第一个有索引的字段的全部范围
     * 2. 如果有WHERE子句，检查涉及的字段是否有索引
     * 3. 根据逻辑操作符（AND/OR）计算查询范围
     * 4. 通过索引查询获得记录UID列表
     * 
     * 对应MySQL查询优化器中的索引选择和范围扫描
     */
    private List<Long> parseWhere(Where where) throws Exception {
        long l0=0, r0=0, l1=0, r1=0;
        boolean single = false;
        Field fd = null;
        
        if(where == null) {
            // 没有WHERE子句，全表扫描（通过第一个索引字段）
            for (Field field : fields) {
                if(field.isIndexed()) {
                    fd = field;
                    break;
                }
            }
            l0 = 0;
            r0 = Long.MAX_VALUE;
            single = true;
        } else {
            // 有WHERE子句，查找对应的索引字段
            for (Field field : fields) {
                if(field.fieldName.equals(where.singleExp1.field)) {
                    if(!field.isIndexed()) {
                        throw Error.FieldNotIndexedException;
                    }
                    fd = field;
                    break;
                }
            }
            if(fd == null) {
                throw Error.FieldNotFoundException;
            }
            
            // 计算WHERE条件的查询范围
            CalWhereRes res = calWhere(fd, where);
            l0 = res.l0; r0 = res.r0;
            l1 = res.l1; r1 = res.r1;
            single = res.single;
        }
        
        // 执行索引查询
        List<Long> uids = fd.search(l0, r0);
        if(!single) {
            // OR操作需要合并两个范围的结果
            List<Long> tmp = fd.search(l1, r1);
            uids.addAll(tmp);
        }
        return uids;
    }

    /**
     * WHERE计算结果的内部类
     * 
     * 用于存储WHERE子句计算后的查询范围信息
     */
    class CalWhereRes {
        /** 第一个范围的左边界 */
        long l0, r0;
        /** 第二个范围的左右边界（用于OR操作） */
        long l1, r1;
        /** 是否为单一范围查询 */
        boolean single;
    }

    /**
     * 计算WHERE子句的查询范围
     * 
     * @param fd 查询字段
     * @param where WHERE子句
     * @return 计算结果，包含查询范围信息
     * @throws Exception 逻辑操作符无效
     * 
     * 支持的逻辑操作：
     * - 无逻辑操作符：单一表达式
     * - OR：两个范围的并集
     * - AND：两个范围的交集
     * 
     * 这是查询优化的核心逻辑，类似MySQL中的范围分析
     */
    private CalWhereRes calWhere(Field fd, Where where) throws Exception {
        CalWhereRes res = new CalWhereRes();
        
        switch(where.logicOp) {
            case "":
                // 单一表达式
                res.single = true;
                FieldCalRes r = fd.calExp(where.singleExp1);
                res.l0 = r.left; res.r0 = r.right;
                break;
                
            case "or":
                // OR操作：需要查询两个范围
                res.single = false;
                r = fd.calExp(where.singleExp1);
                res.l0 = r.left; res.r0 = r.right;
                r = fd.calExp(where.singleExp2);
                res.l1 = r.left; res.r1 = r.right;
                break;
                
            case "and":
                // AND操作：计算两个范围的交集
                res.single = true;
                r = fd.calExp(where.singleExp1);
                res.l0 = r.left; res.r0 = r.right;
                r = fd.calExp(where.singleExp2);
                res.l1 = r.left; res.r1 = r.right;
                
                // 计算交集：取更大的左边界和更小的右边界
                if(res.l1 > res.l0) res.l0 = res.l1;
                if(res.r1 < res.r0) res.r0 = res.r1;
                break;
                
            default:
                throw Error.InvalidLogOpException;
        }
        return res;
    }

    /**
     * 格式化记录为可读字符串
     * 
     * @param entry 记录的Map表示
     * @return 格式化后的字符串
     * 
     * 输出格式：[value1, value2, value3]
     * 按照字段定义的顺序输出值
     */
    private String printEntry(Map<String, Object> entry) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            sb.append(field.printValue(entry.get(field.fieldName)));
            if(i == fields.size()-1) {
                sb.append("]");
            } else {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    /**
     * 从字节数组解析记录
     * 
     * @param raw 记录的原始字节数组
     * @return 记录的Map表示
     * 
     * 解析过程：
     * 1. 按照字段定义的顺序逐一解析每个字段值
     * 2. 根据字段类型确定每个值占用的字节数
     * 3. 构建字段名到值的映射
     */
    private Map<String, Object> parseEntry(byte[] raw) {
        int pos = 0;
        Map<String, Object> entry = new HashMap<>();
        
        for (Field field : fields) {
            ParseValueRes r = field.parserValue(Arrays.copyOfRange(raw, pos, raw.length));
            entry.put(field.fieldName, r.v);
            pos += r.shift;
        }
        return entry;
    }

    /**
     * 将记录序列化为字节数组
     * 
     * @param entry 记录的Map表示
     * @return 序列化后的字节数组
     * 
     * 序列化过程：
     * 1. 按照字段定义的顺序逐一序列化每个字段值
     * 2. 根据字段类型使用对应的序列化方法
     * 3. 连接所有字段的字节数组
     */
    private byte[] entry2Raw(Map<String, Object> entry) {
        byte[] raw = new byte[0];
        for (Field field : fields) {
            raw = Bytes.concat(raw, field.value2Raw(entry.get(field.fieldName)));
        }
        return raw;
    }

    /**
     * 表信息的字符串表示
     * 
     * @return 格式化的表信息字符串
     * 
     * 格式：{表名: (字段1), (字段2), ...}
     * 用于调试和信息展示
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        sb.append(name).append(": ");
        for(Field field : fields) {
            sb.append(field.toString());
            if(field == fields.get(fields.size()-1)) {
                sb.append("}");
            } else {
                sb.append(", ");
            }
        }
        return sb.toString();
    }
}
