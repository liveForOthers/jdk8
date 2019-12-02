package java.lang;
import java.lang.ref.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 *
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit reference to its copy of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 *
 * @author  Josh Bloch and Doug Lea
 * @since   1.2
 */
public class ThreadLocal<T> {
    /**
     * ThreadLocal 通过 threadLocalHashCode 来标识每一个 ThreadLocal 的唯一性。
     * threadLocalHashCode 通过 CAS 操作进行更新，
     * 每次 hash 操作的增量为 0x61c88647(这个数的原理没有探究)。
     */
    //
    private final int threadLocalHashCode = nextHashCode();

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     *
     * 下一次的 散列值 以0为起点
     */
    private static AtomicInteger nextHashCode =
            new AtomicInteger();

    /**
     * 连续生成的 散列码之间的差值
     * 对于2^n 容量 的数组 将隐式顺序 thread-local 的id转换为近似最优分布的乘法散列值
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * 返回下一个散列值
     */
    private static int nextHashCode() {
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     *
     * @return the initial value for this thread-local
     */
    protected T initialValue() {
        return null;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S> the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new SuppliedThreadLocal<>(supplier);
    }

    /**
     * Creates a thread local variable.
     * @see #withInitial(java.util.function.Supplier)
     *
     * 无参构造方法
     */
    public ThreadLocal() {
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     *
     * @return the current thread's value of this thread-local
     *
     * 返回当前线程 ThreadLocalMap 中存储的以当前 ThreadLocal 作为key 对应的value
     * 如 map为null  则执行初始化工作
     * 如 无此 ThreadLocal 对应的 value 返回initialValue 方法中的值
     *
     */
    public T get() {
        Thread t = Thread.currentThread();
        // 获得当前线程持有的 ThreadLocalMap
        ThreadLocalMap map = getMap(t);
        if (map != null) {
            // 获得本 ThreadLocal 实例为key的 Entry
            ThreadLocalMap.Entry e = map.getEntry(this);
            if (e != null) {
                @SuppressWarnings("unchecked")
                T result = (T)e.value;
                return result;
            }
        }
        // map 未初始化或 map中无 以本 ThreadLocal 对象 为key 的 节点
        // 执行初始化
        return setInitialValue();
    }

    /**
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     *
     * @return the initial value
     */
    private T setInitialValue() {
        // 获得初始值 默认实现为null
        T value = initialValue();
        Thread t = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        // map已经初始化 将初始值set进去
        if (map != null)
            map.set(this, value);
        else
            // 初始化map 并加入初始值
            createMap(t, value);
        return value;
    }

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * @param value the value to be stored in the current thread's copy of
     *        this thread-local.
     */
    public void set(T value) {
        // 获得当前线程
        Thread t = Thread.currentThread();
        // 拿到 当前线程持有的 ThreadLocalMap 对象
        ThreadLocalMap map = getMap(t);
        // ThreadLocalMap已经初始化过  ThreadLocalMap.set 方法
        if (map != null)
            map.set(this, value);
        // ThreadLocalMap未初始化 执行初始化 ThreadLocalMap
        else
            createMap(t, value);
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * @since 1.5
     */
    public void remove() {
        ThreadLocalMap m = getMap(Thread.currentThread());
        if (m != null)
            m.remove(this);
    }

    /**
     * 拿到当前线程的  ThreadLocal.ThreadLocalMap 对象
     */
    ThreadLocalMap getMap(Thread t) {
        return t.threadLocals;
    }

    /**
     * Create the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t the current thread
     * @param firstValue value for the initial entry of the map
     *
     * 初始化ThreadLocalMap
     */
    void createMap(Thread t, T firstValue) {
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    /**
     * Factory method to create map of inherited thread locals.
     * Designed to be called only from Thread constructor.
     *
     * @param  parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     */
    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * An extension of ThreadLocal that obtains its initial value from
     * the specified {@code Supplier}.
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap is a customized hash map suitable only for
     * maintaining thread local values. No operations are exported
     * outside of the ThreadLocal class. The class is package private to
     * allow declaration of fields in class Thread.  To help deal with
     * very large and long-lived usages, the hash table entries use
     * WeakReferences for keys. However, since reference queues are not
     * used, stale entries are guaranteed to be removed only when
     * the table starts running out of space.
     *
     * 静态内部类 线程私有的 ThreadLocalMap 的类
     */
    static class ThreadLocalMap {

        /**
         * The entries in this hash map extend WeakReference, using
         * its main ref field as the key (which is always a
         * ThreadLocal object).  Note that null keys (i.e. entry.get()
         * == null) mean that the key is no longer referenced, so the
         * entry can be expunged from table.  Such entries are referred to
         * as "stale entries" in the code that follows.
         */
        static class Entry extends WeakReference<ThreadLocal<?>> {
            /** The value associated with this ThreadLocal. */
            Object value;
            // 每个Entry对象都有一个ThreadLocal的弱引用（作为key），这是为了防止内存泄露。
            // 一旦线程结束，key变为一个不可达的对象，这个Entry就可以被GC了。
            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }

        /**
         * The initial capacity -- MUST be a power of two.
         *
         * ThreadLocalMap 的初始化容量 必须为 2^n
         * 1 可以使得hash发生冲突的次数减小
         * 2 获得当前key的index时 位运算效率更高
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * The table, resized as necessary.
         * table.length MUST always be a power of two.
         *
         * 底层数据结构使用数据进行实现  数组中的 Entry 对象仅存储 value  key 交给父类 Reference 存储
         */
        private Entry[] table;

        /**
         * The number of entries in the table.
         *
         * 数组中 已经占用的 容量
         */
        private int size = 0;

        /**
         * The next size value at which to resize.
         *
         * 扩容的阈值
         */
        private int threshold; // Default to 0

        /**
         * Set the resize threshold to maintain at worst a 2/3 load factor.
         *
         * 数组容量变更时 更新 扩容的阈值 为容量的 2/3
         */
        private void setThreshold(int len) {
            threshold = len * 2 / 3;
        }

        /**
         * Increment i modulo len.
         * ThreadLocalMap 解决冲突的方法是 线性探测法（不断加 1）
         */
        private static int nextIndex(int i, int len) {
            return ((i + 1 < len) ? i + 1 : 0);
        }

        /**
         * Decrement i modulo len.
         */
        private static int prevIndex(int i, int len) {
            return ((i - 1 >= 0) ? i - 1 : len - 1);
        }

        /**
         * Construct a new map initially containing (firstKey, firstValue).
         * ThreadLocalMaps are constructed lazily, so we only create
         * one when we have at least one entry to put in it.
         */
        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            // 初始化容器
            table = new Entry[INITIAL_CAPACITY];
            // 找到当前key的 桶位置
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            // 第一个参数就是本ThreadLocal实例(this)，第二个参数就是要保存的线程本地变量。
            table[i] = new Entry(firstKey, firstValue);
            size = 1;
            // 更新下次扩容的阈值
            setThreshold(INITIAL_CAPACITY);
        }

        /**
         * Construct a new map including all Inheritable ThreadLocals
         * from given parent map. Called only by createInheritedMap.
         *
         * @param parentMap the map associated with parent thread.
         */
        private ThreadLocalMap(ThreadLocalMap parentMap) {
            Entry[] parentTable = parentMap.table;
            int len = parentTable.length;
            setThreshold(len);
            table = new Entry[len];

            for (int j = 0; j < len; j++) {
                Entry e = parentTable[j];
                if (e != null) {
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
                    if (key != null) {
                        Object value = key.childValue(e.value);
                        Entry c = new Entry(key, value);
                        int h = key.threadLocalHashCode & (len - 1);
                        while (table[h] != null)
                            h = nextIndex(h, len);
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        /**
         * Get the entry associated with key.  This method
         * itself handles only the fast path: a direct hit of existing
         * key. It otherwise relays to getEntryAfterMiss.  This is
         * designed to maximize performance for direct hits, in part
         * by making this method readily inlinable.
         *
         * @param  key the thread local object
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntry(ThreadLocal<?> key) {
            int i = key.threadLocalHashCode & (table.length - 1);
            Entry e = table[i];
            // 命中 返回该节点
            if (e != null && e.get() == key)
                return e;
            else
                // 未命中 向后寻找 直到为null 返回null
                return getEntryAfterMiss(key, i, e);
        }

        /**
         * Version of getEntry method for use when key is not found in
         * its direct hash slot.
         *
         * @param  key the thread local object
         * @param  i the table index for key's hash code
         * @param  e the entry at table[i]
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            Entry[] tab = table;
            int len = tab.length;
            // 持续向下寻找 直到e为null
            while (e != null) {
                ThreadLocal<?> k = e.get();
                if (k == key)
                    return e;
                // 有key为null 但节点不为null 的节点 执行顺序清理
                if (k == null)
                    expungeStaleEntry(i);
                // 处理下一个位置的节点
                else
                    i = nextIndex(i, len);
                e = tab[i];
            }
            return null;
        }

        /**
         * Set the value associated with key.
         *
         * @param key the thread local object
         * @param value the value to be set
         */
        private void set(ThreadLocal<?> key, Object value) {

            // We don't use a fast path as with get() because it is at
            // least as common to use set() to create new entries as
            // it is to replace existing ones, in which case, a fast
            // path would fail more often than not.

            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);

            for (Entry e = tab[i];
                 // 当前节点为null 则直接跳出循环
                 e != null;
                 // 获得下一个遍历的 节点
                 e = tab[i = nextIndex(i, len)]) {
                // 获得当前节点的 key
                ThreadLocal<?> k = e.get();
                // 如果 ThreadLocal 为同一对象 即命中
                // 直接更新 value
                if (k == key) {
                    e.value = value;
                    return;
                }
                // 如果entry里对应的key为null的话，表明此entry为staled entry，原entry已被gc回收
                // 就将其替换为当前的key和value
                if (k == null) {
                    // 用当前 key value 设置在当前位置
                    replaceStaleEntry(key, value, i);
                    return;
                }
            }
            // 将新节点 设置在找到的 为空的节点上 因为ThreadLocalMap 是线程私有的 所以无线程安全问题
            tab[i] = new Entry(key, value);
            // 数组元素个数加1
            int sz = ++size;
            // 如果 没有废弃节点被回收 且 达到扩容阈值  执行扩容
            if (!cleanSomeSlots(i, sz) && sz >= threshold)
                // 执行
                rehash();
        }

        /**
         * Remove the entry for key.
         */
        private void remove(ThreadLocal<?> key) {
            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                if (e.get() == key) {
                    e.clear();
                    expungeStaleEntry(i);
                    return;
                }
            }
        }

        /**
         * Replace a stale entry encountered during a set operation
         * with an entry for the specified key.  The value passed in
         * the value parameter is stored in the entry, whether or not
         * an entry already exists for the specified key.
         *
         * As a side effect, this method expunges all stale entries in the
         * "run" containing the stale entry.  (A run is a sequence of entries
         * between two null slots.)
         *
         * @param  key the key
         * @param  value the value to be associated with key
         * @param  staleSlot index of the first stale entry encountered while
         *         searching for key.
         */
        private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                                       int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;
            Entry e;

            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).

            // 向前扫描，查找最前的一个无效slot
            int slotToExpunge = staleSlot;
            for (int i = prevIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = prevIndex(i, len))
                // key为null 则当前槽 节点无效
                if (e.get() == null)
                    slotToExpunge = i;

            // Find either the key or trailing null slot of run, whichever
            // occurs first

            // 向后遍历table
            for (int i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();

                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.

                // 找到了key，将其与无效的slot交换
                if (k == key) {
                    e.value = value;
                    // 交换 后 tab[i]就是无效节点了
                    tab[i] = tab[staleSlot];
                    tab[staleSlot] = e;

                    // Start expunge at preceding stale entry if it exists

                    /*
                     * 如果在整个扫描过程中（包括函数一开始的向前扫描与i之前的向后扫描）
                     * 找到了之前的无效slot则以那个位置作为清理的起点，
                     * 否则则以当前的i作为清理起点
                     */
                    if (slotToExpunge == staleSlot)
                        slotToExpunge = i;
                    // 从slotToExpunge开始做一次连续段的清理，再做一次启发式清理
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }

                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.

                // 如果当前的slot已经无效，并且向前扫描过程中没有无效slot，则更新slotToExpunge为当前位置
                if (k == null && slotToExpunge == staleSlot)
                    slotToExpunge = i;
            }

            // If key not found, put new entry in stale slot

            // 如果key在table中不存在，则在原地放一个即可
            tab[staleSlot].value = null;
            tab[staleSlot] = new Entry(key, value);

            // If there are any other stale entries in run, expunge them

            // 在探测过程中如果发现任何无效slot，则做一次清理（连续段清理+启发式清理）
            if (slotToExpunge != staleSlot)
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }

        /**
         * 这个函数是ThreadLocal中核心清理函数，它做的事情很简单：
         * 就是从staleSlot开始遍历，将无效（弱引用指向对象被回收）清理，
         * 即对应entry中的value置为null，将指向这个entry的table[i]置为null，直到扫到空entry。
         * 另外，在过程中还会对非空的entry作rehash。
         * 可以说这个函数的作用就是从staleSlot开始清理连续段中的slot（断开强引用，rehash slot等）
         */
        private int expungeStaleEntry(int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;

            // 因为entry对应的ThreadLocal已经被回收，value设为null，显式断开强引用
            tab[staleSlot].value = null;
            // 显式设置该entry为null，以便垃圾回收
            tab[staleSlot] = null;
            size--;

            // Rehash until we encounter null
            Entry e;
            int i;
            for (i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();
                // 清理对应ThreadLocal已经被回收的entry
                if (k == null) {
                    e.value = null;
                    tab[i] = null;
                    size--;
                /*
                 * 对于还没有被回收的情况，需要做一次rehash。
                 */
                } else {
                    int h = k.threadLocalHashCode & (len - 1);
                    // 如果对应的ThreadLocal的ID对len取模出来的索引h不为当前位置i
                    if (h != i) {
                        tab[i] = null;

                        /*
                         * 在原代码的这里有句注释值得一提，原注释如下：
                         *
                         * Unlike Knuth 6.4 Algorithm R, we must scan until
                         * null because multiple entries could have been stale.
                         *
                         * 这段话提及了Knuth高德纳的著作TAOCP（《计算机程序设计艺术》）的6.4章节（散列）
                         * 中的R算法。R算法描述了如何从使用线性探测的散列表中删除一个元素。
                         * R算法维护了一个上次删除元素的index，当在非空连续段中扫到某个entry的哈希值取模后的索引
                         * 还没有遍历到时，会将该entry挪到index那个位置，并更新当前位置为新的index，
                         * 继续向后扫描直到遇到空的entry。
                         *
                         * ThreadLocalMap因为使用了弱引用，所以其实每个slot的状态有三种也即
                         * 有效（value未回收），无效（value已回收），空（entry==null）。
                         * 正是因为ThreadLocalMap的entry有三种状态，所以不能完全套高德纳原书的R算法。
                         *
                         * 因为expungeStaleEntry函数在扫描过程中还会对无效slot清理将之转为空slot，
                         * 如果直接套用R算法，可能会出现具有相同哈希值的entry之间断开（中间有空entry）。
                         */

                        // 则从h向后线性探测到第一个空的slot
                        while (tab[h] != null)
                            h = nextIndex(h, len);
                        // 把当前的entry给挪过去。
                        tab[h] = e;
                    }
                }
            }
            // 返回staleSlot之后第一个空的slot索引
            return i;
        }

        /**
         * 启发式地清理slot,
         * i对应entry是非无效（指向的ThreadLocal没被回收，或者entry本身为空）
         * n是用于控制控制扫描次数的
         * 正常情况下如果log n次扫描没有发现无效slot，函数就结束了
         * 但是如果发现了无效的slot，将n置为table的长度len，做一次连续段的清理
         * 再从下一个空的slot开始继续扫描
         *
         * 这个函数有两处地方会被调用，
         * 1  插入的时候可能会被调用，
         * 2  替换无效slot的时候可能会被调用，
         * 区别是前者传入的n为元素个数，后者为table的容量
         */
        private boolean cleanSomeSlots(int i, int n) {
            boolean removed = false;
            Entry[] tab = table;
            int len = tab.length;
            do {
                i = nextIndex(i, len);
                Entry e = tab[i];
                // 一旦发现一个位置对应的 Entry 所持有的 ThreadLocal 弱引用为null，
                // 就会把此位置当做 staleSlot 并调用 expungeStaleEntry 方法进行整理 (rehashing) 的操作
                if (e != null && e.get() == null) {
                    // 发现了无效的slot，将n置为table的长度len，扩大扫描控制因子
                    n = len;
                    removed = true;
                    // 做一次连续段的清理
                    i = expungeStaleEntry(i);
                }
            // 实现 log n 次扫描 n每次缩小2倍
            } while ( (n >>>= 1) != 0);
            return removed;
        }

        /**
         * Re-pack and/or re-size the table. First scan the entire
         * table removing stale entries. If this doesn't sufficiently
         * shrink the size of the table, double the table size.
         */
        private void rehash() {
            // 做一次全量清理
            expungeStaleEntries();

            // Use lower threshold for doubling to avoid hysteresis
            /*
             * 因为做了一次清理，所以size很可能会变小。
             * ThreadLocalMap这里的实现是调低阈值来判断是否需要扩容，
             * threshold默认为len*2/3，所以这里的threshold - threshold / 4相当于len/2
             */
            if (size >= threshold - threshold / 4)
                // 执行扩容
                resize();
        }

        /**
         * Double the capacity of the table.
         */
        private void resize() {
            Entry[] oldTab = table;
            int oldLen = oldTab.length;
            int newLen = oldLen * 2;
            Entry[] newTab = new Entry[newLen];
            int count = 0;

            for (int j = 0; j < oldLen; ++j) {
                Entry e = oldTab[j];
                if (e != null) {
                    ThreadLocal<?> k = e.get();
                    if (k == null) {
                        e.value = null; // Help the GC
                    } else {
                        int h = k.threadLocalHashCode & (newLen - 1);
                        while (newTab[h] != null)
                            h = nextIndex(h, newLen);
                        newTab[h] = e;
                        count++;
                    }
                }
            }

            setThreshold(newLen);
            size = count;
            table = newTab;
        }

        /*
         * 做一次全量清理
         */
        private void expungeStaleEntries() {
            Entry[] tab = table;
            int len = tab.length;
            for (int j = 0; j < len; j++) {
                Entry e = tab[j];
                if (e != null && e.get() == null)
                    /*
                     * 个人觉得这里可以取返回值，如果大于j的话取了用，这样也是可行的。
                     * 因为expungeStaleEntry执行过程中是把连续段内所有无效slot都清理了一遍了。
                     */
                    expungeStaleEntry(j);
            }
        }
    }
}
