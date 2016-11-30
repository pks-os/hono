/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 *
 */

package org.eclipse.hono.client.impl;

import java.util.Objects;

import org.eclipse.hono.client.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonSender;

/**
 * A Vertx-Proton based client for uploading telemtry data to a Hono server.
 */
public class CommandSenderImpl extends AbstractSender {
    private static final Logger LOG = LoggerFactory.getLogger(CommandSenderImpl.class);
    private static final String COMMAND_ADDRESS_TEMPLATE = "command%s%s";

    private CommandSenderImpl(final Context context, final ProtonSender sender) {
        super(context);
        this.sender = sender;
    }

    public static void create(
            final Context context,
            final ProtonConnection con,
            final String tenantId,
            final String pathSeparator,
            final Handler<AsyncResult<MessageSender>> creationHandler) {

        Objects.requireNonNull(context);
        Objects.requireNonNull(con);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(pathSeparator);
        createSender(context, con, tenantId, pathSeparator).setHandler(created -> {
            if (created.succeeded()) {
                creationHandler.handle(Future.succeededFuture(
                        new CommandSenderImpl(context, created.result())));
            } else {
                creationHandler.handle(Future.failedFuture(created.cause()));
            }
        });
    }

    private static Future<ProtonSender> createSender(
            final Context ctx,
            final ProtonConnection con,
            final String tenantId,
            final String pathSeparator) {

        final Future<ProtonSender> result = Future.future();
        final String targetAddress = String.format(COMMAND_ADDRESS_TEMPLATE, pathSeparator, tenantId);

        ctx.runOnContext(create -> {
            final ProtonSender sender = con.createSender(targetAddress);
            sender.setQoS(ProtonQoS.AT_LEAST_ONCE);
            sender.openHandler(senderOpen -> {
                if (senderOpen.succeeded()) {
                    LOG.debug("command sender for [{}] open", senderOpen.result().getRemoteTarget());
                    result.complete(senderOpen.result());
                } else {
                    LOG.debug("command sender open for [{}] failed: {}", targetAddress, senderOpen.cause().getMessage());
                    result.fail(senderOpen.cause());
                }
            }).closeHandler(senderClosed -> {
                if (senderClosed.succeeded()) {
                    LOG.debug("command sender for [{}] closed", senderClosed.result().getRemoteTarget());
                } else {
                    LOG.debug("command closed due to {}", senderClosed.cause().getMessage());
                }
            }).open();
        });

        return result;
    }
}
