/*
 * Copyright (c) 2019 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal.isolation;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.*;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;
import org.opendaylight.controller.md.sal.common.util.jmx.AbstractMXBean;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.BindingAcrossDeviceWriteTransaction;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.BindingAcrossDeviceWriteTransaction.TxOperation;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Schedule the execution of across device transaction on the basis of contending for the device lock.
 * <p>
 * TODO: 去除对于AbstractMXBean的依赖
 *
 * @Author Huafei Zhang
 */
public class TransactionScheduler implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TransactionScheduler.class);
    private static final String THREAD_NAME = "ac-device-trans-with-lock-thread";
    private static final String CALLBACK_THREAD_NAME = "ac-device-trans-callback-thread";
    private static final String NETCONF_METRIC_NAME = "netconf";
    private static final int DEFAULT_TASK_WAIT_TIMEOUT = 300;
    private static final int DEFAULT_POSTPONE_TIME__MIN_MILLIS = 1000;
    private static final int DEFAULT_POSTPONE_TIME__MAX_MILLIS = 5000;
    int taskPostponeTimeMin = DEFAULT_POSTPONE_TIME__MIN_MILLIS;
    int taskPostponeTimeMax = DEFAULT_POSTPONE_TIME__MAX_MILLIS;

    // 假设吞吐量为3/s，按该队列容量以保证不会超时
    // 达到该容量限制，应大致保证该容量不会继续增长
    private static final int DEFAULT_TASK_CONGESTION_WATERMARK = DEFAULT_TASK_WAIT_TIMEOUT / 3;

    private ExecutorService taskExecutor = Executors.newSingleThreadExecutor(new TransactionThreadFactory(THREAD_NAME));

    private ExecutorService taskCallbackExecutor = Executors.newSingleThreadExecutor(new TransactionThreadFactory(CALLBACK_THREAD_NAME));

    // transaction will not set failed instantly after timeout.
    // task total time = wait time + execute time;
    private int waitTimeout = DEFAULT_TASK_WAIT_TIMEOUT;

    // JMX
    private int failLockTransCount = 0;
    private AtomicLong totalSubmittedTransCount = new AtomicLong(0);
    private long totalPostponeTimes = 0;
    private boolean transDetailPrintSwitch = false;
    private TransactionManagement transactionManagement;

    // METRIC
    private Timer timer;
    private JmxReporter reporter;

    // RATE LIMIT
    public static final int DEFAULT_TX_CREATION_INITIAL_RATE_LIMIT = 100;
    public static final int DEFAULT_RATE_LIMIT_STEP_SIZE = 25;
    private long transactionCreationInitialRateLimit = DEFAULT_TX_CREATION_INITIAL_RATE_LIMIT;
    private long taskCongestionWatermark = DEFAULT_TASK_CONGESTION_WATERMARK;
    private TransactionRateLimit rateLimit;

    // 事务队列
    private DelayQueue<DelayTask> taskQueue = new DelayQueue<>();

    // contested by taskExecutor and taskCallbackExecutor
    private Set<DeviceLock> deviceLocks = ConcurrentHashMap.newKeySet();

    public TransactionScheduler(int waitTimeout, long transactionCreationInitialRateLimit, long taskCongestionWatermark, int rateLimitStepSize, int taskPostponeTimeMin, int taskPostponeTimeMax) {
        this.waitTimeout = waitTimeout;
        this.transactionCreationInitialRateLimit = transactionCreationInitialRateLimit;
        this.taskCongestionWatermark = taskCongestionWatermark;
        rateLimit = new TransactionRateLimit(rateLimitStepSize);
        this.taskPostponeTimeMin = taskPostponeTimeMin;
        this.taskPostponeTimeMax = taskPostponeTimeMax;
        this.transactionManagement = new TransactionManagement("DeviceLockStats", "LocktransScheduler", null);
        initMetric();
    }

    public TransactionScheduler() {
        this.transactionManagement = new TransactionManagement("DeviceLockStats", "LocktransScheduler", null);
        initMetric();
    }

    private void initMetric() {
        MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(NETCONF_METRIC_NAME);
        timer = metricRegistry.timer(MetricRegistry.name("across-device-transaction"));
        reporter = JmxReporter.forRegistry(metricRegistry).inDomain("sal-netconf-connector").build();
        reporter.start();
    }

    public FluentFuture<? extends CommitInfo> submit(BindingAcrossDeviceWriteTransaction wtx) {
        if (transDetailPrintSwitch) {
            LOG.debug("transaction {{}}: included operations is {}", wtx.getTransactionId(), wtx.getOperations());
        }

        // 于限制到达率
        // WARN: 获取不到许可时，根据当前许可分配速度来计算沉睡时间
        // 调整速度后，加速的许可分配速度不对已沉睡的线程有影响。
        // 所以rate低的情况下，可能导致大量的线程沉睡很长的时间
        rateLimit.acquire();

        Timer.Context context = timer.time();

        totalSubmittedTransCount.incrementAndGet();
        SettableFuture<CommitInfo> future = SettableFuture.create();
        DelayTask delayTask = new DelayTask(wtx, future);
        taskQueue.add(delayTask);

        Futures.addCallback(delayTask.future, new FutureCallback<CommitInfo>() {
            @Override
            public void onSuccess(@NullableDecl CommitInfo result) {
                // record successful request only
                context.stop();
            }

            @Override
            public void onFailure(Throwable t) {
                LOG.error(t.getMessage(), t);
            }
        }, MoreExecutors.directExecutor());

        return delayTask.future;
    }

    /**
     * 检查相关网元是否可以申请锁
     *
     */
    private boolean isConflict(Set<?> lockClaimed) {
        return !Sets.intersection(deviceLocks, lockClaimed).isEmpty();
    }

    // 尝试获取网元锁，若冲突则直接返回
    private boolean tryAcquire(Set<DeviceLock> lockClaimed) {
        if (isConflict(lockClaimed)) {
            return false;
        }
        long currentTime = System.currentTimeMillis();
        lockClaimed.forEach(lock -> lock.setCreateTime(currentTime));
        deviceLocks.addAll(lockClaimed);
        return true;
    }

    private void release(Set<DeviceLock> lockClaimed) {
        deviceLocks.removeAll(lockClaimed);
    }

    @Override
    public void close() throws Exception {
        transactionManagement.unregister();
        reporter.close();
        SharedMetricRegistries.remove(NETCONF_METRIC_NAME);

    }

    /**
     * WARN
     * 该类的hashcode和equal涉及的字段范围不一样，若配合HashMap使用可能产生问题：
     * hashcode碰撞，使用equals判断。 而equals仅依赖expectExecuteTime
     * hashcode实际上在这里未使用到，仅是为了应付findbug的报告。
     */
    private class DelayTask implements Delayed {

        // 推迟次数
        private int postPoneTimes = 0;

        private BindingAcrossDeviceWriteTransaction wtx;
        private SettableFuture<CommitInfo> future;

        // 期望任务执行的时刻
        private long expectExecuteTime;
        private Set<DeviceLock> lockClaimed = new HashSet<>();

        // for timeout caculation
        private long createTime;

        DelayTask(BindingAcrossDeviceWriteTransaction wtx, SettableFuture<CommitInfo> future) {
            this.wtx = wtx;
            this.future = future;
            this.createTime = System.currentTimeMillis();
            this.expectExecuteTime = createTime;
            claimLocks();
        }

        private long waitTime() {
            return TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - createTime);
        }

        private void postPone() {
            totalPostponeTimes++;
            postPoneTimes++;
            expectExecuteTime = expectExecuteTime + getRandomNumberInRange(taskPostponeTimeMin,
                    taskPostponeTimeMax);
        }

        private void claimLocks() {
            List<TxOperation> operations = wtx.getOperations();
            operations.forEach(op ->
                    lockClaimed.add(new DeviceLock(op.getMountPointPath(), wtx.getTransactionId()))
            );
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long difference = expectExecuteTime - System.currentTimeMillis();
            return unit.convert(difference, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed another) {
            DelayTask anotherTask = (DelayTask) another;
            return Long.compare(this.expectExecuteTime, anotherTask.expectExecuteTime);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DelayTask delayTask = (DelayTask) o;

            if(this.compareTo(delayTask) == 0) {
                return true;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(postPoneTimes, wtx, future, expectExecuteTime, lockClaimed, createTime);
        }

        public void execute() {
            long waitTime = waitTime();
            if (waitTime > waitTimeout) {
                failLockTransCount++;
                future.setException(new TimeoutException("Failed to acquire locks till time out after " +
                        "postpone " + postPoneTimes +
                        " times"));
                return;
            }

            if (tryAcquire(lockClaimed)) {
                LOG.debug("transaction {{}}: acquire device lock {} successfully", wtx.getTransactionId(), lockClaimed);
                // 执行事务
                // 考虑再分配到新的线程池执行以降低concurrent-rpc-limit引发阻塞造成的影响？
                wtx.execute().addCallback(new FutureCallback<CommitInfo>() {
                    @Override
                    public void onSuccess(@NullableDecl CommitInfo result) {
                        future.set(result);
                        release(lockClaimed);
                        LOG.debug("transaction {{}}: successful and release device lock.", wtx.getTransactionId());
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        future.setException(t);
                        release(lockClaimed);
                        LOG.error("transaction {{}}: failed and release device lock.", wtx.getTransactionId(), t);
                    }
                }, taskCallbackExecutor);
                return;
            }

            // 简单处理： 冲突时，通过退避的方式来重新竞争资源
            // TODO 退避策略：指数or倍增
            postPone();
            taskQueue.offer(this);
        }

        private int getRandomNumberInRange(int min, int max) {
            Random r = new Random();
            return r.ints(min, (max + 1)).limit(1).findFirst().getAsInt();
        }
    }

    public void start() {
        // 注册MXBean
        transactionManagement.register();
        taskExecutor.submit(() -> {

                    while (true) {
                        DelayTask task = null;
                        try {
                            // block if no expired task
                            task = taskQueue.take();
                            task.execute();
                        } catch (Exception e) {
                            LOG.error("Transaction failed for some unexpected exception", e);
                            if (task != null && !task.future.isDone()) {
                                task.future.setException(e);
                            }
                        }
                    }
                }
        );
    }

    /**
     * like redis zset element
     */
    static class DeviceLock {
        private InstanceIdentifier<?> ii;

        // used for monitoring lock duration.
        private long createTime = 0;
        private long transactionId;

        DeviceLock(InstanceIdentifier<?> ii, long transactionId) {
            this.ii = ii;
            this.transactionId = transactionId;
        }

        void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        public long getActiveTimeSeconds() {
            return TimeUnit.SECONDS.convert(System.currentTimeMillis() - createTime, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            DeviceLock that = (DeviceLock) other;
            return Objects.equals(ii, that.ii);
        }

        @Override
        public int hashCode() {
            return ii.hashCode();
        }

        @Override
        public String toString() {
            String nodeId = ii.firstKeyOf(Node.class).getNodeId().getValue();
            return "DeviceLock{" +
                    "node-id=" + nodeId +
                    ", createTime=" + Instant.ofEpochMilli(createTime).plusMillis(TimeUnit.HOURS.toMillis(8)) +
                    ", transactionId=" + transactionId +
                    '}';
        }
    }


    class TransactionRateLimit {

        RateLimiter txRateLimiter = RateLimiter.create(transactionCreationInitialRateLimit);

        private volatile long pollOnCount = 1;
        private long stepSize;

        public TransactionRateLimit(long stepSize) {
            this.stepSize = stepSize;
        }

        void acquire() {
            adjustRate();
            txRateLimiter.acquire();
        }

        private void adjustRate() {
            // 超过水位，限制流入，且避免队伍中任务等待超时
            long submitted = totalSubmittedTransCount.get();
            // stepsize是为后续更灵活的rate变化作准备
            if (taskQueue.size() >= taskCongestionWatermark && submitted >= pollOnCount) {
                // 堵塞表明流量峰持续时间较长，所以假设取一分钟的平均吞吐量是合理的
                // 或者考虑折半递减方案
                double newRate = timer.getOneMinuteRate();
                txRateLimiter.setRate(newRate);
                // 按照新的速率持续运行stepsize数量的事务
                pollOnCount = stepSize + submitted;
                // 水位低于限制一半，则将限制放开
            } else if (taskQueue.size() < (taskCongestionWatermark / 2) && txRateLimiter.getRate() != transactionCreationInitialRateLimit) {
                // WARN: 限制的变化不平滑，可能导致吞吐量曲线毛刺
                txRateLimiter.setRate(transactionCreationInitialRateLimit);
            }
        }

        double getRateLimit() {
            return txRateLimiter.getRate();
        }
    }

    private static class TransactionThreadFactory implements ThreadFactory {
        private final AtomicLong threadNum = new AtomicLong();
        private String threadName;

        TransactionThreadFactory(String threadName) {
            this.threadName = threadName;
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, String.format("%s-%d", threadName, threadNum.incrementAndGet()));
        }
    }

    class TransactionManagement extends AbstractMXBean implements DeviceLockStatsMXBean {
        /**
         * Constructor.
         *
         * @param beanName     Used as the <code>name</code> property in the bean's ObjectName.
         * @param beanType     Used as the <code>type</code> property in the bean's ObjectName.
         * @param beanCategory Used as the <code>Category</code> property in the bean's ObjectName.
         */
        TransactionManagement(@Nonnull String beanName, @Nonnull String beanType, @Nullable String beanCategory) {
            super(beanName, beanType, beanCategory);
        }

        @Override
        public int getTotalLockedDeviceCount() {
            return deviceLocks.size();
        }

        @Override
        public int getTaskWaitTimeout() {
            return waitTimeout;
        }

        @Override
        public int getTaskQueueSize() {
            return taskQueue.size();
        }

        @Override
        public String getPostponeDelay() {
            return "Random [" + taskPostponeTimeMin + "," + taskPostponeTimeMax + "]";
        }

        @Override
        public int getFailLockTransCount() {
            return failLockTransCount;
        }

        @Override
        public long getTotalSubmittedTransCount() {
            return totalSubmittedTransCount.get();
        }

        @Override
        public long getTotalPostponeTimes() {
            return totalPostponeTimes;
        }

        @Override
        public long getTransactionCreationInitialRateLimit() {
            return transactionCreationInitialRateLimit;
        }

        @Override
        public double getCurrentRateLimit() {
            return rateLimit.getRateLimit();
        }

        @Override
        public long getTaskCongestionWatermark() {
            return taskCongestionWatermark;
        }

        @Override
        public void printDeviceLocks() {
            System.out.println(deviceLocks);
        }

        @Override
        public void clearAllLocks() {
            deviceLocks.clear();
        }

        @Override
        public void openTransDetailPrint() {
            transDetailPrintSwitch = true;
        }

        @Override
        public void closeTransDetailPrint() {
            transDetailPrintSwitch = false;
        }
    }

}
