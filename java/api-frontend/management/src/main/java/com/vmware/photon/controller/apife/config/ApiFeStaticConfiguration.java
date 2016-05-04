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

package com.vmware.photon.controller.apife.config;

import com.vmware.photon.controller.api.constraints.DomainOrIP;
import com.vmware.photon.controller.common.metrics.GraphiteConfig;
import com.vmware.photon.controller.common.zookeeper.ZookeeperConfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
import org.hibernate.validator.constraints.Range;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

/**
 * API Front End Server Configuration.
 */
public class ApiFeStaticConfiguration extends Configuration implements ApiFeConfiguration {

  @Range(min = 1, max = 2048)
  @JsonProperty("background_workers")
  private int backgroundWorkers = 512;

  @Range(min = 1, max = 2048)
  @JsonProperty("background_workers_queue_size")
  private int backgroundWorkersQueueSize = 512;

  @JsonProperty
  private boolean useDcpBackend = true;

  @Valid
  @NotNull
  @JsonProperty
  private AuthConfig auth = new AuthConfig();

  @Valid
  @NotNull
  @JsonProperty("root_scheduler")
  private RootSchedulerConfig rootScheduler = new RootSchedulerConfig();

  @Valid
  @NotNull
  @JsonProperty
  private ZookeeperConfig zookeeper = new ZookeeperConfig();

  @DomainOrIP
  private String registrationAddress;

  @Valid
  @JsonProperty("image")
  private ImageConfig image = new ImageConfig();

  @Valid
  @JsonProperty("status")
  private StatusConfig statusConfig = new StatusConfig();

  @Valid
  @JsonProperty("pagination")
  private PaginationConfig paginationConfig = new PaginationConfig();

  @JsonProperty
  private boolean useVirtualNetwork = false;

  @Override
  public AuthConfig getAuth() {
    return this.auth;
  }

  @Override
  public RootSchedulerConfig getRootScheduler() {
    return rootScheduler;
  }

  @Override
  public int getBackgroundWorkers() {
    return backgroundWorkers;
  }

  @Override
  public int getBackgroundWorkersQueueSize() {
    return backgroundWorkersQueueSize;
  }

  @Override
  public ZookeeperConfig getZookeeper() {
    return zookeeper;
  }

  @Override
  public GraphiteConfig getGraphite() {
    // Turn off graphite for now. Once we are ready to turn it back on, return
    // graphite instead of null.
    return null;
  }

  @Override
  public String getRegistrationAddress() {
    return registrationAddress;
  }

  @Override
  public ImageConfig getImage() {
    return image;
  }

  @Override
  public StatusConfig getStatusConfig() {
    return statusConfig;
  }

  @Override
  public boolean useDcpBackend() {
    return useDcpBackend;
  }

  @Override
  public PaginationConfig getPaginationConfig() {
    return paginationConfig;
  }

  @Override
  public boolean useVirtualNetwork() {
    return useVirtualNetwork;
  }
}
