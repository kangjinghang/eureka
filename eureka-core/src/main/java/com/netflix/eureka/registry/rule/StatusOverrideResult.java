package com.netflix.eureka.registry.rule;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;

/**
 * Container for a result computed by an {@link InstanceStatusOverrideRule}.
 *
 * Created by Nikos Michalakis on 7/13/16.
 */
public class StatusOverrideResult { // 状态覆盖结果。当匹配成功，返回 matches = true；否则，返回 matches = false

    public static StatusOverrideResult NO_MATCH = new StatusOverrideResult(false, null);

    public static StatusOverrideResult matchingStatus(InstanceInfo.InstanceStatus status) {
        return new StatusOverrideResult(true, status);
    }

    // Does the rule match?
    private final boolean matches;

    // The status computed by the rule.
    private final InstanceInfo.InstanceStatus status;

    private StatusOverrideResult(boolean matches, InstanceInfo.InstanceStatus status) {
        this.matches = matches;
        this.status = status;
    }

    public boolean matches() {
        return matches;
    }

    public InstanceInfo.InstanceStatus status() {
        return status;
    }
}
