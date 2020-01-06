/*
 * Copyright (c) 2019 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal.isolation;

/**
 * 管理网元锁及事务信息的MXBean接口
 *
 * @Author Huafei Zhang
 */
public interface DeviceLockStatsMXBean {

    /**
     * 当前被锁住的所有网元数量
     */
    int getTotalLockedDeviceCount();

    /**
     * 任务（事务）允许在队列中等待的时间
     */
    int getTaskWaitTimeout();

    /**
     * 当前任务（事务）队列的长度
     */
    int getTaskQueueSize();

    /**
     * 锁竞争失败后延迟执行的时间
     */
    String getPostponeDelay();

    /**
     * 因未能竞争到锁而失败的事务数量
     */
    int getFailLockTransCount();

    /**
     * 提交的任务（事务）总数
     */
    long getTotalSubmittedTransCount();

    /**
     * 所有事务推迟执行的总次数
     */
    long getTotalPostponeTimes();

    /**
     * 事务创建的初始限速值
     */
    long getTransactionCreationInitialRateLimit();

    /**
     * 任务（事务）队列所限制的到达率
     * 防止队列过长
     */
    double getCurrentRateLimit();

    /**
     * 任务（事务）队列水位
     */
    long getTaskCongestionWatermark();

    /**
     * 操作：打印LockPool中的信息
     */
    void printDeviceLocks();

    /**
     * 操作：清除LockPool中的所有锁
     */
    void clearAllLocks();

    /**
     * 操作：打开事务信息的详细打印
     */
    void openTransDetailPrint();

    /**
     * 操作：关闭事务信息的详细打印
     */
    void closeTransDetailPrint();

}
