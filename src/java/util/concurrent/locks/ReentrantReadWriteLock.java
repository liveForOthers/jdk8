/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.Collection;

/**
 * @since 1.5
 * @author Doug Lea
 */
public class ReentrantReadWriteLock
        implements ReadWriteLock, java.io.Serializable {
    private static final long serialVersionUID = -6992448646407690164L;
    /** Inner class providing readlock */
    private final ReentrantReadWriteLock.ReadLock readerLock;
    /** Inner class providing writelock */
    private final ReentrantReadWriteLock.WriteLock writerLock;
    /** Performs all synchronization mechanics */
    final Sync sync;

    /**
     * Creates a new {@code ReentrantReadWriteLock} with
     * default (nonfair) ordering properties.
     * 构造方法 默认使用非公平锁
     */
    public ReentrantReadWriteLock() {
        this(false);
    }

    /**
     * Creates a new {@code ReentrantReadWriteLock} with
     * the given fairness policy.
     *
     * @param fair {@code true} if this lock should use a fair ordering policy
     *
     * 带参的构造方法 根据入参选择使用公平or非公平锁
     * 初始化sync、readerLock以及writerLock
     */
    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }
    /*获取读or写锁 实例的方法*/
    public ReentrantReadWriteLock.WriteLock writeLock() { return writerLock; }
    public ReentrantReadWriteLock.ReadLock  readLock()  { return readerLock; }

    /**
     * Synchronization implementation for ReentrantReadWriteLock.
     * Subclassed into fair and nonfair versions.
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 6317671515068378041L;

        /*
         * Read vs write count extraction constants and functions.
         * Lock state is logically divided into two unsigned shorts:
         * The lower one representing the exclusive (writer) lock hold count,
         * and the upper the shared (reader) hold count.
         */

        static final int SHARED_SHIFT   = 16;
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /** Returns the number of shared holds represented in count  */
        static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
        /** Returns the number of exclusive holds represented in count  */
        static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }

        /**
         * A counter for per-thread read hold counts.
         * Maintained as a ThreadLocal; cached in cachedHoldCounter
         */
        static final class HoldCounter {
            int count = 0;
            // Use id, not reference, to avoid garbage retention
            final long tid = getThreadId(Thread.currentThread());
        }

        /**
         * ThreadLocal subclass. Easiest to explicitly define for sake
         * of deserialization mechanics.
         */
        static final class ThreadLocalHoldCounter
            extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        /**
         * The number of reentrant read locks held by current thread.
         * Initialized only in constructor and readObject.
         * Removed whenever a thread's read hold count drops to 0.
         *
         * cachedHoldCounter 有什么用？其实没什么用，但能提升性能。
         * 将最后一次获取读锁的线程的 HoldCounter 缓存到这里，这样比使用 ThreadLocal 性能要好一些，
         * 因为 ThreadLocal 内部是基于 map 来查询的。
         * 但是 cachedHoldCounter 这一个属性毕竟只能缓存一个线程，
         * 所以它要起提升性能作用的依据就是：通常读锁的获取紧随着就是该读锁的释放。
         *
         */
        private transient ThreadLocalHoldCounter readHolds;

        /**
         * 将最后一次获取读锁的线程的 HoldCounter 缓存到这里，这样比使用 ThreadLocal 性能要好一些，
         * 所以它要起提升性能作用的依据就是：通常读锁的获取紧随着就是该读锁的释放。
         * 每个线程都需要维护自己的 HoldCounter，记录该线程获取的读锁次数，
         * 这样才能知道到底是不是读锁重入，用 ThreadLocal 属性 readHolds 维护
         */
        private transient HoldCounter cachedHoldCounter;

        /**
         * firstReader is the first thread to have acquired the read lock.
         * firstReaderHoldCount is firstReader's hold count.
         *
         * <p>More precisely, firstReader is the unique thread that last
         * changed the shared count from 0 to 1, and has not released the
         * read lock since then; null if there is no such thread.
         *
         * <p>Cannot cause garbage retention unless the thread terminated
         * without relinquishing its read locks, since tryReleaseShared
         * sets it to null.
         *
         * <p>Accessed via a benign data race; relies on the memory
         * model's out-of-thin-air guarantees for references.
         *
         * <p>This allows tracking of read holds for uncontended read
         * locks to be very cheap.
         */
        private transient Thread firstReader = null;
        private transient int firstReaderHoldCount;

        Sync() {
            // 初始化 ThreadLocalHoldCounter
            readHolds = new ThreadLocalHoldCounter();
            // 确保 readHolds 的可见性 为啥不直接用 volatile?
            setState(getState()); // ensures visibility of readHolds
        }

        /*
         * Acquires and releases use the same code for fair and
         * nonfair locks, but differ in whether/how they allow barging
         * when queues are non-empty.
         */

        /**
         * 如获得共享锁应当阻塞 返回true otherwise false
         * 共享锁应当阻塞场景：
         * 前提：非重入读锁 重入读锁无需阻塞
         * 1 公平锁 且队头有节点等待 2 非公平锁 但队头有等待独占锁的线程
         */
        abstract boolean readerShouldBlock();

        /**
         * 如获得独占锁应当阻塞 返回true otherwise false
         * 独占锁应当阻塞场景：
         * 前提：非重入锁 重入锁无需阻塞
         * 1 公平锁 且队头有节点等待
         */
        abstract boolean writerShouldBlock();


        // 释放锁，是线程安全的，因为写锁是独占锁，具有排他性
        // 实现很简单，state 减 releases
        protected final boolean tryRelease(int releases) {
            // 持有独占锁的线程 非本线程 抛出异常
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // state 经过本次释放之后的值
            int nextc = getState() - releases;
            // 判断独占锁释放后的 独占值
            boolean free = exclusiveCount(nextc) == 0;
            // 独占锁已释放完毕 清空独占锁占有线程 help gc
            if (free)
                setExclusiveOwnerThread(null);
            // 更新 锁值
            setState(nextc);
            // 返回独占锁是否已完全释放完毕
            return free;
        }
        /*
        * 重写父类tryAcquire方法 实现 尝试获取 独占锁 的功能
        * */
        protected final boolean tryAcquire(int acquires) {

            Thread current = Thread.currentThread();
            int c = getState();
            // 独占锁当前持有数目
            int w = exclusiveCount(c);
            // 已被锁定
            if (c != 0) {
                // (Note: if c != 0 and w == 0 then shared count != 0)
                // 如当前 共享锁已锁定 或者 独占锁已锁定 且当前线程非独占锁占有线程 返回false
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                // 如 独占锁已锁定 且 之前为当前线程锁定 执行重入锁功能
                // 如锁数目超过 2^16-1 抛出异常
                if (w + exclusiveCount(acquires) > MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // Reentrant acquire
                // 因为之前占有独占锁的为当前线程 所以直接set更新state字段 无线程安全问题
                setState(c + acquires);
                return true;
            }
            // 如当前未锁定
            // 根据公平锁/非公平锁策略 判断获取写锁 是否应阻塞
            // 1  阻塞直接返回失败
            // 2  不阻塞 尝试cas拿锁
            if (writerShouldBlock() ||
                !compareAndSetState(c, c + acquires))
                return false;
            // cas拿锁成功 设置 独占锁线程 为当前线程
            setExclusiveOwnerThread(current);
            return true;
        }

        /*
         * 重写父类 tryReleaseShared 方法 实现 尝试释放 共享锁
         * */
        protected final boolean tryReleaseShared(int unused) {
            // 获取当前线程 用于 根据线程 拿到该线程持有的锁数目
            Thread current = Thread.currentThread();
            /*
             * 当前线程 拿到共享锁 数目减1
             * */
            // 查看当前线程数目 是否缓存在 firstReader 中
            if (firstReader == current) {
                // assert firstReaderHoldCount > 0;
                if (firstReaderHoldCount == 1)
                    firstReader = null;
                else
                    firstReaderHoldCount--;
            } else {
                // 拿到 HoldCounter
                HoldCounter rh = cachedHoldCounter;
                // 查看 cachedHoldCounter 是否缓存的 当前线程
                if (rh == null || rh.tid != getThreadId(current))
                    // cachedHoldCounter 非缓存当前线程 从 threadLocal 中获取 HoldCounter
                    rh = readHolds.get();
                int count = rh.count;
                if (count <= 1) {
                    readHolds.remove();
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                --rh.count;
            }
            /*
             * cas 更新state值 直到成功
             * */
            for (;;) {
                int c = getState();
                int nextc = c - SHARED_UNIT;
                if (compareAndSetState(c, nextc))
                    // Releasing the read lock has no effect on readers,
                    // but it may allow waiting writers to proceed if
                    // both read and write locks are now free.
                    // 锁全部释放完毕 返回成功
                    return nextc == 0;
            }
        }

        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                "attempt to unlock read lock, not locked by current thread");
        }
        /*
        * 重写AQS 中方法  尝试获取共享锁
        *
        * */
        protected final int tryAcquireShared(int unused) {

            Thread current = Thread.currentThread();
            int c = getState();
            // 如果独占锁被持有 且 非 本线程持有 则返回失败
            if (exclusiveCount(c) != 0 &&
                getExclusiveOwnerThread() != current)
                return -1;
            // 获取 共享锁数目
            int r = sharedCount(c);
            // 如果 读不阻塞 且未达到最大锁持有数目  且 cas 成功
            if (!readerShouldBlock() &&
                r < MAX_COUNT &&
                compareAndSetState(c, c + SHARED_UNIT)) {
                // 如原 共享锁 数目为0
                if (r == 0) {
                    // 存储第一个获取读锁的线程 为当前线程
                    firstReader = current;
                    // 存储 第一个read持有数目为1
                    firstReaderHoldCount = 1;
                // 如 第一个 获得 共享锁的线程 为本线程
                } else if (firstReader == current) {
                    // 持有数目++
                    firstReaderHoldCount++;
                // 如 第一个获得共享锁线程 与本线程无关
                } else {
                    // 拿到HoldCounter 对象
                    // 前面我们说了 cachedHoldCounter 用于缓存最后一个获取读锁的线程
                    // 如果 cachedHoldCounter 缓存的不是当前线程，设置为缓存当前线程的 HoldCounter
                    HoldCounter rh = cachedHoldCounter;
                    // 如 HoldCounter未初始化  或 线程id 非本线程id
                    if (rh == null || rh.tid != getThreadId(current))
                        // 重新初始化 HoldCounter
                        cachedHoldCounter = rh = readHolds.get();
                    else if (rh.count == 0)
                        // 到这里，那么就是 cachedHoldCounter 缓存的是当前线程，但是 count 为 0，
                        // 大家可以思考一下：这里为什么要 set ThreadLocal 呢？(当然，答案肯定不在这块代码中)
                        //   既然 cachedHoldCounter 缓存的是当前线程，
                        //   当前线程肯定调用过 readHolds.get() 进行初始化 ThreadLocal
                        readHolds.set(rh);
                    // HoldCounter 持有锁数目++
                    rh.count++;
                }
                // 获取 共享锁 成功
                return 1;
            }
            // 如 获取共享锁应阻塞 或 共享锁数目超过最大数目  或cas失败 进入 fullTryAcquireShared
            return fullTryAcquireShared(current);
        }

        /**
         * 1\. 刚刚我们说了可能是因为 CAS 失败，如果就此返回，那么就要进入到阻塞队列了，
         *    想想有点不甘心，因为都已经满足了 !readerShouldBlock()，也就是说本来可以不用到阻塞队列的，
         *    所以进到这个方法其实是
         *
         *    ！！！！！增加 CAS 成功的机会！！！！！
         *
         * 2\. 在 NonFairSync 情况下，虽然 head.next 是获取写锁的，我知道它等待很久了，我没想和它抢，
         *    可是如果我是来
         *
         *    ！！！重入读锁！！！的，那么只能表示对不起了
         */
        final int fullTryAcquireShared(Thread current) {

            HoldCounter rh = null;
            // 失败重试
            for (;;) {
                int c = getState();
                // 独占锁已被获取
                if (exclusiveCount(c) != 0) {
                    // 独占锁线程非本线程  返回失败
                    if (getExclusiveOwnerThread() != current)
                        return -1;
                    // else we hold the exclusive lock; blocking here
                    // would cause deadlock.
                // 独占锁未被获取 再 二次确认 读应当阻塞  则处理 读锁重入
                } else if (readerShouldBlock()) {
                    // firstReader 为当前线程 说明 当前线程有读锁还未释放 完全释放后 会将firstReader置null
                    // 线程重入读锁，直接到下面的 CAS
                    if (firstReader == current) {
                        // assert firstReaderHoldCount > 0;
                    } else {
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) {
                                // 拿到线程私自持有的 共享锁获得次数保存对象
                                rh = readHolds.get();
                                if (rh.count == 0)
                                    // 如数目为0  直接remove  防止内存泄露 映射之前创建方法
                                    readHolds.remove();
                            }
                        }
                        // 之前未获得过共享锁 直接返回失败
                        if (rh.count == 0)
                            return -1;
                    }
                }
                // 超出 共享锁 最大值 抛出异常
                if (sharedCount(c) == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // cas 拿锁
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    // cas 成功 更新本线程 拿到共享锁的次数
                    if (sharedCount(c) == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    // 返回成功
                    return 1;
                }
                // cas 失败 for循环重试
            }
        }

        /**
         * Performs tryLock for write, enabling barging in both modes.
         * This is identical in effect to tryAcquire except for lack
         * of calls to writerShouldBlock.
         */
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            // 当前锁已被持有 执行尝试锁重入
            if (c != 0) {
                int w = exclusiveCount(c);
                // 共享锁被持有 或 （独占锁被持有且 非被当前线程持有） 返回失败
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                if (w == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
            }
            // 执行锁重入（锁重入无线程安全 或ABA问题 因为当前线程为已持有锁线程 不会释放锁  别的线程拿不到锁）
            // 或未被锁定 cas竞争锁
            if (!compareAndSetState(c, c + 1))
                return false;
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * Performs tryLock for read, enabling barging in both modes.
         * This is identical in effect to tryAcquireShared except for
         * lack of calls to readerShouldBlock.
         */
        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            for (;;) {
                int c = getState();
                if (exclusiveCount(c) != 0 &&
                    getExclusiveOwnerThread() != current)
                    return false;
                int r = sharedCount(c);
                if (r == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (r == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            cachedHoldCounter = rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
            }
        }

        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        // Methods relayed to outer class

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        final Thread getOwner() {
            // Must read state before owner to ensure memory consistency
            return ((exclusiveCount(getState()) == 0) ?
                    null :
                    getExclusiveOwnerThread());
        }

        final int getReadLockCount() {
            return sharedCount(getState());
        }

        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        final int getReadHoldCount() {
            if (getReadLockCount() == 0)
                return 0;

            Thread current = Thread.currentThread();
            if (firstReader == current)
                return firstReaderHoldCount;

            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return rh.count;

            int count = readHolds.get().count;
            if (count == 0) readHolds.remove();
            return count;
        }

        /**
         * Reconstitutes the instance from a stream (that is, deserializes it).
         */
        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            readHolds = new ThreadLocalHoldCounter();
            setState(0); // reset to unlocked state
        }

        final int getCount() { return getState(); }
    }

    /**
     * Nonfair version of Sync
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;
        final boolean writerShouldBlock() {
            // 非公平锁 写锁 直接cas竞争
            return false; // writers can always barge
        }
        final boolean readerShouldBlock() {
            /* As a heuristic to avoid indefinite writer starvation,
             * block if the thread that momentarily appears to be head
             * of queue, if one exists, is a waiting writer.  This is
             * only a probabilistic effect since a new reader will not
             * block if there is a waiting writer behind other enabled
             * readers that have not yet drained from the queue.
             */
            // 非公平锁 读锁
            // 如队列头结点 非独占锁  直接cas竞争
            // 如队列头结点 为独占锁  则放弃cas竞争 直接队列等待 防止独占锁饥饿
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    /**
     * Fair version of Sync
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;
        final boolean writerShouldBlock() {
            // 公平锁 如队列中有有效节点 返回true 获得写锁应当阻塞
            return hasQueuedPredecessors();
        }
        final boolean readerShouldBlock() {
            // 公平锁 如队列中有有效节点 返回true 获得读锁应当阻塞
            return hasQueuedPredecessors();
        }
    }

    /**
     * The lock returned by method {@link ReentrantReadWriteLock#readLock}.
     */
    public static class ReadLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -5992448646407690164L;
        /* 读写锁内部类实例初始化时均拿到 sync均初始化为父节点的sync 即竞争同一个state 实现读写互斥功能*/
        private final Sync sync;

        /**
         * Constructor for use by subclasses
         *
         * @param lock the outer lock object
         * @throws NullPointerException if the lock is null
         *
         * 静态内部类sync的实例初始化为读写锁的sync实例
         */
        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * 实现lock接口的lock方法
         * 阻塞获取读锁 调用AQS的acquireShared方法实现
         *
         * 主要实现在Sync的 tryAcquireShared 方法中
         */
        public void lock() {
            sync.acquireShared(1);
        }

        /**
         * 线程中断后抛出中断异常的 阻塞获取读锁
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        /**
         * 非阻塞 获取共享锁
         * 如果 独占锁被非本线程占有 或共享锁超过最大值 直接返回false
         * 否则 cas 尝试拿到共享锁 并记录本线程拿到的共享锁 数目 返回true
         */
        public boolean tryLock() {
            return sync.tryReadLock();
        }

        /**
         * 带有超时时间的 阻塞式 获取共享锁
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        /**
         * 释放共享锁
         */
        public void unlock() {
            sync.releaseShared(1);
        }

        /**
         * 共享锁 不支持 condition 方法 直接抛出异常
         */
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public String toString() {
            int r = sync.getReadLockCount();
            return super.toString() +
                "[Read locks = " + r + "]";
        }
    }

    /**
     * The lock returned by method {@link ReentrantReadWriteLock#writeLock}.
     */
    public static class WriteLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -4992448646407690164L;
        // 读写锁中共同持有的 Sync 实例
        private final Sync sync;

        protected WriteLock(ReentrantReadWriteLock lock) {
            // 共享锁 独占锁 的 sync 都初始化为 读写重入锁的共同 sync 实例
            sync = lock.sync;
        }

        /**
         * 调用AQS acquire方法 阻塞式获取 独占锁
         * 中断 调用Thread.currentThread().interrupt() 将当前线程中断（修改线程中断标识）
         */
        public void lock() {
            sync.acquire(1);
        }

        /**
         * 中断抛出中断异常的 阻塞式获得独占锁
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        /**
         * 仅1次尝试获得独占锁
         */
        public boolean tryLock( ) {
            return sync.tryWriteLock();
        }

        /**
         *
         * 带有超时时间的阻塞式获得锁
         * 在队列中阻塞式 仅阻塞剩余超时时间
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        /**
         *
         * 阻塞式 释放独占锁
         */
        public void unlock() {
            sync.release(1);
        }

        /**
         * 获取与当前写锁 绑定的 Condition 对象
         * 必须先拿到独占锁 才能操作 Condition中对应的方法
         */
        public Condition newCondition() {
            return sync.newCondition();
        }

        public String toString() {
            Thread o = sync.getOwner();
            return super.toString() + ((o == null) ?
                                       "[Unlocked]" :
                                       "[Locked by thread " + o.getName() + "]");
        }

        // 判断 独占锁是否被当前线程持有
        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        /**
         * Queries the number of holds on this write lock by the current
         * thread.  A thread has a hold on a lock for each lock action
         * that is not matched by an unlock action.  Identical in effect
         * to {@link ReentrantReadWriteLock#getWriteHoldCount}.
         *
         * @return the number of holds on this lock by the current thread,
         *         or zero if this lock is not held by the current thread
         * @since 1.6
         */
        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }

    // Instrumentation and status

    /**
     * Returns {@code true} if this lock has fairness set true.
     *
     * @return {@code true} if this lock has fairness set true
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * Returns the thread that currently owns the write lock, or
     * {@code null} if not owned. When this method is called by a
     * thread that is not the owner, the return value reflects a
     * best-effort approximation of current lock status. For example,
     * the owner may be momentarily {@code null} even if there are
     * threads trying to acquire the lock but have not yet done so.
     * This method is designed to facilitate construction of
     * subclasses that provide more extensive lock monitoring
     * facilities.
     *
     * @return the owner, or {@code null} if not owned
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    /**
     * Queries if the write lock is held by any thread. This method is
     * designed for use in monitoring system state, not for
     * synchronization control.
     *
     * @return {@code true} if any thread holds the write lock and
     *         {@code false} otherwise
     */
    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    /**
     * Queries if the write lock is held by the current thread.
     *
     * @return {@code true} if the current thread holds the write lock and
     *         {@code false} otherwise
     */
    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * Queries the number of reentrant write holds on this lock by the
     * current thread.  A writer thread has a hold on a lock for
     * each lock action that is not matched by an unlock action.
     *
     * @return the number of holds on the write lock by the current thread,
     *         or zero if the write lock is not held by the current thread
     */
    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    /**
     * Queries the number of reentrant read holds on this lock by the
     * current thread.  A reader thread has a hold on a lock for
     * each lock action that is not matched by an unlock action.
     *
     * @return the number of holds on the read lock by the current thread,
     *         or zero if the read lock is not held by the current thread
     * @since 1.6
     */
    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire the write lock.  Because the actual set of threads may
     * change dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive lock monitoring facilities.
     *
     * @return the collection of threads
     */
    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire the read lock.  Because the actual set of threads may
     * change dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive lock monitoring facilities.
     *
     * @return the collection of threads
     */
    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

    /**
     * Queries whether any threads are waiting to acquire the read or
     * write lock. Note that because cancellations may occur at any
     * time, a {@code true} return does not guarantee that any other
     * thread will ever acquire a lock.  This method is designed
     * primarily for use in monitoring of the system state.
     *
     * @return {@code true} if there may be other threads waiting to
     *         acquire the lock
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * Queries whether the given thread is waiting to acquire either
     * the read or write lock. Note that because cancellations may
     * occur at any time, a {@code true} return does not guarantee
     * that this thread will ever acquire a lock.  This method is
     * designed primarily for use in monitoring of the system state.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is queued waiting for this lock
     * @throws NullPointerException if the thread is null
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * Returns an estimate of the number of threads waiting to acquire
     * either the read or write lock.  The value is only an estimate
     * because the number of threads may change dynamically while this
     * method traverses internal data structures.  This method is
     * designed for use in monitoring of the system state, not for
     * synchronization control.
     *
     * @return the estimated number of threads waiting for this lock
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire either the read or write lock.  Because the actual set
     * of threads may change dynamically while constructing this
     * result, the returned collection is only a best-effort estimate.
     * The elements of the returned collection are in no particular
     * order.  This method is designed to facilitate construction of
     * subclasses that provide more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with the write lock. Note that because timeouts and
     * interrupts may occur at any time, a {@code true} return does
     * not guarantee that a future {@code signal} will awaken any
     * threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if this lock is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this lock
     * @throws NullPointerException if the condition is null
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with the write lock. Note that because
     * timeouts and interrupts may occur at any time, the estimate
     * serves only as an upper bound on the actual number of waiters.
     * This method is designed for use in monitoring of the system
     * state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if this lock is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this lock
     * @throws NullPointerException if the condition is null
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with the write lock.
     * Because the actual set of threads may change dynamically while
     * constructing this result, the returned collection is only a
     * best-effort estimate. The elements of the returned collection
     * are in no particular order.  This method is designed to
     * facilitate construction of subclasses that provide more
     * extensive condition monitoring facilities.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if this lock is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this lock
     * @throws NullPointerException if the condition is null
     */
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * Returns a string identifying this lock, as well as its lock state.
     * The state, in brackets, includes the String {@code "Write locks ="}
     * followed by the number of reentrantly held write locks, and the
     * String {@code "Read locks ="} followed by the number of held
     * read locks.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);

        return super.toString() +
            "[Write locks = " + w + ", Read locks = " + r + "]";
    }

    /**
     * Returns the thread id for the given thread.  We must access
     * this directly rather than via method Thread.getId() because
     * getId() is not final, and has been known to be overridden in
     * ways that do not preserve unique mappings.
     */
    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long TID_OFFSET;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            TID_OFFSET = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
