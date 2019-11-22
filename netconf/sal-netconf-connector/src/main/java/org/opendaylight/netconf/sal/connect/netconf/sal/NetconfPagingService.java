/*
 * Copyright (c) 2019 UTStarcom, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.netconf.sal.connect.netconf.sal;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import org.opendaylight.yangtools.yang.binding.DataObject;

/***
 * 分页服务
 */
public interface NetconfPagingService {

    /**
     *
     * @param nodeId 网元ID
     * @param tableName 表名
     * @param type cfg or stat
     * @return 表的总条数
     */
     ListenableFuture<Integer> queryCount(String nodeId, String tableName, String type) ;

    /***
     *
     * @param nodeId 网元ID
     * @param topContainer yang顶层container生成的class
     * @param base 分页起点
     * @param num 分页末点
     * @param expressions 查询的条件表达式
     * @return 查询到的Binding类型的数据
     */
     <T extends DataObject> ListenableFuture<Optional<T>> find(String nodeId, final Class<T> topContainer, Integer base, Integer num, String... expressions);
}
