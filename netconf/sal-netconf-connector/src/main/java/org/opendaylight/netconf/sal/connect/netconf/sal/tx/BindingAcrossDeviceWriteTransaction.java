/*
 * Copyright (c) 2018 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.*;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataWriteTransaction;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPoint;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.netconf.sal.connect.api.AcrossDeviceTransCommitFailedException;
import org.opendaylight.netconf.sal.connect.api.AcrossDeviceTransPartialUnheathyException;
import org.opendaylight.netconf.sal.connect.netconf.sal.isolation.TransactionScheduler;
import org.opendaylight.netconf.sal.connect.util.RemoteDeviceId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.opendaylight.netconf.sal.connect.netconf.sal.tx.BindingAcrossDeviceWriteTransaction.TxOperationType.*;

/**
 * Across device write transaction
 *
 * @author Zhang Huafei
 */
@SuppressWarnings("deprecation")
public class BindingAcrossDeviceWriteTransaction implements AcrossDeviceWriteTransaction {

    private static final Logger LOG = LoggerFactory.getLogger(BindingAcrossDeviceWriteTransaction.class);
    private static AtomicLong transactionCounter = new AtomicLong(0);

    private BindingNormalizedNodeSerializer codec;
    private DOMMountPointService mountService;

    // to keep error info
    private Set<InstanceIdentifier<?>> missingMountPointPaths = Sets.newHashSet();

    // represents submitted or canceled.
    private AtomicBoolean isCompleted = new AtomicBoolean(false);
    private TransactionScheduler lockPool;

    private long transactionId;

    private Map<YangInstanceIdentifier, DOMDataWriteTransaction> mountPointPathToTx = Maps.newHashMap(); // cohorts

    private LinkedList<TxOperation> operationQueue = new LinkedList<>();


    public BindingAcrossDeviceWriteTransaction(BindingNormalizedNodeSerializer codec,
                                               DOMMountPointService mountService, TransactionScheduler transScheduler) {
        this.codec = codec;
        this.mountService = mountService;
        this.lockPool = transScheduler;
        transactionId = transactionCounter.incrementAndGet();
    }

    public List<TxOperation> getOperations() {
        return operationQueue;
    }

    private boolean isAnyMountPointMissing() {
        return !missingMountPointPaths.isEmpty();
    }

    public long getTransactionId() {
        return transactionId;
    }

    private @Nullable DOMDataWriteTransaction getOrCreate(InstanceIdentifier<?> mountPointPath,
                                                          YangInstanceIdentifier yangMountPointPath) {
        DOMDataWriteTransaction tx = mountPointPathToTx.get(yangMountPointPath);
        if (tx == null) {
            Optional<DOMMountPoint> optionalMountPoint = mountService.getMountPoint(yangMountPointPath);
            if (!optionalMountPoint.isPresent()) {
                LOG.error("transaction {{}}: mount point {} missing.", transactionId, toNodeId(mountPointPath));
                if (missingMountPointPaths.contains(mountPointPath)) {
                    return null;
                }
                missingMountPointPaths.add(mountPointPath);
                return null;
            }
            DOMMountPoint mountPoint = optionalMountPoint.get();
            // I think not checking optional should be ok.
            DOMDataBroker db = mountPoint.getService(DOMDataBroker.class).get();

            tx = db.newWriteOnlyTransaction();
            mountPointPathToTx.put(mountPoint.getIdentifier(), tx);
        }
        return tx;
    }

    @Override
    public void close() {
        cancel();
    }

    /**
     * To collect the commit result and decide if transaction successful.
     * <p>
     * this object will be concurrently accessed by netty threads from each channel.
     */
    @ThreadSafe
    private class TransactionResultCallBack {
        // finished devices count
        // NOTE: shared resource by multiple threads.
        private AtomicInteger count = new AtomicInteger(0);
        // count of devices relevant to this across device transaction.
        final private int size;
        // is the across device transaction successful.
        // NOTE: shared resource by multi thread.
        private AtomicBoolean isSucessful = new AtomicBoolean(true);
        // device id to error message
        private Map<String, String> failedMessages = new ConcurrentHashMap<>();
        private SettableFuture<RpcResult<Void>> actxResult;

        public TransactionResultCallBack(int size, SettableFuture<RpcResult<Void>> actxResult) {
            this.size = size;
            this.actxResult = actxResult;
        }

        private class CommitResultCallBack implements FutureCallback<RpcResult<Void>> {
            private RemoteDeviceId id;

            public CommitResultCallBack(RemoteDeviceId id) {
                this.id = id;
            }

            @Override
            public void onSuccess(RpcResult<Void> result) {
                // Rpc result must be successful. namely, result.isSuccessful() must be true;
                handleIfFinished(null, isSucessful.get());
            }

