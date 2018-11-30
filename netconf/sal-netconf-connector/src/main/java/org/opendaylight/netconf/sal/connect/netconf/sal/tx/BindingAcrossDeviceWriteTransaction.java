/*
 * Copyright (c) 2018 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.controller.config.util.xml.DocumentedException;
import org.opendaylight.controller.md.sal.common.api.TransactionStatus;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataWriteTransaction;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPoint;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.netconf.api.NetconfDocumentedException;
import org.opendaylight.netconf.sal.connect.api.AcrossDeviceTransCommitFailedException;
import org.opendaylight.netconf.sal.connect.api.AcrossDeviceTransPartialUnheathyException;
import org.opendaylight.netconf.sal.connect.util.RemoteDeviceId;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

/**
 * FIXME not completely thread safe
 * 
 * @author Zhang Huafei
 */
@SuppressWarnings("deprecation")
public class BindingAcrossDeviceWriteTransaction implements AcrossDeviceWriteTransaction {

    private static final Logger LOG = LoggerFactory.getLogger(BindingAcrossDeviceWriteTransaction.class);
    private BindingNormalizedNodeSerializer codec;
    private DOMMountPointService mountService;
    private Set<InstanceIdentifier<?>> missingMountPointPaths = Sets.newHashSet(); // to keep error info
    // represents submitted or canceled.
    private AtomicBoolean isCompleted = new AtomicBoolean(false);


    private Map<YangInstanceIdentifier, DOMDataWriteTransaction> mountPointPathToTx = Maps.newHashMap(); // cohorts

    public BindingAcrossDeviceWriteTransaction(BindingNormalizedNodeSerializer codec, DOMMountPointService mountService) {
        this.codec = codec;
        this.mountService = mountService;
    }

    private boolean isAnyMountPointMissing() {
        return !missingMountPointPaths.isEmpty();
    }

    private @Nullable DOMDataWriteTransaction getOrCreate(InstanceIdentifier<?> mountPointPath,
        YangInstanceIdentifier yangMountPointPath) {
        DOMDataWriteTransaction tx = mountPointPathToTx.get(yangMountPointPath);
        if (tx == null) {
            Optional<DOMMountPoint> optionalMountPoint = mountService.getMountPoint(yangMountPointPath);
            if (!optionalMountPoint.isPresent()) {
                LOG.error("Mount point " + mountPointPath + " not exist.");
                if (missingMountPointPaths.contains(mountPointPath)) {
                    return null;
                }
                missingMountPointPaths.add(mountPointPath);
                return null;
            }
            DOMMountPoint mountPoint = optionalMountPoint.get();
            DOMDataBroker db = mountPoint.getService(DOMDataBroker.class).get(); // I think not check optional should be
                                                                                 // ok.
            tx = db.newWriteOnlyTransaction();
            mountPointPathToTx.put(mountPoint.getIdentifier(), tx);
        }
        return tx;
    }

    /**
     * Not sure if need thread safe.
     * 
     * @author Zhang Huafei
     *
     */
    private static class TransactionResultCallBack {
        // finished devices count
        private int count = 0;
        // size of devices relevant to this across device transaction.
        private int size;
        // is the across device transaction successful.
        private boolean isSucessful = true;
        // device id to error message
        private Map<String, String> failedMessages = Maps.newHashMap();
        private SettableFuture<RpcResult<Void>> actxResult;

        public TransactionResultCallBack(int size, SettableFuture<RpcResult<Void>> actxResult) {
            this.size = size;
            this.actxResult = actxResult;
        }

        private class CommitResultCallBack implements FutureCallback<RpcResult<TransactionStatus>> {
            private RemoteDeviceId id;

            public CommitResultCallBack(RemoteDeviceId id) {
                this.id = id;
            }

            @Override
            public void onSuccess(RpcResult<TransactionStatus> result) {
                if (!result.isSuccessful()) {
                    if (isSucessful) {
                        isSucessful = false;
                    }
                    result.getErrors().forEach(error -> failedMessages.put(id.toString(), error.getMessage()));
                }

                handleIfFinished(null);

            }

            private void handleIfFinished(Throwable exception) {
                count++;
                if (count == size) {
                    if (isSucessful) {
                        actxResult.set(RpcResultBuilder.<Void>success().build());
                    } else {
                        String message = "Commit phase failed for device return error or network error.";
                        // WARN: with last exception.
                        AcrossDeviceTransPartialUnheathyException finalException =
                            new AcrossDeviceTransPartialUnheathyException(message, null);
                        finalException.setDetailedErrorMessages(failedMessages);
                        LOG.warn("", finalException);
                        actxResult.setException(finalException);
                    }
                }
            }

            /*
             * always caused by network error
             */
            @Override
            public void onFailure(Throwable t) {
                if (isSucessful) {
                    isSucessful = false;
                }
                failedMessages.put(id.toString(), t.getMessage());
                handleIfFinished(t);
            }

        }
    }

