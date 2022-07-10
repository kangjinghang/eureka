/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.resources;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.cluster.PeerEurekaNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A <em>jersey</em> resource that handles operations for a particular instance.
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@Produces({"application/xml", "application/json"})
public class InstanceResource { // 处理单个应用实例信息的请求操作的 Resource ( Controller )
    private static final Logger logger = LoggerFactory
            .getLogger(InstanceResource.class);

    private final PeerAwareInstanceRegistry registry;
    private final EurekaServerConfig serverConfig;
    private final String id;
    private final ApplicationResource app;


    InstanceResource(ApplicationResource app, String id, EurekaServerConfig serverConfig, PeerAwareInstanceRegistry registry) {
        this.app = app;
        this.id = id;
        this.serverConfig = serverConfig;
        this.registry = registry;
    }

    /**
     * Get requests returns the information about the instance's
     * {@link InstanceInfo}.
     *
     * @return response containing information about the the instance's
     *         {@link InstanceInfo}.
     */
    @GET
    public Response getInstanceInfo() {
        InstanceInfo appInfo = registry
                .getInstanceByAppAndId(app.getName(), id);
        if (appInfo != null) {
            logger.debug("Found: {} - {}", app.getName(), id);
            return Response.ok(appInfo).build();
        } else {
            logger.debug("Not Found: {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    /**
     * A put request for renewing lease from a client instance.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param overriddenStatus
     *            overridden status if any.
     * @param status
     *            the {@link InstanceStatus} of the instance.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    public Response renewLease(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("overriddenstatus") String overriddenStatus,
            @QueryParam("status") String status,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        boolean isFromReplicaNode = "true".equals(isReplication);
        boolean isSuccess = registry.renew(app.getName(), id, isFromReplicaNode);

        // Not found in the registry, immediately ask for a register
        if (!isSuccess) { // 续租失败
            logger.warn("Not Found (Renew): {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build();
        }
        // Check if we need to sync based on dirty time stamp, the client
        // instance might have changed some value
        Response response;
        if (lastDirtyTimestamp != null && serverConfig.shouldSyncWhenTimestampDiffers()) { // 比较请求的 lastDirtyTimestamp 和 Server 的 InstanceInfo 的 lastDirtyTimestamp 属性差异
            response = this.validateDirtyTimestamp(Long.valueOf(lastDirtyTimestamp), isFromReplicaNode); // 比较 lastDirtyTimestamp 的差异
            // Store the overridden status since the validation found out the node that replicates wins
            if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()
                    && (overriddenStatus != null)
                    && !(InstanceStatus.UNKNOWN.name().equals(overriddenStatus))
                    && isFromReplicaNode) {
                registry.storeOverriddenStatusIfRequired(app.getAppName(), id, InstanceStatus.valueOf(overriddenStatus));
            }
        } else {  // 成功
            response = Response.ok().build();
        }
        logger.debug("Found (Renew): {} - {}; reply status={}", app.getName(), id, response.getStatus());
        return response;
    }

    /**
     * Handles {@link InstanceStatus} updates.
     *
     * <p>
     * The status updates are normally done for administrative purposes to
     * change the instance status between {@link InstanceStatus#UP} and
     * {@link InstanceStatus#OUT_OF_SERVICE} to select or remove instances for
     * receiving traffic.
     * </p>
     *
     * @param newStatus
     *            the new status of the instance.
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    @Path("status")
    public Response statusUpdate( // 应用实例覆盖状态变更接口
            @QueryParam("value") String newStatus,
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            if (registry.getInstanceByAppAndId(app.getName(), id) == null) { // 应用实例不存在
                logger.warn("Instance not found: {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
            boolean isSuccess = registry.statusUpdate(app.getName(), id, // 更新应用实例覆盖状态
                    InstanceStatus.valueOf(newStatus), lastDirtyTimestamp,
                    "true".equals(isReplication));
            // 返回结果
            if (isSuccess) {
                logger.info("Status updated: {} - {} - {}", app.getName(), id, newStatus);
                return Response.ok().build();
            } else {
                logger.warn("Unable to update status: {} - {} - {}", app.getName(), id, newStatus);
                return Response.serverError().build();
            }
        } catch (Throwable e) {
            logger.error("Error updating instance {} for status {}", id,
                    newStatus);
            return Response.serverError().build();
        }
    }

    /**
     * Removes status override for an instance, set with
     * {@link #statusUpdate(String, String, String)}.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @DELETE
    @Path("status")
    public Response deleteStatusUpdate( // 当我们不需要应用实例的覆盖状态时，调度接口接口进行删除
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("value") String newStatusValue, // 大多数情况下，newStatusValue 要和应用实例实际的状态一致，因为该应用实例的 Eureka-Client 不会从 Eureka-Server 拉取到该应用状态 newStatusValue 。另外一种方式，不传递该参数，相当于 UNKNOWN 状态，这样，Eureka-Client 会主动向 Eureka-Server 再次发起注册
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            if (registry.getInstanceByAppAndId(app.getName(), id) == null) { // 应用实例不存在
                logger.warn("Instance not found: {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }

            InstanceStatus newStatus = newStatusValue == null ? InstanceStatus.UNKNOWN : InstanceStatus.valueOf(newStatusValue);
            boolean isSuccess = registry.deleteStatusOverride(app.getName(), id, // 覆盖状态删除
                    newStatus, lastDirtyTimestamp, "true".equals(isReplication));
            // 返回结果
            if (isSuccess) {
                logger.info("Status override removed: {} - {}", app.getName(), id);
                return Response.ok().build();
            } else {
                logger.warn("Unable to remove status override: {} - {}", app.getName(), id);
                return Response.serverError().build();
            }
        } catch (Throwable e) {
            logger.error("Error removing instance's {} status override", id);
            return Response.serverError().build();
        }
    }

    /**
     * Updates user-specific metadata information. If the key is already available, its value will be overwritten.
     * If not, it will be added.
     * @param uriInfo - URI information generated by jersey.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    @Path("metadata")
    public Response updateMetadata(@Context UriInfo uriInfo) {
        try {
            InstanceInfo instanceInfo = registry.getInstanceByAppAndId(app.getName(), id);
            // ReplicationInstance information is not found, generate an error
            if (instanceInfo == null) {
                logger.warn("Cannot find instance while updating metadata for instance {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
            MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
            Set<Entry<String, List<String>>> entrySet = queryParams.entrySet();
            Map<String, String> metadataMap = instanceInfo.getMetadata();
            // Metadata map is empty - create a new map
            if (Collections.emptyMap().getClass().equals(metadataMap.getClass())) {
                metadataMap = new ConcurrentHashMap<>();
                InstanceInfo.Builder builder = new InstanceInfo.Builder(instanceInfo);
                builder.setMetadata(metadataMap);
                instanceInfo = builder.build();
            }
            // Add all the user supplied entries to the map
            for (Entry<String, List<String>> entry : entrySet) {
                metadataMap.put(entry.getKey(), entry.getValue().get(0));
            }
            registry.register(instanceInfo, false);
            return Response.ok().build();
        } catch (Throwable e) {
            logger.error("Error updating metadata for instance {}", id, e);
            return Response.serverError().build();
        }

    }

    /**
     * Handles cancellation of leases for this particular instance.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @DELETE
    public Response cancelLease( // 接收下线请求
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication) {
        try {
            boolean isSuccess = registry.cancel(app.getName(), id, // 下线
                "true".equals(isReplication));

            if (isSuccess) { // 下线成功
                logger.debug("Found (Cancel): {} - {}", app.getName(), id);
                return Response.ok().build();
            } else {// 下线失败
                logger.info("Not Found (Cancel): {} - {}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
        } catch (Throwable e) {
            logger.error("Error (cancel): {} - {}", app.getName(), id, e);
            return Response.serverError().build();
        }

    }
    // 比较 lastDirtyTimestamp 的差异
    private Response validateDirtyTimestamp(Long lastDirtyTimestamp,
                                            boolean isReplication) {
        InstanceInfo appInfo = registry.getInstanceByAppAndId(app.getName(), id, false); // 获取 InstanceInfo
        if (appInfo != null) {
            if ((lastDirtyTimestamp != null) && (!lastDirtyTimestamp.equals(appInfo.getLastDirtyTimestamp()))) {
                Object[] args = {id, appInfo.getLastDirtyTimestamp(), lastDirtyTimestamp, isReplication};
                // 请求 的 较大
                if (lastDirtyTimestamp > appInfo.getLastDirtyTimestamp()) { // 请求 的较大。意味着请求方(可能是 Eureka-Client ，也可能是 Eureka-Server 集群内的其他 Server)存在 InstanceInfo 和 Eureka-Server 的 InstanceInfo 的数据不一致
                    logger.debug(
                            "Time to sync, since the last dirty timestamp differs -"
                                    + " ReplicationInstance id : {},Registry : {} Incoming: {} Replication: {}",
                            args);
                    return Response.status(Status.NOT_FOUND).build(); // 返回 404 响应。请求方收到 404 响应后重新发起注册
                } else if (appInfo.getLastDirtyTimestamp() > lastDirtyTimestamp) { // Server 的 较大
                    // In the case of replication, send the current instance info in the registry for the
                    // replicating node to sync itself with this one.
                    if (isReplication) {
                        logger.debug(
                                "Time to sync, since the last dirty timestamp differs -"
                                        + " ReplicationInstance id : {},Registry : {} Incoming: {} Replication: {}",
                                args);
                        return Response.status(Status.CONFLICT).entity(appInfo).build(); // 409 状态码，客户端收到这个状态码，会用收到的实例信息覆盖它自己本地的
                    } else { // 并且请求方为 Eureka-Client，续租成功，返回 200 成功响应。
                        return Response.ok().build();
                    }
                }
            }

        }
        return Response.ok().build();
    }
}
