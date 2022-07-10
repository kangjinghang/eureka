/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.resolver.aws;

import java.util.Collections;
import java.util.List;

import com.netflix.discovery.shared.resolver.ClusterResolver;
import com.netflix.discovery.shared.resolver.EndpointRandomizer;
import com.netflix.discovery.shared.resolver.ResolverUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * It is a cluster resolver that reorders the server list, such that the first server on the list
 * is in the same zone as the client. The server is chosen randomly from the available pool of server in
 * that zone. The remaining servers are appended in a random order, local zone first, followed by servers from other zones.
 *
 * @author Tomasz Bak
 */
public class ZoneAffinityClusterResolver implements ClusterResolver<AwsEndpoint> { // 使用可用区亲和的集群解析器

    private static final Logger logger = LoggerFactory.getLogger(ZoneAffinityClusterResolver.class);
    // 委托的解析器。目前代码里为 {@link ConfigClusterResolver}
    private final ClusterResolver<AwsEndpoint> delegate;
    private final String myZone; // 应用实例的可用区
    private final boolean zoneAffinity; // 是否可用区亲和。true ：EndPoint 可用区为本地的优先被放在前面。false ：EndPoint 可用区非本地的优先被放在前面。
    private final EndpointRandomizer randomizer;

    /**
     * A zoneAffinity defines zone affinity (true) or anti-affinity rules (false).
     */
    public ZoneAffinityClusterResolver(
            ClusterResolver<AwsEndpoint> delegate,
            String myZone,
            boolean zoneAffinity,
            EndpointRandomizer randomizer
    ) {
        this.delegate = delegate;
        this.myZone = myZone;
        this.zoneAffinity = zoneAffinity;
        this.randomizer = randomizer;
    }

    @Override
    public String getRegion() {
        return delegate.getRegion();
    }
    // 获得 EndPoint 集群
    @Override
    public List<AwsEndpoint> getClusterEndpoints() {
        List<AwsEndpoint>[] parts = ResolverUtils.splitByZone(delegate.getClusterEndpoints(), myZone); // 拆分成 本地的可用区和非本地的可用区的 EndPoint 集群
        List<AwsEndpoint> myZoneEndpoints = parts[0];
        List<AwsEndpoint> remainingEndpoints = parts[1];
        List<AwsEndpoint> randomizedList = randomizeAndMerge(myZoneEndpoints, remainingEndpoints); // 随机打乱 EndPoint 集群并进行合并
        if (!zoneAffinity) { // 非可用区亲和，将非本地的可用区的 EndPoint 集群放在前面
            Collections.reverse(randomizedList);
        }

        logger.debug("Local zone={}; resolved to: {}", myZone, randomizedList);

        return randomizedList;
    }
    // 随机打乱 EndPoint 集群并进行合并
    private List<AwsEndpoint> randomizeAndMerge(List<AwsEndpoint> myZoneEndpoints, List<AwsEndpoint> remainingEndpoints) {
        if (myZoneEndpoints.isEmpty()) {
            return randomizer.randomize(remainingEndpoints);  // 打乱
        }
        if (remainingEndpoints.isEmpty()) {
            return randomizer.randomize(myZoneEndpoints);  // 打乱
        }
        List<AwsEndpoint> mergedList = randomizer.randomize(myZoneEndpoints);  // 打乱
        mergedList.addAll(randomizer.randomize(remainingEndpoints));  // 打乱
        return mergedList;
    }
}
