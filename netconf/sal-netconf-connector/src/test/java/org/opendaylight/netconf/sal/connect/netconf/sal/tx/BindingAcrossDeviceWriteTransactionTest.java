package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.junit.Assert.assertEquals;
import java.net.InetSocketAddress;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPoint;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.netconf.sal.connect.api.AcrossDeviceTransCommitFailedException;
import org.opendaylight.netconf.sal.connect.util.RemoteDeviceId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopologyBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyKey;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeKey;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcError.ErrorType;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifierWithPredicates;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.PathArgument;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.SettableFuture;

@RunWith(MockitoJUnitRunner.class)
public class BindingAcrossDeviceWriteTransactionTest {
	
	@Mock
    private BindingNormalizedNodeSerializer codec;
	@Mock
    private DOMMountPointService mountService;
	@Mock
	private DOMMountPoint mp1;
	@Mock
	private DOMMountPoint mp2;
	@Mock
	private DOMDataBroker db1;
	
	@Mock
	private DOMDataBroker db2;
	@Mock
	private WriteCandidateTx atx1;
	@Mock
	private WriteCandidateTx atx2;
	
	@Mock
	private Map.Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> entry;
	
	@InjectMocks
	private BindingAcrossDeviceWriteTransaction  tx;
	
	private InstanceIdentifier<NetworkTopology> networkII = InstanceIdentifier.create(NetworkTopology.class);
	
	private InstanceIdentifier<Node> ii1 = networkII
	        .child(Topology.class, new TopologyKey(new TopologyId("topology-netconf"))).child(Node.class,  new NodeKey(new NodeId("1")));
	
	private InstanceIdentifier<Node> ii2 = networkII
	        .child(Topology.class, new TopologyKey(new TopologyId("topology-netconf"))).child(Node.class,  new NodeKey(new NodeId("2")));
	
	private YangInstanceIdentifier topoYangii1;
	
    @Before
    public void setUp() {
    	Map<QName, Object> topoKeyValue = Maps.newHashMap(); 
    	QName topoKeyName = QName.create(Topology.QNAME, "topology-id"); 
    	topoKeyValue.put(topoKeyName, "topology-netconf");
        YangInstanceIdentifier networkYangII = YangInstanceIdentifier.create(NodeIdentifier.create(NetworkTopology.QNAME));
    	topoYangii1 = networkYangII.node(new NodeIdentifierWithPredicates(Topology.QNAME, topoKeyValue));
    	
    	Map<QName, Object> node1KeyValue = Maps.newHashMap(); 
    	Map<QName, Object> node2KeyValue = Maps.newHashMap(); 
    	QName nodeKeyName = QName.create(Node.QNAME, "node-id");
    	node1KeyValue.put(nodeKeyName, "1");
    	node2KeyValue.put(nodeKeyName, "2");
    	YangInstanceIdentifier nodeYangII1 = topoYangii1.node(new NodeIdentifierWithPredicates(Node.QNAME, node1KeyValue));
    	YangInstanceIdentifier nodeYangII2 = topoYangii1.node(new NodeIdentifierWithPredicates(Node.QNAME, node2KeyValue));
    	
        when(mountService.getMountPoint(eq(nodeYangII1))).thenReturn(Optional.of(mp1));
        when(mountService.getMountPoint(eq(nodeYangII2))).thenReturn(Optional.of(mp2)); 
        
        when(entry.getKey()).thenReturn(networkYangII);
        when(entry.getValue()).thenReturn((NormalizedNode)Builders.containerBuilder().withNodeIdentifier(NodeIdentifier.create(NetworkTopology.QNAME)).build());  
        NormalizedNode<PathArgument, Object> normalizedNode = Mockito.mock(NormalizedNode.class);
        NetworkTopology topology = new NetworkTopologyBuilder().build();
        when(codec.<NetworkTopology>toNormalizedNode(eq(networkII), eq(topology))).thenReturn(entry); 
        when(codec.toYangInstanceIdentifier(eq(ii1))).thenReturn(nodeYangII1);
        when(codec.toYangInstanceIdentifier(eq(ii2))).thenReturn(nodeYangII2);
        when(mp1.getService(eq(DOMDataBroker.class))).thenReturn(Optional.of(db1));
        when(mp2.getService(eq(DOMDataBroker.class))).thenReturn(Optional.of(db2));
        when(mp1.getIdentifier()).thenReturn(nodeYangII1);
        when(mp2.getIdentifier()).thenReturn(nodeYangII2);
        when(db1.newWriteOnlyTransaction()).thenReturn(atx1);
        when(db2.newWriteOnlyTransaction()).thenReturn(atx2);
        when(atx1.remoteDeviceId()).thenReturn(new RemoteDeviceId("1", new InetSocketAddress(88)));
        when(atx2.remoteDeviceId()).thenReturn(new RemoteDeviceId("2", new InetSocketAddress(88)));
        RpcError rpcError = RpcResultBuilder.newError(ErrorType.APPLICATION, "test", "wrong message: 1");
        SettableFuture<RpcResult<Void>> future1 = SettableFuture.create();
        RpcResult<Void> result1 =
                RpcResultBuilder.<Void>failed().withRpcError(rpcError).build();
        future1.set(result1);
        when(atx1.resultsToTxStatus()).thenReturn(future1);
        SettableFuture<RpcResult<Void>> future2 = SettableFuture.create();
        RpcResult<Void> result2 =
                RpcResultBuilder.<Void>success().build();
        future2.set(result2);
        when(atx2.resultsToTxStatus()).thenReturn(future2);
        
        doNothing().when(atx1).cleanup();
        doNothing().when(atx2).cleanup();
        doNothing().when(atx1).cleanupOnSuccess();
        doNothing().when(atx2).cleanupOnSuccess();
        Mockito.doAnswer(new Answer<Object>() {
            public Object answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                return "called with arguments: " + args;
            }
        }).when(atx1).put(eq(LogicalDatastoreType.CONFIGURATION), eq(nodeYangII1), any());
        
       doNothing().when(atx2).put(eq(LogicalDatastoreType.CONFIGURATION), eq(nodeYangII2), any());;
    }

    /**
     * 1. 两个网元事务的提交， 1个网元在vote阶段失败，另外一个vote成功。
     * 2. 最终跨网元事务失败。
     * 3. 报告失败失败结果。
     */
    @Test(
            expected = AcrossDeviceTransCommitFailedException.class)
	public void testSubmit() throws TransactionCommitFailedException {
		InstanceIdentifier<NetworkTopology> dataII = InstanceIdentifier.create(NetworkTopology.class);
		
		tx.put(ii1, LogicalDatastoreType.CONFIGURATION, dataII, new NetworkTopologyBuilder().build());
		verify(atx1).put(eq(LogicalDatastoreType.CONFIGURATION), any(), any()); 
		tx.put(ii2, LogicalDatastoreType.CONFIGURATION, dataII, new NetworkTopologyBuilder().build());
		verify(atx2).put(eq(LogicalDatastoreType.CONFIGURATION), any(), any()); 
		
		try {
			tx.submit().checkedGet();
		} catch (AcrossDeviceTransCommitFailedException e) {
			assertEquals("Vote phase failed for device returned error. {RemoteDevice{1}=wrong message: 1}",  e.getMessage());
			throw e; 
		}
	}

}
