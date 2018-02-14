/**
 * Copyright (c) 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 1.0 which is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package org.eclipse.hono.service.tenant;

import static org.eclipse.hono.util.TenantConstants.StandardAction;
import static org.eclipse.hono.util.TenantConstants.FIELD_TENANT_ID;

import java.net.HttpURLConnection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.hono.config.ServiceConfigProperties;
import org.eclipse.hono.service.http.AbstractHttpEndpoint;
import org.eclipse.hono.util.TenantConstants;
import org.springframework.beans.factory.annotation.Autowired;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * An {@code HttpEndpoint} for managing tenant information.
 * <p>
 * This endpoint implements Hono's <a href="https://www.eclipse.org/hono/api/tenant-api/">Tenant API</a>.
 * It receives HTTP requests representing operation invocations and sends them to an address on the vertx
 * event bus for processing. The outcome is then returned to the peer in the HTTP response.
 */
public final class TenantHttpEndpoint extends AbstractHttpEndpoint<ServiceConfigProperties> {

    /**
     * Creates an endpoint for a Vertx instance.
     *
     * @param vertx The Vertx instance to use.
     * @throws NullPointerException if vertx is {@code null};
     */
    @Autowired
    public TenantHttpEndpoint(final Vertx vertx) {
        super(Objects.requireNonNull(vertx));
    }

    @Override
    protected String getEventBusAddress() {
        return TenantConstants.EVENT_BUS_ADDRESS_TENANT_IN;
    }

    @Override
    public String getName() {
        return TenantConstants.TENANT_ENDPOINT;
    }

    @Override
    public void addRoutes(final Router router) {

        final String path = String.format("/%s", TenantConstants.TENANT_ENDPOINT);

        final BodyHandler bodyHandler = BodyHandler.create();
        bodyHandler.setBodyLimit(config.getMaxPayloadSize());

        // ADD tenant
        router.post(path).handler(bodyHandler);
        router.post(path).handler(this::extractRequiredJsonPayload);
        router.post(path).handler(this::checkPayloadForTenantId);
        router.post(path).handler(ctx -> doTenantHttpRequest(ctx, StandardAction.ACTION_ADD,
                status -> status == HttpURLConnection.HTTP_CREATED));

        final String pathWithTenant = String.format("/%s/:%s", TenantConstants.TENANT_ENDPOINT, PARAM_TENANT_ID);

        // GET tenant
        router.get(pathWithTenant).handler(ctx -> doTenantHttpRequest(ctx, StandardAction.ACTION_GET,
                status -> status == HttpURLConnection.HTTP_OK));

        // UPDATE tenant
        router.put(pathWithTenant).handler(bodyHandler);
        router.put(pathWithTenant).handler(this::extractRequiredJsonPayload);
        router.put(pathWithTenant).handler(ctx -> doTenantHttpRequest(ctx, StandardAction.ACTION_UPDATE, null));

        // REMOVE tenant
        router.delete(pathWithTenant).handler(ctx -> doTenantHttpRequest(ctx, StandardAction.ACTION_REMOVE, null));
    }

    /**
     * Check if the payload (that was put to the RoutingContext ctx with the
     * key {@link #KEY_REQUEST_BODY}) contains a value for the key {@link TenantConstants#FIELD_TENANT_ID} that is not null.
     *
     * @param ctx The routing context to retrieve the JSON request body from.
     */
    protected void checkPayloadForTenantId(final RoutingContext ctx) {

        final JsonObject payload = ctx.get(KEY_REQUEST_BODY);

        final Object tenantId = payload.getValue(FIELD_TENANT_ID);

        if (tenantId == null) {
            ctx.response().setStatusMessage(String.format("'%s' param is required", FIELD_TENANT_ID));
            ctx.fail(HttpURLConnection.HTTP_BAD_REQUEST);
        } else if (!(tenantId instanceof String)) {
            ctx.response().setStatusMessage(String.format("'%s' must be a string", FIELD_TENANT_ID));
            ctx.fail(HttpURLConnection.HTTP_BAD_REQUEST);
        }
        ctx.next();
    }

    private void doTenantHttpRequest(final RoutingContext ctx, final StandardAction action, final Predicate<Integer> sendResponseForStatus) {

        final JsonObject payload = ctx.get(KEY_REQUEST_BODY);

        final String tenantId = Optional.ofNullable(getTenantParam(ctx)).orElse(getTenantParamFromPayload(payload));
        logger.debug("http request [{}] for tenant [tenant: {}]", action, tenantId);

        final JsonObject requestMsg = TenantConstants.getServiceRequestAsJson(action.toString(), tenantId, payload);

        sendAction(ctx, requestMsg, getDefaultResponseHandler(ctx, action, sendResponseForStatus,
                (v) -> String.format("/%s/%s", TenantConstants.TENANT_ENDPOINT, tenantId)));
    }

    private static String getTenantParamFromPayload(final JsonObject payload) {
        return (payload != null ? (String) payload.remove(TenantConstants.FIELD_TENANT_ID) : null);
    }

}