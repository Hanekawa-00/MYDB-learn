package top.guoziyang.mydb.backend.parser;

import java.util.ArrayList;
import java.util.List;

import top.guoziyang.mydb.backend.parser.statement.Abort;
import top.guoziyang.mydb.backend.parser.statement.Begin;
import top.guoziyang.mydb.backend.parser.statement.Commit;
import top.guoziyang.mydb.backend.parser.statement.Create;
import top.guoziyang.mydb.backend.parser.statement.Delete;
import top.guoziyang.mydb.backend.parser.statement.Drop;
import top.guoziyang.mydb.backend.parser.statement.Insert;
import top.guoziyang.mydb.backend.parser.statement.Select;
import top.guoziyang.mydb.backend.parser.statement.Show;
import top.guoziyang.mydb.backend.parser.statement.SingleExpression;
import top.guoziyang.mydb.backend.parser.statement.Update;
import top.guoziyang.mydb.backend.parser.statement.Where;
import top.guoziyang.mydb.common.Error;

/**
 * SQL语句解析器 - MYDB查询处理模块的核心组件
 *
 * 功能概述：
 * 该类是MYDB系统中SQL语句解析的入口点，负责将用户输入的SQL语句字节数组
 * 解析成相应的语句对象。类似于MySQL中的sql_parse.cc文件，但MYDB实现了
 * 一个简化版本的SQL解析器。
 *
 * 设计特点：
 * 1. 递归下降解析器设计：采用递归下降的解析方法，每个SQL语句类型对应一个解析方法
 * 2. 词法分析与语法分析分离：使用Tokenizer进行词法分析，Parser进行语法分析
 * 3. 支持基本SQL操作：BEGIN/COMMIT/ABORT事务控制，CREATE/DROP表操作，SELECT/INSERT/UPDATE/DELETE数据操作
 * 4. 简化的语法规则：相比MySQL，MYDB只支持基本的SQL语法，没有复杂的子查询、联合查询等
 *
 * 与MySQL对比：
 * - MySQL解析器：支持完整的SQL标准，包括复杂的语法结构、函数、存储过程等
 * - MYDB解析器：只支持基本的SQL操作，语法规则简化，适合学习和理解基本原理
 *
 * 解析流程：
 * 1. 使用Tokenizer将SQL语句分词
 * 2. 根据第一个关键字确定SQL语句类型
 * 3. 调用对应的解析方法生成语句对象
 * 4. 验证语句的完整性和正确性
 *
 * @author guoziyang
 * @see Tokenizer 词法分析器
 * @see top.guoziyang.mydb.backend.parser.statement 语句对象包
 */
