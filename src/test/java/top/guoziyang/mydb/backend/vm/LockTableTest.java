package top.guoziyang.mydb.backend.vm;

import static org.junit.Assert.assertThrows;

import java.util.concurrent.locks.Lock;

import org.junit.Test;

import top.guoziyang.mydb.backend.utils.Panic;

public class LockTableTest {

    @Test
    public void testLockTable() {
        LockTable lt = new LockTable();
        try {
            lt.add(1, 1);
        } catch (Exception e) {
            Panic.panic(e);
        }

        try {
            lt.add(2, 2);
        } catch (Exception e) {
            Panic.panic(e);
        }

        try {
            // 这里事务2获取资源1的时候，资源1已经被事务1占用了
            // waitU.put(xid, uid) - 记录事务2正在等待资源1
            // putIntoList(wait, uid, xid) - 将事务2加入资源1的等待队列
            // 创建并返回一个等待锁
            lt.add(2, 1);
        } catch (Exception e) {
            Panic.panic(e);
        }

        assertThrows(RuntimeException.class, () -> lt.add(1, 2));
    }

    @Test
    public void testLockTable2() {
        LockTable lt = new LockTable();
        for (long i = 1; i <= 100; i++) {
            try {
                Lock o = lt.add(i, i);
                if (o != null) {
                    Runnable r = () -> {
                        o.lock();
                        o.unlock();
                    };
                    new Thread(r).run();
                }
            } catch (Exception e) {
                Panic.panic(e);
            }
        }

        for (long i = 1; i <= 99; i++) {
            try {
                Lock o = lt.add(i, i + 1);
                if (o != null) {
                    Runnable r = () -> {
                        o.lock();
                        o.unlock();
                    };
                    new Thread(r).run();
                }
            } catch (Exception e) {
                Panic.panic(e);
            }
        }

        assertThrows(RuntimeException.class, () -> lt.add(100, 1));
        lt.remove(23);

        try {
            lt.add(100, 1);
        } catch (Exception e) {
            Panic.panic(e);
        }
    }
}
