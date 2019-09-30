package org.opendaylight.netconf.sal.connect.netconf.util;

import org.opendaylight.mdsal.dom.api.DOMSchemaService;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;

public class GlobalSchemaContext {
	private	static DOMSchemaService domService;

	public GlobalSchemaContext(DOMSchemaService schemaService) {
		domService = schemaService;
	}


	public static SchemaContext shcemaContext() {
		return domService.getGlobalContext();
	}

}