public class Parser {
    /**
     * SQL语句解析的主入口方法
     *
     * 功能说明：
     * 将输入的SQL语句字节数组解析成对应的语句对象。这个方法是整个解析器的核心，
     * 采用了类似于MySQL parser的两阶段设计：词法分析 + 语法分析。
     *
     * 解析流程：
     * 1. 创建词法分析器(Tokenizer)对SQL语句进行分词
     * 2. 获取第一个关键字确定SQL语句类型
     * 3. 根据语句类型调用相应的解析方法
     * 4. 验证解析结果的完整性
     * 5. 返回解析后的语句对象
     *
     * 支持的SQL语句类型：
     * - 事务控制：BEGIN, COMMIT, ABORT
     * - 表操作：CREATE TABLE, DROP TABLE
     * - 数据操作：SELECT, INSERT, UPDATE, DELETE
     * - 元数据查询：SHOW
     *
     * 错误处理：
     * - 词法错误：由Tokenizer抛出
     * - 语法错误：由各个parse方法抛出
     * - 语句不完整：检查是否还有未处理的token
     *
     * 与MySQL对比：
     * MySQL的解析器更复杂，支持预处理语句、存储过程、视图等高级特性，
     * 而MYDB只支持最基本的SQL操作，便于理解核心原理。
     *
     * @param statement 待解析的SQL语句字节数组
     * @return 解析后的语句对象，具体类型取决于SQL语句类型
     * @throws Exception 解析过程中的各种异常
     *
     * 示例用法：
     * <pre>
     * byte[] sql = "SELECT * FROM users WHERE id = 1".getBytes();
     * Select selectStmt = (Select) Parser.Parse(sql);
     * </pre>
     */
    public static Object Parse(byte[] statement) throws Exception {
        // 创建词法分析器
        Tokenizer tokenizer = new Tokenizer(statement);
        
        // 获取第一个关键字，确定SQL语句类型
        String token = tokenizer.peek();
        tokenizer.pop();

        Object stat = null;
        Exception statErr = null;
        
        try {
            // 根据关键字分发到对应的解析方法
            switch(token) {
                case "begin":
                    stat = parseBegin(tokenizer);
                    break;
                case "commit":
                    stat = parseCommit(tokenizer);
                    break;
                case "abort":
                    stat = parseAbort(tokenizer);
                    break;
                case "create":
                    stat = parseCreate(tokenizer);
                    break;
                case "drop":
                    stat = parseDrop(tokenizer);
                    break;
                case "select":
                    stat = parseSelect(tokenizer);
                    break;
                case "insert":
                    stat = parseInsert(tokenizer);
                    break;
                case "delete":
                    stat = parseDelete(tokenizer);
                    break;
                case "update":
                    stat = parseUpdate(tokenizer);
                    break;
                case "show":
                    stat = parseShow(tokenizer);
                    break;
                default:
                    throw Error.InvalidCommandException;
            }
        } catch(Exception e) {
            statErr = e;
        }
        
        // 检查是否还有未处理的token，确保语句完整性
        try {
            String next = tokenizer.peek();
            if(!"".equals(next)) {
                byte[] errStat = tokenizer.errStat();
                statErr = new RuntimeException("Invalid statement: " + new String(errStat));
            }
        } catch(Exception e) {
            e.printStackTrace();
            byte[] errStat = tokenizer.errStat();
            statErr = new RuntimeException("Invalid statement: " + new String(errStat));
        }
        
        if(statErr != null) {
            throw statErr;
        }
        return stat;
    }

    private static Show parseShow(Tokenizer tokenizer) throws Exception {
        String tmp = tokenizer.peek();
        if("".equals(tmp)) {
            return new Show();
        }
        throw Error.InvalidCommandException;
    }