            private void handleIfFinished(Throwable exception, boolean isSuccessful) {
                int localCount = count.incrementAndGet();
                LOG.debug("transaction {{}}: handling commit response from device {}", transactionId, id);
                if (localCount == size) {
                    if (isSuccessful) {
                        LOG.debug("transaction {{}}: commit phase is sucessfully", transactionId);
                        actxResult.set(RpcResultBuilder.<Void>success().build());
                    } else {
                        String message = String.format("{%s}: commit phase failed for device return error or network " +
                                "error.", transactionId);
                        // WARN: with last exception.
                        AcrossDeviceTransPartialUnheathyException finalException =
                                new AcrossDeviceTransPartialUnheathyException(message, exception);
                        finalException.setDetailedErrorMessages(failedMessages);
                        LOG.warn(message, finalException);
                        actxResult.setException(finalException);
                    }
                }
            }

            /*
             * always caused by network error
             */
            @Override
            public void onFailure(Throwable t) {
                isSucessful.compareAndSet(true, false);
                failedMessages.put(id.getName(), t.getMessage());
                handleIfFinished(t, isSucessful.get());
            }
        }
    }

    private ListenableFuture<RpcResult<Void>> performCommit() {
        ListenableFuture<RpcResult<Void>> voteResult = toVoteResult();
        final SettableFuture<RpcResult<Void>> actxResult = SettableFuture.create();
        TransactionResultCallBack txResultAggregator =
                new TransactionResultCallBack(mountPointPathToTx.size(), actxResult);

        Futures.addCallback(voteResult, new FutureCallback<RpcResult<Void>>() {

            @Override
            public void onSuccess(RpcResult<Void> innerVoteResult) {
                if (innerVoteResult.isSuccessful()) {
                    LOG.debug("transaction {{}}: sending commit message.", transactionId);
                    mountPointPathToTx
                            .entrySet()
                            .forEach(
                                    entry -> {
                                        UTStarcomWriteCandidateTx tx = (UTStarcomWriteCandidateTx) entry.getValue();
                                        ListenableFuture<RpcResult<Void>> commitResult =
                                                tx.doCommit(voteResult);
                                        Futures.addCallback(commitResult,
                                                txResultAggregator.new CommitResultCallBack(tx.remoteDeviceId()), MoreExecutors.directExecutor());
                                    });

                } else {
                    // tx fail
                    LOG.error("transaction {{}}: fail due to unexpected exception", transactionId,
                            new IllegalStateException(
                                    "Unexpected to hit here"));
                }
            }

            @Override
            public void onFailure(Throwable t) { // tx fail
                String message = "{" + transactionId + "} " + "vote phase failed for 'edit-config' or 'validate' " +
                        "returned exception.";
                message = atachDeviceIdIfPresent(t, message);
                AcrossDeviceTransCommitFailedException finalException = new AcrossDeviceTransCommitFailedException(message, t);
                finalException.setDetailedErrorMessages(toIdMessages(message));
                LOG.warn("", finalException);
                actxResult.setException(finalException);
            }

            private String atachDeviceIdIfPresent(Throwable t, String message) {
                Pattern pattern = Pattern.compile("RemoteDevice\\{.*\\}");
                Matcher matcher = pattern.matcher(t.getMessage());
                if (matcher.find()) {
                    message = matcher.group(0) + ":" + message;
                }
                return message;
            }

        }, MoreExecutors.directExecutor());

        // release resource
        Futures.addCallback(actxResult, new FutureCallback<RpcResult<Void>>() {

            @Override
            public void onSuccess(RpcResult<Void> result) {
                if (result.isSuccessful()) {
                    cleanupOnSuccess();
                } else {
                    cleanup();
                }
            }

            @Override
            public void onFailure(Throwable t) {
                cleanup();
            }

        }, MoreExecutors.directExecutor());

        return actxResult;
    }

    private static Map<String, String> toIdMessages(String message) {
        Pattern pattern = Pattern.compile("RemoteDevice\\{(.*)\\}");
        Matcher matcher = pattern.matcher(message);
        String id;
        Map<String, String> idToErr = Maps.newHashMap();
        if (matcher.find()) {
            id = matcher.group(1);
            idToErr.put(id, message);
        }
        return idToErr;
    }

    private ListenableFuture<RpcResult<Void>> toVoteResult() {
        List<ListenableFuture<RpcResult<Void>>> txResults = Lists.newArrayList();
        mountPointPathToTx.entrySet().stream()
                .forEach(entry -> txResults.add(((UTStarcomWriteCandidateTx) entry.getValue()).prepare()));
        final SettableFuture<RpcResult<Void>> transformed = SettableFuture.create();

        Futures.addCallback(Futures.allAsList(txResults), new FutureCallback<List<RpcResult<Void>>>() {
            @Override
            public void onSuccess(final List<RpcResult<Void>> txResults) {
                LOG.debug("transaction {{}}: vote sucessfully.", transactionId);

                if (!transformed.isDone()) {
                    transformed.set(RpcResultBuilder.<Void>success().build());
                }
            }

            @Override
            public void onFailure(final Throwable throwable) {
                // timeout or returned error.
                // WARN: NOT sure if object throwable have the certain type of NetconfDocumentedException.
                transformed.setException(throwable);
            }
        }, MoreExecutors.directExecutor());
        return transformed;
    }

