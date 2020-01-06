package org.opendaylight.netconf.sal.connect.netconf.sal;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import org.opendaylight.mdsal.dom.api.DOMMountPoint;
import org.opendaylight.mdsal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.dom.api.DOMRpcResult;
import org.opendaylight.mdsal.dom.api.DOMRpcService;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfMessageTransformUtil;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.AnyXmlNode;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerChild;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.w3c.dom.Element;

import javax.xml.transform.dom.DOMSource;
import java.util.Set;

public class ExtCmdServiceImpl implements ExtCmdService {
    private DOMMountPointService domMountService;

    public ExtCmdServiceImpl(DOMMountPointService domMountService) {
        this.domMountService = domMountService;
    }

    @Override
    public FluentFuture<String> extCmdTo(String nodeId, Long indexValue, String cmdNameValue, String operationValue, Integer timeoutValue, Integer syncValue, String paraValue) {
        YangInstanceIdentifier nodeII = ExtCmdService.toYangNodeII(nodeId);
        Optional<DOMMountPoint> mountPointOpt = domMountService.getMountPoint(nodeII);
        if (!mountPointOpt.isPresent()) {
            SettableFuture<String> future = SettableFuture.create();
            future.setException(new IllegalStateException("Specified mount point " + nodeId + " not exist"));
            return future;
        }

        Set<Module> modules = mountPointOpt.get().getSchemaContext().findModules(EXT_MODULE_NAME);
        if (modules == null || modules.isEmpty()) {
            SettableFuture<String> future = SettableFuture.create();
            future.setException(new IllegalStateException("Unable to find module " + EXT_MODULE_NAME));
            return future;
        }
        ExtCmdInputFactory extCmdInputFactory = new ExtCmdInputFactory(modules.iterator().next());

        DOMRpcService rpcService = mountPointOpt.get().getService(DOMRpcService.class).get();
        SchemaPath rpcType = SchemaPath.create(true, extCmdInputFactory.extCmdRpcName);
        AnyXmlNode extCmdInput = extCmdInputFactory.createExtCmdInput(indexValue, cmdNameValue, operationValue, timeoutValue, syncValue, paraValue);
        FluentFuture<DOMRpcResult> resultFuture = rpcService.invokeRpc(rpcType, NetconfMessageTransformUtil.wrap(extCmdInputFactory.extCmdRpcName, extCmdInput));
        return resultFuture.transform(domRpcResult -> {
            Preconditions.checkArgument(domRpcResult.getErrors().isEmpty(), "%s: execute ext cmd failed, errors: %s",
                    nodeId, domRpcResult.getErrors());
            final DataContainerChild<? extends YangInstanceIdentifier.PathArgument, ?> reply =
                    ((ContainerNode) domRpcResult.getResult())
                            .getChild(NetconfMessageTransformUtil.toId(QName.create(extCmdInputFactory.moduleQname, "reply").intern())).get();

            DOMSource domSource = ((AnyXmlNode) reply).getValue();
            Element domReply = (Element) domSource.getNode();
            if (domReply.getFirstChild() == null || Strings.isNullOrEmpty(domReply.getFirstChild().getTextContent())) {
                return null;
            }
            String result = domReply.getFirstChild().getTextContent();
            return result;
        }, MoreExecutors.directExecutor());
    }

}
