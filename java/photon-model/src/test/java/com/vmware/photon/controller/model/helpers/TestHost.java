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

package com.vmware.photon.controller.model.helpers;

import com.vmware.dcp.common.Operation;
import com.vmware.dcp.common.ServiceHost;
import com.vmware.photon.controller.common.dcp.BasicServiceHost;
import com.vmware.photon.controller.common.dcp.ServiceHostUtils;
import com.vmware.photon.controller.model.resources.ComputeDescriptionFactoryService;
import com.vmware.photon.controller.model.resources.DiskFactoryService;

import java.util.logging.LogManager;

/**
 * This class implements helper routines used to test service hosts in isolation.
 */
public class TestHost extends BasicServiceHost {

  public static final Class[] FACTORY_SERVICES = {
      ComputeDescriptionFactoryService.class,
      DiskFactoryService.class,
  };

  public TestHost() throws Throwable {
    super();
    this.initialize();
  }

  @Override
  public ServiceHost start() throws Throwable {
    super.start();

    this.startWithCoreServices();
    ServiceHostUtils.startServices(this, FACTORY_SERVICES);

    return this;
  }

  @Override
  public void destroy() throws Throwable {
    super.destroy();
    LogManager.getLogManager().reset();
  }

  @Override
  public Operation sendRequestAndWait(Operation op) throws Throwable {
    Operation operation = super.sendRequestAndWait(op);

    // For tests we check status code 200 to see if the response is OK
    // If nothing is changed in patch, it returns 304 which means not modified.
    // We will treat 304 as 200
    if (operation.getStatusCode() == 304) {
      operation.setStatusCode(200);
    }
    return operation;
  }
}