    private static Update parseUpdate(Tokenizer tokenizer) throws Exception {
        Update update = new Update();
        update.tableName = tokenizer.peek();
        tokenizer.pop();

        if(!"set".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        update.fieldName = tokenizer.peek();
        tokenizer.pop();

        if(!"=".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        update.value = tokenizer.peek();
        tokenizer.pop();

        String tmp = tokenizer.peek();
        if("".equals(tmp)) {
            update.where = null;
            return update;
        }

        update.where = parseWhere(tokenizer);
        return update;
    }

    private static Delete parseDelete(Tokenizer tokenizer) throws Exception {
        Delete delete = new Delete();

        if(!"from".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        String tableName = tokenizer.peek();
        if(!isName(tableName)) {
            throw Error.InvalidCommandException;
        }
        delete.tableName = tableName;
        tokenizer.pop();

        delete.where = parseWhere(tokenizer);
        return delete;
    }

    private static Insert parseInsert(Tokenizer tokenizer) throws Exception {
        Insert insert = new Insert();

        if(!"into".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        String tableName = tokenizer.peek();
        if(!isName(tableName)) {
            throw Error.InvalidCommandException;
        }
        insert.tableName = tableName;
        tokenizer.pop();

        if(!"values".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }

        List<String> values = new ArrayList<>();
        while(true) {
            tokenizer.pop();
            String value = tokenizer.peek();
            if("".equals(value)) {
                break;
            } else {
                values.add(value);
            }
        }
        insert.values = values.toArray(new String[values.size()]);

        return insert;
    }

    /**
     * 解析SELECT语句
     *
     * 支持的语法格式：
     * SELECT * FROM table_name [WHERE conditions]
     * SELECT field1, field2, ... FROM table_name [WHERE conditions]
     *
     * 解析过程：
     * 1. 解析字段列表：支持*通配符或具体字段名列表
     * 2. 解析FROM子句：获取表名
     * 3. 解析可选的WHERE子句：条件筛选
     *
     * 与MySQL对比：
     * - MySQL支持复杂的SELECT：JOIN、子查询、聚合函数、GROUP BY、ORDER BY等
     * - MYDB只支持基本的单表查询，适合理解查询处理的基本原理
     *
     * @param tokenizer 词法分析器
     * @return Select语句对象
     * @throws Exception 语法错误异常
     */
    private static Select parseSelect(Tokenizer tokenizer) throws Exception {
        Select read = new Select();

        // 解析字段列表
        List<String> fields = new ArrayList<>();
        String asterisk = tokenizer.peek();
        if("*".equals(asterisk)) {
            // 处理SELECT *的情况
            fields.add(asterisk);
            tokenizer.pop();
        } else {
            // 处理具体字段列表的情况
            while(true) {
                String field = tokenizer.peek();
                if(!isName(field)) {
                    throw Error.InvalidCommandException;
                }
                fields.add(field);
                tokenizer.pop();
                
                // 检查是否有更多字段（逗号分隔）
                if(",".equals(tokenizer.peek())) {
                    tokenizer.pop();
                } else {
                    break;
                }
            }
        }
        read.fields = fields.toArray(new String[fields.size()]);

        // 解析FROM关键字
        if(!"from".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        // 解析表名
        String tableName = tokenizer.peek();
        if(!isName(tableName)) {
            throw Error.InvalidCommandException;
        }
        read.tableName = tableName;
        tokenizer.pop();

        // 检查是否有WHERE子句
        String tmp = tokenizer.peek();
        if("".equals(tmp)) {
            // 没有WHERE子句
            read.where = null;
            return read;
        }

        // 解析WHERE子句
        read.where = parseWhere(tokenizer);
        return read;
    }

    private static Where parseWhere(Tokenizer tokenizer) throws Exception {
        Where where = new Where();

        if(!"where".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        SingleExpression exp1 = parseSingleExp(tokenizer);
        where.singleExp1 = exp1;

        String logicOp = tokenizer.peek();
        if("".equals(logicOp)) {
            where.logicOp = logicOp;
            return where;
        }
        if(!isLogicOp(logicOp)) {
            throw Error.InvalidCommandException;
        }
        where.logicOp = logicOp;
        tokenizer.pop();

        SingleExpression exp2 = parseSingleExp(tokenizer);
        where.singleExp2 = exp2;

        if(!"".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        return where;
    }

    private static SingleExpression parseSingleExp(Tokenizer tokenizer) throws Exception {
        SingleExpression exp = new SingleExpression();
        
        String field = tokenizer.peek();
        if(!isName(field)) {
            throw Error.InvalidCommandException;
        }
        exp.field = field;
        tokenizer.pop();

        String op = tokenizer.peek();
        if(!isCmpOp(op)) {
            throw Error.InvalidCommandException;
        }
        exp.compareOp = op;
        tokenizer.pop();

        exp.value = tokenizer.peek();
        tokenizer.pop();
        return exp;
    }

    private static boolean isCmpOp(String op) {
        return ("=".equals(op) || ">".equals(op) || "<".equals(op));
    }

    private static boolean isLogicOp(String op) {
        return ("and".equals(op) || "or".equals(op));
    }

    private static Drop parseDrop(Tokenizer tokenizer) throws Exception {
        if(!"table".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        String tableName = tokenizer.peek();
        if(!isName(tableName)) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        if(!"".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        
        Drop drop = new Drop();
        drop.tableName = tableName;
        return drop;
    }

    /**
     * 解析CREATE TABLE语句
     *
     * 支持的语法格式：
     * CREATE TABLE table_name field1 type1, field2 type2, ... (index field1, field2, ...)
     *
     * 语法特点：
     * 1. 必须指定表名
     * 2. 必须定义至少一个字段及其类型
     * 3. 必须指定索引字段（MYDB要求每个表都要有索引）
     * 4. 支持的数据类型：int32, int64, string
     *
     * 与MySQL对比：
     * - MySQL CREATE TABLE语法更复杂：支持约束、外键、分区、存储引擎等
     * - MYDB简化了语法：只支持基本字段定义和索引
     * - MySQL索引是可选的，MYDB要求必须有索引
     *
     * 解析流程：
     * 1. 验证TABLE关键字
     * 2. 解析表名
     * 3. 解析字段定义列表（字段名和类型）
     * 4. 解析索引定义（必须存在）
     *
     * @param tokenizer 词法分析器
     * @return Create语句对象
     * @throws Exception 语法错误或表没有索引异常
     */
    private static Create parseCreate(Tokenizer tokenizer) throws Exception {
        if(!"table".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        Create create = new Create();
        String name = tokenizer.peek();
        if(!isName(name)) {
            throw Error.InvalidCommandException;
        }
        create.tableName = name;

        List<String> fNames = new ArrayList<>();
        List<String> fTypes = new ArrayList<>();
        while(true) {
            tokenizer.pop();
            String field = tokenizer.peek();
            if("(".equals(field)) {
                break;
            }

            if(!isName(field)) {
                throw Error.InvalidCommandException;
            }

            tokenizer.pop();
            String fieldType = tokenizer.peek();
            if(!isType(fieldType)) {
                throw Error.InvalidCommandException;
            }
            fNames.add(field);
            fTypes.add(fieldType);
            tokenizer.pop();
            
            String next = tokenizer.peek();
            if(",".equals(next)) {
                continue;
            } else if("".equals(next)) {
                throw Error.TableNoIndexException;
            } else if("(".equals(next)) {
                break;
            } else {
                throw Error.InvalidCommandException;
            }
        }
        create.fieldName = fNames.toArray(new String[fNames.size()]);
        create.fieldType = fTypes.toArray(new String[fTypes.size()]);

        tokenizer.pop();
        if(!"index".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }

        List<String> indexes = new ArrayList<>();
        while(true) {
            tokenizer.pop();
            String field = tokenizer.peek();
            if(")".equals(field)) {
                break;
            }
            if(!isName(field)) {
                throw Error.InvalidCommandException;
            } else {
                indexes.add(field);
            }
        }
        create.index = indexes.toArray(new String[indexes.size()]);
        tokenizer.pop();

        if(!"".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        return create;
    }

    private static boolean isType(String tp) {
        return ("int32".equals(tp) || "int64".equals(tp) ||
        "string".equals(tp));
    }

    private static Abort parseAbort(Tokenizer tokenizer) throws Exception {
        if(!"".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        return new Abort();
    }

    private static Commit parseCommit(Tokenizer tokenizer) throws Exception {
        if(!"".equals(tokenizer.peek())) {
            throw Error.InvalidCommandException;
        }
        return new Commit();
    }

    private static Begin parseBegin(Tokenizer tokenizer) throws Exception {
        String isolation = tokenizer.peek();
        Begin begin = new Begin();
        if("".equals(isolation)) {
            return begin;
        }
        if(!"isolation".equals(isolation)) {
            throw Error.InvalidCommandException;
        }

        tokenizer.pop();
        String level = tokenizer.peek();
        if(!"level".equals(level)) {
            throw Error.InvalidCommandException;
        }
        tokenizer.pop();

        String tmp1 = tokenizer.peek();
        if("read".equals(tmp1)) {
            tokenizer.pop();
            String tmp2 = tokenizer.peek();
            if("committed".equals(tmp2)) {
                tokenizer.pop();
                if(!"".equals(tokenizer.peek())) {
                    throw Error.InvalidCommandException;
                }
                return begin;
            } else {
                throw Error.InvalidCommandException;
            }
        } else if("repeatable".equals(tmp1)) {
            tokenizer.pop();
            String tmp2 = tokenizer.peek();
            if("read".equals(tmp2)) {
                begin.isRepeatableRead = true;
                tokenizer.pop();
                if(!"".equals(tokenizer.peek())) {
                    throw Error.InvalidCommandException;
                }
                return begin;
            } else {
                throw Error.InvalidCommandException;
            }
        } else {
            throw Error.InvalidCommandException;
        }
    }

    private static boolean isName(String name) {
        return !(name.length() == 1 && !Tokenizer.isAlphaBeta(name.getBytes()[0]));
    }
}