    public FluentFuture<? extends @NonNull CommitInfo> commit() {
        if (!isCompleted.compareAndSet(false, true)) {
            throw new IllegalStateException("{" + transactionId + "}" + " Across device transaction already submitted" +
                    ".");
        }

        if (operationQueue.isEmpty()) {
            return CommitInfo.emptyFluentFuture();
        }

        return lockPool.submit(this);
    }


    public FluentFuture<? extends @NonNull CommitInfo> execute() {
        SettableFuture<CommitInfo> resultFuture = SettableFuture.create();

        try {
            int size = operationQueue.size();
            for (int i = 0; i < size; i++) {
                TxOperation op = operationQueue.poll();
                YangInstanceIdentifier yangMountPointPath = codec.toYangInstanceIdentifier(op.getMountPointPath());
                DOMDataWriteTransaction tx = getOrCreate(op.getMountPointPath(), yangMountPointPath);
                if (isAnyMountPointMissing()) {
                    // discard all changes
                    cleanup();
                    resultFuture
                            .setException(new IllegalStateException(missingMountPointPaths.size() + " mount point missing"));
                    return FluentFuture.from(resultFuture);
                }
                switch (op.getOperationType()) {
                    case PUT: {
                        final Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> normalized =
                                codec.toNormalizedNode(op.getPath(), op.getData());
                        tx.put(op.getStore(), normalized.getKey(), normalized.getValue());
                        break;
                    }
                    case MERGE: {
                        final Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> normalized =
                                codec.toNormalizedNode(op.getPath(), op.getData());
                        tx.merge(op.getStore(), normalized.getKey(), normalized.getValue());
                        break;
                    }
                    case DELETE: {
                        YangInstanceIdentifier dataPath = codec.toYangInstanceIdentifier(op.getPath());
                        tx.delete(op.getStore(), dataPath);
                        break;
                    }
                }
            }

            ListenableFuture<RpcResult<Void>> acTxResult = performCommit();

            Futures.addCallback(acTxResult, new FutureCallback<RpcResult<Void>>() {

                @Override
                public void onSuccess(RpcResult<Void> result) {
                    resultFuture.set(CommitInfo.empty());
                }

                @Override
                public void onFailure(Throwable t) {
                    resultFuture.setException(t);
                }

            }, MoreExecutors.directExecutor());
            return FluentFuture.from(resultFuture);

        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            resultFuture.setException(e);
            return FluentFuture.from(resultFuture);
        }
    }

    private void cleanupOnSuccess() {
        mountPointPathToTx.values().forEach(tx -> ((WriteCandidateTx) tx).cleanupOnSuccess());
    }

    private void cleanup() {
        mountPointPathToTx.values().forEach(tx -> ((WriteCandidateTx) tx).cleanup());
    }

    @Override
    public <T extends DataObject> void put(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
                                           InstanceIdentifier<T> path, T data) {
        TxOperation<T> operation = new TxOperation(PUT, mountPointPath, store, path, data);
        operationQueue.offer(operation);
    }

    @Override
    public <T extends DataObject> void delete(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
                                              InstanceIdentifier<T> path) {
        TxOperation<T> operation = new TxOperation(DELETE, mountPointPath, store, path);
        operationQueue.offer(operation);
    }


    @Override
    public <T extends DataObject> void merge(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
                                             InstanceIdentifier<T> path, T data) {
        TxOperation<T> operation = new TxOperation(MERGE, mountPointPath, store, path);
        operationQueue.offer(operation);
    }

    @Override
    public boolean cancel() {
        return true;
    }

    public class TxOperation<T extends DataObject> {
        private TxOperationType operationType;
        private InstanceIdentifier<?> mountPointPath;
        private LogicalDatastoreType store;
        private InstanceIdentifier<T> path;
        private T data;

        public TxOperation(TxOperationType operationType, InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store, InstanceIdentifier<T> path) {
            if (operationType != DELETE) {
                throw new IllegalArgumentException("Unexpected operation type" + operationType);
            }
            this.operationType = operationType;
            this.mountPointPath = mountPointPath;
            this.store = store;
            this.path = path;
        }

        public TxOperation(TxOperationType operationType, InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store, InstanceIdentifier<T> path, T data) {
            this.operationType = operationType;
            this.mountPointPath = mountPointPath;
            this.store = store;
            this.path = path;
            this.data = data;
        }

        TxOperationType getOperationType() {
            return operationType;
        }

        public InstanceIdentifier<?> getMountPointPath() {
            return mountPointPath;
        }

        public LogicalDatastoreType getStore() {
            return store;
        }

        public InstanceIdentifier<T> getPath() {
            return path;
        }

        public T getData() {
            return data;
        }

        @Override
        public String toString() {
            String nodeId = toNodeId(mountPointPath);
            String target = path.getTargetType().getSimpleName();

            return "TxOperation{" +
                    "operationType=" + operationType +
                    ", nodeId=" + nodeId +
                    ", store=" + store +
                    ", target=" + target +
                    '}';
        }
    }

    enum TxOperationType {
        DELETE,
        MERGE,
        PUT
    }

    private String toNodeId(InstanceIdentifier<?> mountPointPath) {
        return mountPointPath.firstKeyOf(Node.class).getNodeId().getValue();
    }

}
