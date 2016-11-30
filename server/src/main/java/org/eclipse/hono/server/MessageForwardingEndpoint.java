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
 */
package org.eclipse.hono.server;

import static io.vertx.proton.ProtonHelper.condition;
import static org.eclipse.hono.util.MessageHelper.APP_PROPERTY_RESOURCE;
import static org.eclipse.hono.util.MessageHelper.getAnnotation;

import java.util.Objects;
import java.util.UUID;

import org.apache.qpid.proton.amqp.transport.AmqpError;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.ResourceIdentifier;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;

/**
 * A base class for implementing Hono {@code Endpoint}s that forward messages
 * to a downstream container.
 *
 */
public abstract class MessageForwardingEndpoint extends BaseEndpoint {

    private DownstreamAdapter downstreamAdapter;
    private UpstreamAdapter upstreamAdapter;

    protected MessageForwardingEndpoint(final Vertx vertx) {
        super(Objects.requireNonNull(vertx));
    }

    @Override
    protected final void doStart(final Future<Void> startFuture) {
        final Future<Void> startDownstream = Future.future();
        final Future<Void> startUpstream = Future.future();

        if (downstreamAdapter == null) {
            startDownstream.complete();
        } else {
            downstreamAdapter.start(startDownstream);
        }

        if (upstreamAdapter == null) {
            startUpstream.complete();
        } else {
            upstreamAdapter.start(startUpstream);
        }

        CompositeFuture.all(startDownstream, startUpstream).compose(cf -> startFuture.complete(), startFuture);
    }

    @Override
    protected final void doStop(final Future<Void> stopFuture) {
        final Future<Void> stopDownstream = Future.future();
        final Future<Void> stopUpstream = Future.future();

        if (downstreamAdapter == null) {
            stopDownstream.complete();
        } else {
            downstreamAdapter.stop(stopDownstream);
        }

        if (upstreamAdapter == null) {
            stopUpstream.complete();
        } else {
            upstreamAdapter.stop(stopUpstream);
        }

        CompositeFuture.all(stopDownstream, stopUpstream).compose(cf -> stopFuture.complete(), stopFuture);
    }
    /**
     * Sets the downstream adapter to forward messages to.
     * <p>
     * Subclasses must invoke this method to set the specific
     * downstream adapter they want to forward messages to.
     * 
     * @param adapter The adapter.
     */
    protected final void setDownstreamAdapter(final DownstreamAdapter adapter) {
        this.downstreamAdapter = Objects.requireNonNull(adapter);
    }

    /**
     * Sets the upstream adapter that forwards messages to the client.
     * <p>
     * Subclasses must invoke this method to set the specific
     * downstream adapter they want to forward messages to.
     *
     * @param adapter The adapter.
     */
    protected final void setUpstreamAdapter(final UpstreamAdapter adapter) {
        this.upstreamAdapter = Objects.requireNonNull(adapter);
    }

    @Override
    public final void onLinkAttach(final ProtonReceiver receiver, final ResourceIdentifier targetAddress) {

        final String linkId = UUID.randomUUID().toString();
        final UpstreamReceiver link = ForwardingLinkImpl.newUpstreamReceiver(linkId, receiver, getEndpointQos());

        downstreamAdapter.onClientAttach(link, s -> {
            if (s.succeeded()) {
                receiver.closeHandler(clientDetached -> {
                    // client has closed link -> inform TelemetryAdapter about client detach
                    onLinkDetach(link);
                    downstreamAdapter.onClientDetach(link);
                }).handler((delivery, message) -> {
                    if (passesFormalVerification(targetAddress, message)) {
                        forwardMessage(link, delivery, message);
                    } else {
                        MessageHelper.rejected(delivery, AmqpError.DECODE_ERROR.toString(), "malformed message");
                        onLinkDetach(link, condition(AmqpError.DECODE_ERROR.toString(), "invalid message received"));
                    }
                }).open();
                logger.debug("accepted link from client [{}]", linkId);
            } else {
                // we cannot connect to downstream container, reject client
                link.close(condition(AmqpError.PRECONDITION_FAILED, "no consumer available for target"));
            }
        });
    }

    @Override
    public void onLinkAttach(final ProtonSender sender, final ResourceIdentifier targetResource) {
        final String linkId = UUID.randomUUID().toString();
        final UpstreamSender link = ForwardingLinkImpl.newUpstreamSender(linkId, sender, getEndpointQos());

        upstreamAdapter.onClientAttach(link, s -> {
            if (s.succeeded()) {
                sender.closeHandler(clientDetached -> {
                    // client has closed link -> inform Adapter about client detach
                    onLinkDetach(link);
                    upstreamAdapter.onClientDetach(link);
                }).open();
                logger.debug("accepted link from client [{}]", linkId);
            } else {
                // we cannot connect to downstream container, reject client
                link.close(condition(AmqpError.PRECONDITION_FAILED, "no consumer available for target"));
            }
        });

    }

    private void forwardMessage(final UpstreamReceiver link, final ProtonDelivery delivery, final Message msg) {

        final ResourceIdentifier messageAddress = ResourceIdentifier.fromString(getAnnotation(msg, APP_PROPERTY_RESOURCE, String.class));
        checkDeviceExists(messageAddress, deviceExists -> {
            if (deviceExists) {
                downstreamAdapter.processMessage(link, delivery, msg);
            } else {
                logger.debug("device {}/{} does not exist, closing link",
                        messageAddress.getTenantId(), messageAddress.getResourceId());
                MessageHelper.rejected(delivery, AmqpError.PRECONDITION_FAILED.toString(), "device does not exist");
                link.close(condition(AmqpError.PRECONDITION_FAILED.toString(), "device does not exist"));
            }
        });
    }

    /**
     * Gets the Quality-of-Service this endpoint uses for messages received from upstream clients.
     * 
     * @return The QoS.
     */
    protected abstract ProtonQoS getEndpointQos();

    /**
     * Verifies that a message passes <em>formal</em> checks regarding e.g.
     * required headers, content type and payload format.
     * 
     * @param targetAddress The address the message has been received on.
     * @param message The message to check.
     * @return {@code true} if the message passes all checks and can be forwarded downstream.
     */
    protected abstract boolean passesFormalVerification(final ResourceIdentifier targetAddress, final Message message);
}
