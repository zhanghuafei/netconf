package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.opendaylight.yangtools.yang.common.QName;

import static org.opendaylight.netconf.sal.connect.netconf.util.NetconfMessageTransformUtil.NETCONF_QNAME;

public class XPathUtil {
    public static final String NAMESPACE_PREFIX = "t";


    public static String toXpathExp(String topContainerName, Integer start, Integer num, String... expressions) {

        String prefixSlash = "/" + NAMESPACE_PREFIX + ":";
        String listName = topContainerName.substring(0, topContainerName.length() - 1);
        String startPath = prefixSlash + topContainerName + prefixSlash + listName;

        StringBuilder stringBuilder = new StringBuilder(startPath);
        if (expressions != null) {
            for (String expression : expressions) {
                String fexp = String.format("[%s]", QueryParaUtil.quoteExp(expression));
                stringBuilder.append(fexp);
            }
        }

        String limit = toLimit(start, num);
        if (limit != null) {
            stringBuilder.append(limit);
        }
        return stringBuilder.toString();
    }

    private static String toLimit(Integer start, Integer num) {
        if (start == null || num == null) {
            return null;
        }
        String limit = String.format("[LIMIT()-%s+%s]", start, num);
        return limit;
    }
}
