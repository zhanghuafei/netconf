/*
 * Copyright (c) 2019 UTStarcom, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.netconf.sal.connect.netconf.sal;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;

import javax.annotation.Nullable;

/**
 * 分页服务
 *
 * @author Huafei Zhang
 */
public interface NetconfPagingService {

    // 常量特定于yang的生成规则
    static final String UT_NAMESPACE_PART = "urn.utstar:uar:";
    static final String REVISION_DATE = "2017-11-02";
    static final String TOP_CONTAINER_SUFFIX = "s";

    static <T extends DataObject> YangInstanceIdentifier toTableYangII(String moduleName) {
        QName topContainer = topoContainerQname(moduleName);
        return YangInstanceIdentifier.builder().node(topContainer).build();
    }

    static QName topoContainerQname(String moduleName) {
        QName moduleQname = QName.create(UT_NAMESPACE_PART + moduleName, REVISION_DATE, moduleName).intern();
        QName topContainer = QName.create(moduleQname, topContainerName(moduleName));
        return topContainer;
    }

    static String topContainerName(String moduleName) {
        return moduleName + TOP_CONTAINER_SUFFIX;
    }

    static String namespace(String moduleName) {
        return UT_NAMESPACE_PART + moduleName;
    }

    /**
     * @param nodeId    网元ID
     * @param tableName 表名
     * @param type      cfg or stat
     * @return 表的总条数
     */
    ListenableFuture<Integer> queryCount(String nodeId, String tableName, TableType type, @Nullable String... expressions);

    /***
     *
     * @param nodeId 网元ID
     * @param moduleName yang模型名字
     * @param start 分页起点
     * @param num 数目
     * @param expressions 查询的条件表达式
     * @return 查询到的Binding类型的数据
     */
    <T extends DataObject> FluentFuture<Optional<T>> query(String nodeId, final String moduleName, @Nullable Integer start, @Nullable Integer num, @Nullable String... expressions);


    /***
     *
     * @param nodeId 网元ID
     * @param moduleName yang模型名字
     * @param start 分页起点
     * @param num 数目
     * @param expressions 查询的条件表达式
     * @return 查询到的Binding类型的数据
     */
    <T extends DataObject> FluentFuture<Optional<NormalizedNode<?, ?>>> find(String nodeId, String moduleName,
                                                                             @Nullable Integer start, @Nullable Integer num, @Nullable String... expressions);

    enum TableType {
        cfg, stat;
    }

}
