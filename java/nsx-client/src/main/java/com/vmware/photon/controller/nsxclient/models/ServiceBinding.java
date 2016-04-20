/*
 * Copyright 2016 VMware, Inc. All Rights Reserved.
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

package com.vmware.photon.controller.nsxclient.models;

import com.vmware.photon.controller.nsxclient.utils.ToStringHelper;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Bound services.
 */
public class ServiceBinding {
  @JsonProperty(value = "service_id", required = true)
  ResourceReference serviceId;

  public ResourceReference getServiceId() {
    return serviceId;
  }

  public void setServiceId(ResourceReference serviceId) {
    this.serviceId = serviceId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null || this.getClass() != o.getClass()) {
      return false;
    }

    ServiceBinding other = (ServiceBinding) o;
    return Objects.equals(this.serviceId, other.serviceId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), serviceId);
  }

  @Override
  public String toString() {
    return ToStringHelper.jsonObjectToString(this);
  }
}
