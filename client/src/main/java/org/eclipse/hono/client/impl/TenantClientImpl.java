/*******************************************************************************
 * Copyright (c) 2016, 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.client.impl;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import javax.security.auth.x500.X500Principal;

import org.eclipse.hono.cache.CacheProvider;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.StatusCodeMapper;
import org.eclipse.hono.client.TenantClient;
import org.eclipse.hono.config.ClientConfigProperties;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.CacheDirective;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.RegistrationConstants;
import org.eclipse.hono.util.TenantConstants;
import org.eclipse.hono.util.TenantConstants.TenantAction;
import org.eclipse.hono.util.TenantObject;
import org.eclipse.hono.util.TenantResult;
import org.eclipse.hono.util.TriTuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.tag.StringTag;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;

/**
 * A Vertx-Proton based client for Hono's Tenant API.
 *
 */
public class TenantClientImpl extends AbstractRequestResponseClient<TenantResult<TenantObject>>
        implements TenantClient {

    private static final Logger LOG = LoggerFactory.getLogger(TenantClientImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final StringTag TAG_SUBJECT_DN = new StringTag("subject_dn");

    /**
     * Creates a tenant API client.
     *
     * @param context The Vert.x context to run message exchanges with the peer on.
     * @param config The configuration properties to use.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    protected TenantClientImpl(final Context context, final ClientConfigProperties config) {
        this(context, config, NoopTracerFactory.create());
    }

    /**
     * Creates a tenant API client.
     *
     * @param context The Vert.x context to run message exchanges with the peer on.
     * @param config The configuration properties to use.
     * @param tracer The tracer to use for tracking request processing
     *               across process boundaries.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    protected TenantClientImpl(final Context context, final ClientConfigProperties config, final Tracer tracer) {
        super(context, config, tracer, null);
    }

    /**
     * Creates a tenant API client.
     *
     * @param context The Vert.x context to run message exchanges with the peer on.
     * @param config The configuration properties to use.
     * @param sender The AMQP 1.0 link to use for sending requests to the peer.
     * @param receiver The AMQP 1.0 link to use for receiving responses from the peer.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    protected TenantClientImpl(final Context context, final ClientConfigProperties config,
                           final ProtonSender sender, final ProtonReceiver receiver) {

        this(context, config, null, sender, receiver);
    }

    /**
     * Creates a tenant API client.
     *
     * @param context The Vert.x context to run message exchanges with the peer on.
     * @param config The configuration properties to use.
     * @param tracer The tracer to use for tracking request processing
     *               across process boundaries.
     * @param sender The AMQP 1.0 link to use for sending requests to the peer.
     * @param receiver The AMQP 1.0 link to use for receiving responses from the peer.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    protected TenantClientImpl(final Context context, final ClientConfigProperties config,
                           final Tracer tracer, final ProtonSender sender, final ProtonReceiver receiver) {
        super(context, config, tracer, null, sender, receiver);
    }

    @Override
    protected final String getName() {

        return TenantConstants.TENANT_ENDPOINT;
    }

    @Override
    protected final String createMessageId() {

        return String.format("%s-%s", TenantConstants.MESSAGE_ID_PREFIX, UUID.randomUUID());
    }

    @Override
    protected final TenantResult<TenantObject> getResult(final int status, final Buffer payload, final CacheDirective cacheDirective) {

        if (payload == null) {
            return TenantResult.from(status, (TenantObject) null, cacheDirective);
        } else {
            try {
                return TenantResult.from(status, OBJECT_MAPPER.readValue(payload.getBytes(), TenantObject.class), cacheDirective);
            } catch (final IOException e) {
                LOG.warn("received malformed payload from Tenant service", e);
                return TenantResult.from(HttpURLConnection.HTTP_INTERNAL_ERROR);
            }
        }
    }

    /**
     * Gets the AMQP <em>target</em> address to use for sending requests to Hono's Tenant API endpoint.
     *
     * @return The target address.
     * @throws NullPointerException if tenant is {@code null}.
     */
    public final static String getTargetAddress() {
        return TenantConstants.TENANT_ENDPOINT;
    }

    /**
     * Creates a new tenant client.
     *
     * @param context The vert.x context to run all interactions with the server on.
     * @param clientConfig The configuration properties to use.
     * @param cacheProvider A factory for cache instances for tenant configuration results. If {@code null}
     *                     the client will not cache any results from the Tenant service.
     * @param tracer The tracer to use for tracking request processing
     *               across process boundaries.
     * @param con The AMQP connection to the server.
     * @param senderCloseHook A handler to invoke if the peer closes the sender link unexpectedly.
     * @param receiverCloseHook A handler to invoke if the peer closes the receiver link unexpectedly.
     * @param creationHandler The handler to invoke with the outcome of the creation attempt.
     * @throws NullPointerException if any of the parameters, except for senderCloseHook and receiverCloseHook, is {@code null}.
     */
    public final static void create(
            final Context context,
            final ClientConfigProperties clientConfig,
            final CacheProvider cacheProvider,
            final Tracer tracer,
            final ProtonConnection con,
            final Handler<String> senderCloseHook,
            final Handler<String> receiverCloseHook,
            final Handler<AsyncResult<TenantClient>> creationHandler) {

        LOG.debug("creating new tenant client");
        final TenantClientImpl client = new TenantClientImpl(context, clientConfig, tracer);
        if (cacheProvider != null) {
            client.setResponseCache(cacheProvider.getCache(TenantClientImpl.getTargetAddress()));
        }
        client.createLinks(con, senderCloseHook, receiverCloseHook).setHandler(s -> {
            if (s.succeeded()) {
                LOG.debug("successfully created tenant client");
                creationHandler.handle(Future.succeededFuture(client));
            } else {
                LOG.debug("failed to create tenant client", s.cause());
                creationHandler.handle(Future.failedFuture(s.cause()));
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Future<TenantObject> get(final String tenantId) {
        return get(tenantId, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Future<TenantObject> get(final String tenantId, final SpanContext parent) {

        Objects.requireNonNull(tenantId);

        final TriTuple<TenantAction, String, Object> key = TriTuple.of(TenantAction.get, tenantId, null);
        final Span span = newChildSpan(parent, "get Tenant by ID");
        span.setTag(MessageHelper.APP_PROPERTY_TENANT_ID, tenantId);
        return get(
                key,
                () -> new JsonObject().put(TenantConstants.FIELD_PAYLOAD_TENANT_ID, tenantId).toBuffer(),
                span).map(tenant -> {
                    span.finish();
                    return tenant;
                }).recover(t -> {
                    logError(span, t);
                    span.finish();
                    return Future.failedFuture(t);
                });
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public final Future<TenantObject> get(final X500Principal subjectDn) {
        return get(subjectDn, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Future<TenantObject> get(final X500Principal subjectDn, final SpanContext parent) {

        Objects.requireNonNull(subjectDn);

        final String subjectDnRfc2253 = subjectDn.getName(X500Principal.RFC2253);
        final TriTuple<TenantAction, X500Principal, Object> key = TriTuple.of(TenantAction.get, subjectDn, null);
        final Span span = newChildSpan(parent, "get Tenant by subject DN");
        TAG_SUBJECT_DN.set(span, subjectDnRfc2253);
        return get(
                key,
                () -> new JsonObject().put(TenantConstants.FIELD_PAYLOAD_SUBJECT_DN, subjectDnRfc2253).toBuffer(),
                span).map(tenant -> {
                    span.finish();
                    return tenant;
                }).recover(t -> {
                    logError(span, t);
                    span.finish();
                    return Future.failedFuture(t);
                });
    }

    private <T> Future<TenantObject> get(
            final TriTuple<TenantAction, T, Object> key,
            final Supplier<Buffer> payloadSupplier,
            final Span currentSpan) {

        TracingHelper.TAG_CACHE_HIT.set(currentSpan, true);

        return getResponseFromCache(key).recover(cacheMiss -> {
            TracingHelper.TAG_CACHE_HIT.set(currentSpan, false);
            final Future<TenantResult<TenantObject>> tenantResult = Future.future();
            createAndSendRequest(
                    TenantConstants.TenantAction.get.toString(),
                    customizeRequestApplicationProperties(),
                    payloadSupplier.get(),
                    RegistrationConstants.CONTENT_TYPE_APPLICATION_JSON,
                    tenantResult.completer(),
                    key,
                    currentSpan);
            return tenantResult;
        }).map(tenantResult -> {
            switch(tenantResult.getStatus()) {
            case HttpURLConnection.HTTP_OK:
                return tenantResult.getPayload();
            case HttpURLConnection.HTTP_NOT_FOUND:
                throw new ClientErrorException(tenantResult.getStatus(), "no such tenant");
            default:
                throw StatusCodeMapper.from(tenantResult);
            }
        });
    }

    /**
     * Customize AMQP application properties of the request by overwriting this method.
     * @return The map that holds the properties to include in the AMQP 1.0 message, or null (if nothing is customized).
     */
    protected Map<String, Object> customizeRequestApplicationProperties() {
        return null;
    }
}
