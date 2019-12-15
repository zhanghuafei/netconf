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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

 /**
 * Schedule the execution of across device transaction on the basis of contending for the device lock.
 *
 * TODO: 1、参数配置化 2、防止队列堰塞的限速策略：参考地铁站的限制容量策略，容量可以动态调整，决策基于吞吐量和等待时间 3、 去除对于AbstractMXBean的依赖
 *
 */
public class TransactionScheduler extends AbstractMXBean implements DeviceLockStatsMXBean, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TransactionScheduler.class);
    private static final String THREAD_NAME = "ac-device-trans-with-lock-thread";
    private static final String CALLBACK_THREAD_NAME = "ac-device-trans-callback-thread";
    private static final String NETCONF_METRIC_NAME = "netconf";
    private static final int DEFAULT_TASK_WAIT_TIMEOUT = 300000;
    private static final int DEFAULT_POSTPONE_TIME__MIN_MILLIS = 1000;
    private static final int DEFAULT_POSTPONE_TIME__MAX_MILLIS = 5000;

    private ExecutorService taskExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {

        private final AtomicLong threadNum = new AtomicLong();

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, String.format("%s-%d", THREAD_NAME, threadNum.incrementAndGet()));
        }
    });

    private ExecutorService taskCallbackExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicLong threadNum = new AtomicLong();

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, String.format("%s-%d", CALLBACK_THREAD_NAME, threadNum.incrementAndGet()));
        }
    });

    // transaction will not set failed instantly after timeout.
    // task total time = wait time + execute time;
    private int waitTimeout = DEFAULT_TASK_WAIT_TIMEOUT;

    // JMX
    // count of transaction which failed to acquire lock.
    private int failLockTransCount = 0;
    private AtomicLong totalSubmittedTransCount = new AtomicLong(0);
    private long totalPostponeTimes = 0;
    private boolean transDetailPrintSwitch = false;

    // METRIC
    private Timer timer;
    private JmxReporter reporter;

    // 事务队列
    private DelayQueue<DelayTask> taskQueue = new DelayQueue<>();

    // contested by taskExecutor and taskCallbackExecutor
    private Set<DeviceLock> deviceLocks = ConcurrentHashMap.newKeySet();

    private TransactionScheduler() {
        super("DeviceLockStats", "LocktransScheduler", null);
        initMetric();
    }

    private void initMetric() {
        MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(NETCONF_METRIC_NAME);
        timer = metricRegistry.timer(MetricRegistry.name("across-device-transaction"));
        reporter = JmxReporter.forRegistry(metricRegistry).inDomain("sal-netconf-connector").build();
        reporter.start();
    }

    public static TransactionScheduler create() {
        return new TransactionScheduler();
    }

    public FluentFuture<? extends CommitInfo> submit(BindingAcrossDeviceWriteTransaction wtx) {
        if (transDetailPrintSwitch) {
            LOG.debug("transaction {{}}: included operations is {}", wtx.getTransactionId(), wtx.getOperations());
        }

        Timer.Context context = timer.time();

        totalSubmittedTransCount.incrementAndGet();
        SettableFuture<CommitInfo> future = SettableFuture.create();
        DelayTask delayTask = new DelayTask(wtx, future);
        taskQueue.add(delayTask);

        Futures.addCallback(delayTask.future, new FutureCallback<CommitInfo>() {
            @Override
            public void onSuccess(@NullableDecl CommitInfo result) {
                context.stop();
            }

            @Override
            public void onFailure(Throwable t) {
                context.stop();
            }
        }, MoreExecutors.directExecutor());

        return delayTask.future;
    }

    /**
     * 检查相关网元是否可以申请锁
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
        return "Random [" + DEFAULT_POSTPONE_TIME__MIN_MILLIS + "," + DEFAULT_POSTPONE_TIME__MAX_MILLIS + "]";
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

    @Override
    public void close() throws Exception {
        unregister();
        reporter.close();
        SharedMetricRegistries.remove(NETCONF_METRIC_NAME);

    }

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
            return System.currentTimeMillis() - createTime;
        }

        private void postPone() {
            totalPostponeTimes++;
            postPoneTimes++;
            expectExecuteTime = expectExecuteTime + getRandomNumberInRange(DEFAULT_POSTPONE_TIME__MIN_MILLIS,
                    DEFAULT_POSTPONE_TIME__MAX_MILLIS);

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
                LOG.debug("transaction {{}}: acquire device lock sucessfully", wtx.getTransactionId());
                // 执行事务
                wtx.execute().addCallback(new FutureCallback<CommitInfo>() {
                    @Override
                    public void onSuccess(@NullableDecl CommitInfo result) {
                        future.set(result);
                        release(lockClaimed);
                        LOG.debug("transaction {{}}: release device lock successfully.", wtx.getTransactionId());
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        future.setException(t);
                        release(lockClaimed);
                        LOG.error("transaction {{}}: release device lock successfully.", wtx.getTransactionId());
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
        register();
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
    class DeviceLock {
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

}
