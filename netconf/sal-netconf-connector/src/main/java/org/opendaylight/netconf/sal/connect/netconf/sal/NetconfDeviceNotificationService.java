/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import java.util.Collection;
import javax.annotation.Nonnull;
import org.opendaylight.controller.md.sal.dom.api.DOMNotification;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationListener;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationService;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetconfDeviceNotificationService implements DOMNotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NetconfDeviceNotificationService.class);

    private final Multimap<SchemaPath, DOMNotificationListener> listeners = HashMultimap.create();

    // Notification publish is very simple and hijacks the thread of the caller
    // TODO shouldnt we reuse the implementation for notification router from sal-broker-impl ?
    @SuppressWarnings("checkstyle:IllegalCatch")
    public synchronized void publishNotification(final DOMNotification notification) {
        for (final DOMNotificationListener domNotificationListener : listeners.get(notification.getType())) {
            try {
                domNotificationListener.onNotification(notification);
            } catch (final Exception e) {
                String message = String.format("Listener %s threw an uncaught exception during processing notification %s", domNotificationListener, notification);
                LOG.warn(message, e);
            }
        }
    }

    @Override
    public synchronized <T extends DOMNotificationListener> ListenerRegistration<T> registerNotificationListener(
            @Nonnull final T listener, @Nonnull final Collection<SchemaPath> types) {
        for (final SchemaPath type : types) {
            listeners.put(type, listener);
        }

        return new ListenerRegistration<T>() {
            @Override
            public void close() {
                for (final SchemaPath type : types) {
                    listeners.remove(type, listener);
                }
            }

            @Override
            public T getInstance() {
                return listener;
            }
        };
    }

    @Override
    public synchronized <T extends DOMNotificationListener> ListenerRegistration<T> registerNotificationListener(
            @Nonnull final T listener, final SchemaPath... types) {
        return registerNotificationListener(listener, Lists.newArrayList(types));
    }
}
