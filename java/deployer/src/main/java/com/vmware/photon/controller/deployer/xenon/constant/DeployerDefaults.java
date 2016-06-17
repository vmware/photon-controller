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

package com.vmware.photon.controller.deployer.xenon.constant;

/**
 * Defines various deployer constants.
 */
public class DeployerDefaults {
  /**
   * The core pool size is minimum the number of threads which will be kept
   * alive by the thread pool executor service. Using a value of zero allows
   * the thread pool to die down to a state where no threads are present, since
   * after a deployment is complete there should be no work items being
   * processed by the deployer service.
   * <p>
   * Since we are using a queue without a hard limit in order we need to set
   * the CORE_POOL_SIZE equal to the MAXIMUM_POOL_SIZE to ever get to use the
   * maximum.
   */
  public static final int CORE_POOL_SIZE = 16;

  /**
   * The maximum pool size is the maximum number of threads which the thread
   * pool executor service will spin up in response to new work items. After
   * this maximum is reached, new threads go onto the service's BlockingQueue
   * or are rejected per the service's rejection policy. Using a value of
   * sixteen is just a guess here, but some maximum pool size needs to be set
   * in order to prevent the JVM from running out of memory as it spins up a
   * large number of threads during deployment.
   */
  public static final int MAXIMUM_POOL_SIZE = 16;

  /**
   * The keep-alive time is the period after which the thread pool executor
   * service will kill off threads above the CORE_POOL_SIZE which have not
   * processed work items. A value of sixty seconds is taken from the default
   * value used for the CachedThreadPool executor.
   */
  public static final long KEEP_ALIVE_TIME = 60;

  /**
   * This value represents the default polling delay to use when polling the
   * state of a task object generated by a Photon Controller API call.
   */
  public static final int DEFAULT_TASK_POLL_DELAY = 1000;

  /**
   * This value represents the default polling delay to use when polling the
   * state of a async NSX API call.
   */
  public static final int DEFAULT_NSX_POLL_DELAY = 5000;

  /**
   * Maximum memory to be assigned to docker VM.
   */
  public static final int DEFAULT_MAX_MEMORY_GB = 64;

  /**
   * Maximum allowed docker VMs. Will essentially limit the number of replicas of
   * replicated jobs to this value.
   */
  public static final int DEFAULT_MAX_VM_COUNT = 20;

  /**
   * Port number on which deployer listens.
   */
  public static final int DEPLOYER_PORT_NUMBER = 18000;

  /**
   * Max number of retries for Xenon queries.
   */
  public static final int DEFAULT_XENON_RETRY_COUNT = 5;

  /**
   * Retry interval between successive query requests.
   */
  public static final int DEFAULT_XENON_RETRY_INTERVAL_MILLISECOND = 5000;

  /**
   * Timeout on running shell scripts.
   */
  public static final int SCRIPT_TIMEOUT_IN_SECONDS = 600;

  /**
   * Default wait interval which is used when polling for task in the
   * WAIT_FOR_TASK substage.
   */
  public static final int DEFAULT_POLLING_INTERVAL_MILLISECOND = 1000;

  /**
   * Max retry count when waiting for a service to be available.
   */
  public static final int DEFAULT_WAIT_FOR_SERVICE_MAX_RETRY_COUNT = 300;

  /**
   * Default port for CloudStore.
   */
  public static final int CLOUDSTORE_PORT_NUMBER = 19000;

  /**
   * Default entrypoint script to be executed on container startup.
   */
  public static final String DEFAULT_ENTRYPOINT = "/bin/bash";

  /**
   * Default entrypoint command to be executed on container startup.
   */
  public static final String DEFAULT_ENTRYPOINT_COMMAND = "/etc/esxcloud/run.sh";

  /**
   * Lightwave requires entrypoint of "init" to use systemd as service manager.
   */
  public static final String LIGHTWAVE_ENTRYPOINT = "/usr/sbin/init";

  /**
   * This is the ratio which decides the amount of resource to be allocated to the management vm on a host which is
   * tagged with only the MGMT usage tag.
   */
  public static final float MANAGEMENT_VM_TO_MANAGEMENT_ONLY_HOST_RESOURCE_RATIO = 0.8f;

  /**
   * This is the ratio which decides the amount of resource to be allocated to the management vm on a host which is
   * tagged with both MGMT and CLOUD usage tags.
   */
  public static final float MANAGEMENT_VM_TO_MIXED_HOST_RESOURCE_RATIO = 0.25f;
}
