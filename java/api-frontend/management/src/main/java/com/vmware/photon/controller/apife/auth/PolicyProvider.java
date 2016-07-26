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

package com.vmware.photon.controller.apife.auth;

import com.vmware.identity.openidconnect.client.ResourceServerAccessToken;
import com.vmware.photon.controller.apife.exceptions.external.ExternalException;

import org.glassfish.jersey.server.ContainerRequest;

/**
 * Interface for a policy service.
 */
public interface PolicyProvider {
  /**
   * Determines if the path in the request can be accessed without authorization.
   *
   * @param request
   * @return
   */
  boolean isOpenAccessRoute(ContainerRequest request);

  /**
   * Determines if the correct token authorizes access to the path.
   *
   * @param request
   * @param token
   * @throws ExternalException
   */
  void checkAccessPermissions(ContainerRequest request, ResourceServerAccessToken token) throws ExternalException;
}
