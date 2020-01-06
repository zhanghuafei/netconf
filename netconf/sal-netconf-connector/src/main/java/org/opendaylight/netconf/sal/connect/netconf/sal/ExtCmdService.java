package org.opendaylight.netconf.sal.connect.netconf.sal;

import com.google.common.util.concurrent.FluentFuture;
import org.opendaylight.mdsal.dom.api.DOMRpcResult;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;

public interface ExtCmdService {
    String EXT_MODULE_NAME = "ExtCmd";
    String DEFAULT_TOPOLOGY_NAME = "topology-netconf";
    YangInstanceIdentifier DEFAULT_TOPOLOGY_NODE =
            YangInstanceIdentifier.builder().node(NetworkTopology.QNAME).node(Topology.QNAME)
                    .nodeWithKey(Topology.QNAME, QName.create(Topology.QNAME, "topology-id"), DEFAULT_TOPOLOGY_NAME)
                    .node(Node.QNAME).build();


    static YangInstanceIdentifier toYangNodeII(String nodeId) {
        return YangInstanceIdentifier.builder(DEFAULT_TOPOLOGY_NODE)
                .nodeWithKey(Node.QNAME, QName.create(Node.QNAME, "node-id"), nodeId).build();
    }

    FluentFuture<String> extCmdTo(String nodeId, Long indexValue, String cmdNameValue, String operationValue, Integer timeoutValue, Integer syncValue, String paraValue);

}
