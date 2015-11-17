/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.cloudstore.dcp.task.trigger;

import com.vmware.dcp.common.Utils;
import com.vmware.photon.controller.cloudstore.dcp.task.EntityLockCleanerFactoryService;
import com.vmware.photon.controller.cloudstore.dcp.task.EntityLockCleanerService;
import com.vmware.photon.controller.common.dcp.scheduler.TaskStateBuilder;
import com.vmware.photon.controller.common.dcp.scheduler.TaskTriggerService;

import java.util.concurrent.TimeUnit;

/**
 * Builder that generates the states for a TaskTriggerService meant to periodically trigger
 * EntityLockCleanerService instances.
 */
public class EntityLockCleanerTriggerBuilder implements TaskStateBuilder {

  /**
   * Link for the trigger service.
   */
  public static final String TRIGGER_SELF_LINK = "/entitylock-cleaner";

  /**
   * Default interval for entity lock cleaner service. (5m)
   */
  public static final long DEFAULT_TRIGGER_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);

  /**
   * Default age after which to expire a task.
   */
  public static final long DEFAULT_TASK_EXPIRATION_AGE_MILLIS = DEFAULT_TRIGGER_INTERVAL_MILLIS * 5;

  /**
   * Interval at which to trigger the lock cleanup in milliseconds.
   */
  private final Long triggerIntervalMills;

  /**
   * Age at which the EntityLockCleanerService tasks should expire.
   */
  private final Long taskExpirationAgeMills;

  /**
   * Constructor.
   *
   * @param triggerInterval   (in milliseconds)
   * @param taskExpirationAge (in milliseconds)
   */
  public EntityLockCleanerTriggerBuilder(Long triggerInterval, Long taskExpirationAge) {
    this.triggerIntervalMills = triggerInterval;
    this.taskExpirationAgeMills = taskExpirationAge;
  }

  @Override
  public TaskTriggerService.State build() {
    TaskTriggerService.State state = new TaskTriggerService.State();

    state.taskExpirationAgeMillis = this.taskExpirationAgeMills.intValue();
    state.triggerIntervalMillis = this.triggerIntervalMills.intValue();
    state.serializedTriggerState = Utils.toJson(new EntityLockCleanerService.State());
    state.triggerStateClassName = EntityLockCleanerService.State.class.getName();
    state.factoryServiceLink = EntityLockCleanerFactoryService.SELF_LINK;
    state.documentSelfLink = TRIGGER_SELF_LINK;

    return state;
  }
}