    private ListenableFuture<RpcResult<Void>> performCommit() {
        ListenableFuture<RpcResult<TransactionStatus>> voteResult = toVoteResult();
        final SettableFuture<RpcResult<Void>> actxResult = SettableFuture.create();
        TransactionResultCallBack txResultAggregator =
            new TransactionResultCallBack(mountPointPathToTx.size(), actxResult);

        Futures.addCallback(voteResult, new FutureCallback<RpcResult<TransactionStatus>>() {

            @Override
            public void onSuccess(RpcResult<TransactionStatus> innerVoteResult) {
                if (innerVoteResult.isSuccessful()) {
                    mountPointPathToTx
                        .entrySet()
                        .stream()
                        .forEach(
                            entry -> {
                                AbstractWriteTx tx = (AbstractWriteTx) entry.getValue();
                                ListenableFuture<RpcResult<TransactionStatus>> commitResult =
                                    tx.performCommit(voteResult);
                                Futures.addCallback(commitResult,
                                    txResultAggregator.new CommitResultCallBack(tx.remoteDeviceId()));
                            });

                } else { // tx fail
                    String message = "Vote phase failed for device returned error.";
                    Exception finalException =
                        new AcrossDeviceTransCommitFailedException(message, innerVoteResult.getErrors().toArray(
                            new RpcError[innerVoteResult.getErrors().size()]));
                    LOG.warn("", finalException);
                    actxResult.setException(finalException);
                }
            }

            @Override
            public void onFailure(Throwable t) { // tx fail
                String message = ":RPC during tx 'edit-config' or 'validate' returned an exception";
                Exception finalException = new AcrossDeviceTransCommitFailedException(message, t);
                LOG.warn("", finalException);
                actxResult.setException(finalException);
            }

        });

        return actxResult;
    }

    private ListenableFuture<RpcResult<TransactionStatus>> toVoteResult() {
        List<ListenableFuture<RpcResult<Void>>> txResults = Lists.newArrayList();
        mountPointPathToTx.entrySet().stream()
            .forEach(entry -> txResults.add(((AbstractWriteTx) entry.getValue()).resultsToTxStatus()));
        final SettableFuture<RpcResult<TransactionStatus>> transformed = SettableFuture.create();

        Futures.addCallback(Futures.allAsList(txResults), new FutureCallback<List<RpcResult<Void>>>() {
            @Override
            public void onSuccess(final List<RpcResult<Void>> txResults) {
                txResults.forEach(txResult -> {
                    if (!txResult.isSuccessful() && !transformed.isDone()) {
                        RpcResult<TransactionStatus> result =
                            RpcResultBuilder.<TransactionStatus>failed().withResult(TransactionStatus.FAILED)
                                .withRpcErrors(txResult.getErrors()).build();
                        transformed.set(result);
                    }
                });

                if (!transformed.isDone()) {
                    transformed.set(RpcResultBuilder.success(TransactionStatus.COMMITED).build());
                }
            }

            @Override
            public void onFailure(final Throwable throwable) {
                final NetconfDocumentedException exception =
                    new NetconfDocumentedException(":RPC during tx returned an exception", new Exception(throwable),
                        DocumentedException.ErrorType.APPLICATION, DocumentedException.ErrorTag.OPERATION_FAILED,
                        DocumentedException.ErrorSeverity.ERROR);
                transformed.setException(exception);
            }
        });
        return transformed;
    }

    @Override
    public CheckedFuture<Void, TransactionCommitFailedException> submit() {
        if (!isCompleted.compareAndSet(false, true)) {
            throw new IllegalStateException("Across device transaction already submitted.");
        }
        if (isAnyMountPointMissing()) {
            cleanup(); // discard all changes
            SettableFuture<Void> resultFturue = SettableFuture.create();
            resultFturue
                .setException(new IllegalStateException(missingMountPointPaths.size() + " mount point missing"));
            return toCheckedFuture(resultFturue);
        }

        ListenableFuture<RpcResult<Void>> netTxStatus = performCommit();
        Futures.addCallback(netTxStatus, new FutureCallback<RpcResult<Void>>() {

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

        });

        final ListenableFuture<Void> commitFutureAsVoid =
            Futures.transform(netTxStatus, new Function<RpcResult<Void>, Void>() {
                @Override
                public Void apply(final RpcResult<Void> input) { // No need for rpc result.
                    return null;
                }
            });

        return toCheckedFuture(commitFutureAsVoid);
    }

    private void cleanupOnSuccess() {
        mountPointPathToTx.values().stream().forEach(tx -> ((WriteCandidateTx) tx).cleanupOnSuccess());
    }

    private void cleanup() {
        mountPointPathToTx.values().stream().forEach(tx -> ((WriteCandidateTx) tx).cleanup());
    }

    private CheckedFuture<Void, TransactionCommitFailedException> toCheckedFuture(
        final ListenableFuture<Void> futureAsVoid) {
        return Futures.makeChecked(futureAsVoid, new Function<Exception, TransactionCommitFailedException>() {
            @Override
            public TransactionCommitFailedException apply(Exception input) {
                if (input.getCause() instanceof TransactionCommitFailedException) {
                    return (TransactionCommitFailedException) input.getCause();
                }
                return new AcrossDeviceTransCommitFailedException(input.getCause().getMessage(), input.getCause());
            }
        });
    }

    @Override
    public <T extends DataObject> void put(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
        InstanceIdentifier<T> path, T data) {
        YangInstanceIdentifier yangMountPointPath = codec.toYangInstanceIdentifier(mountPointPath);
        DOMDataWriteTransaction tx = getOrCreate(mountPointPath, yangMountPointPath);
        if (isAnyMountPointMissing()) {
            return;
        }
        final Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> normalized = codec.toNormalizedNode(path, data);

        tx.put(store, normalized.getKey(), normalized.getValue()); // may fail ?
    }

    @Override
    public <T extends DataObject> void delete(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
        InstanceIdentifier<T> path) {
        YangInstanceIdentifier yangMountPointPath = codec.toYangInstanceIdentifier(mountPointPath);
        YangInstanceIdentifier dataPath = codec.toYangInstanceIdentifier(path);
        DOMDataWriteTransaction tx = getOrCreate(mountPointPath, yangMountPointPath);
        if (isAnyMountPointMissing()) {
            return;
        }
        tx.delete(store, dataPath);
    }


    @Override
    public <T extends DataObject> void merge(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
        InstanceIdentifier<T> path, T data) {
        YangInstanceIdentifier yangMountPointPath = codec.toYangInstanceIdentifier(mountPointPath);
        DOMDataWriteTransaction tx = getOrCreate(mountPointPath, yangMountPointPath);
        if (isAnyMountPointMissing()) {
            return;
        }
        final Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> normalized = codec.toNormalizedNode(path, data);
        tx.merge(store, normalized.getKey(), normalized.getValue());
    }

    @Override
    public boolean cancel() {
        if (!isCompleted.compareAndSet(false, true)) {
            return false;
        }

        if (mountPointPathToTx.isEmpty()) {
            return true;
        }

        cleanup();
        return true;

    }


    public static void main(String[] args) {
        System.out.println("Usage: " + BindingAcrossDeviceWriteTransaction.class.getSimpleName() + " <port>");

        SettableFuture<Void> future1 = SettableFuture.create();
        SettableFuture<Void> future2 = SettableFuture.create();
        List<SettableFuture<Void>> futures = Lists.newArrayList();
        futures.add(future1);
        futures.add(future2);
        Futures.addCallback(Futures.allAsList(futures), new FutureCallback<List<Void>>() {
            @Override
            public void onSuccess(List<Void> result) {
                System.out.println("Success");
            }

            @Override
            public void onFailure(Throwable t) {
                System.out.println("Failed-----------------------------------");
            }

        });
        // future1.setException(new IllegalStateException("111111111"));
        future1.set(null);
        future2.setException(new IllegalStateException("222222222"));

        ListenableFuture<Void> future3 = Futures.transform(future1, new Function<Void, Void>() {
            @Override
            public Void apply(Void input) {
                throw new IllegalStateException("hahaha");
            }
        });

        CheckedFuture<Void, TransactionCommitFailedException> checkedFuture =
            Futures.makeChecked(future3, new Function<Exception, TransactionCommitFailedException>() {
                @Override
                public TransactionCommitFailedException apply(final Exception input) {
                    System.out.println("null exception");
                    if (input.getCause() instanceof NullPointerException) {
                        System.out.println("null exception");
                    }
                    return new TransactionCommitFailedException("Submit of transaction failed", input);
                }
            });
        try {
            checkedFuture.checkedGet();
        } catch (Exception e) {
            System.out.println("*************************");
            e.printStackTrace();
        }


        Futures.addCallback(checkedFuture, new FutureCallback<Void>() {

            @Override
            public void onSuccess(Void result) {
                System.out.println("eeeeeeeee");
            }

            @Override
            public void onFailure(Throwable t) {
                System.out.println("ttttttttt");
                t.printStackTrace();

            }
        });


        System.out.println("End.");

    }


    /*
     * public static void main(String[] args) {
     * 
     * Map<String, Object> hashMap = new HashMap<String, Object>(); hashMap.put("1", "a"); hashMap.put("5", "b");
     * hashMap.put("2", "c"); hashMap.put("4", "d"); hashMap.put("3", "e");
     * 
     * 
     * for (Object value : hashMap.values()) { System.out.println("value: " + value); }
     * 
     * for (String key : hashMap.keySet()) { System.out.println("key: " + key); }
     * 
     * Set<Entry<String, Object>> entry = hashMap.entrySet(); for (Entry<String, Object> temp : entry) {
     * System.out.println("hashMap:" + temp.getKey() + " 值" + temp.getValue()); }
     * 
     * }
     */



}
