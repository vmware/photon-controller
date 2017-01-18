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

package com.vmware.photon.controller.api.frontend.commands.steps;

import com.vmware.photon.controller.api.frontend.backends.DeploymentBackend;
import com.vmware.photon.controller.api.frontend.backends.StepBackend;
import com.vmware.photon.controller.api.frontend.backends.XenonBackendTestModule;
import com.vmware.photon.controller.api.frontend.backends.clients.ApiFeXenonRestClient;
import com.vmware.photon.controller.api.frontend.commands.tasks.TaskCommand;
import com.vmware.photon.controller.api.frontend.entities.DeploymentEntity;
import com.vmware.photon.controller.api.frontend.entities.StepEntity;
import com.vmware.photon.controller.api.frontend.exceptions.internal.InternalException;
import com.vmware.photon.controller.api.model.DeploymentState;
import com.vmware.photon.controller.cloudstore.xenon.entity.DeploymentService;
import com.vmware.photon.controller.cloudstore.xenon.entity.DeploymentServiceFactory;
import com.vmware.photon.controller.deployer.xenon.constant.DeployerDefaults;

import com.google.inject.Inject;
import org.mockito.Mock;
import org.powermock.modules.testng.PowerMockTestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Collections;

/**
 * Tests {@link com.vmware.photon.controller.api.frontend.commands.steps.SystemPauseBackgroundTasksStepCmd}.
 */
@Guice(modules = {XenonBackendTestModule.class})
public class SystemPauseBackgroundTasksStepCmdTest extends PowerMockTestCase {

  @Mock
  private TaskCommand taskCommand;
  @Mock
  private StepBackend stepBackend;


  private StepEntity step;

  private SystemPauseBackgroundTasksStepCmd command;

  @Inject
  private ApiFeXenonRestClient apiFeXenonRestClient;

  @Inject
  private DeploymentBackend deploymentBackend;

  private DeploymentEntity initialDeploymentEntity;

  private DeploymentService.State deploymentState;

  @BeforeMethod
  public void setUp() throws Exception {
    step = new StepEntity();
    step.setId("step-1");

    deploymentState = new DeploymentService.State();
    deploymentState.imageDataStoreNames = Collections.singleton("imageDatastore");
    deploymentState.imageDataStoreUsedForVMs = true;
    deploymentState.documentSelfLink = DeployerDefaults.DEFAULT_DEPLOYMENT_ID;
    deploymentState.state = DeploymentState.READY;

    apiFeXenonRestClient.post(DeploymentServiceFactory.SELF_LINK, deploymentState);
    initialDeploymentEntity = deploymentBackend.findById(deploymentState.documentSelfLink);
    step.createOrUpdateTransientResource(SystemResumeStepCmd.DEPLOYMENT_ID_RESOURCE_KEY,
        initialDeploymentEntity.getId());
    when(taskCommand.getApiFeXenonRestClient()).thenReturn(apiFeXenonRestClient);
    command = spy(new SystemPauseBackgroundTasksStepCmd(taskCommand, stepBackend, step));
  }

  @AfterMethod
  public void cleanUp() throws Throwable {
    // We need to change the state so that it can be deleted
    deploymentBackend.updateState(initialDeploymentEntity, DeploymentState.NOT_DEPLOYED);
    deploymentState.documentExpirationTimeMicros = 1;
    apiFeXenonRestClient.delete(DeploymentServiceFactory.SELF_LINK + '/' + deploymentState.documentSelfLink,
            deploymentState);
  }

  @Test
  public void testSuccess() throws Throwable {
    command.execute();

    DeploymentEntity deploymentEntity = deploymentBackend.findById(initialDeploymentEntity.getId());
    assertEquals(deploymentEntity.getState(), DeploymentState.BACKGROUND_PAUSED);
  }

  @Test(expectedExceptions = InternalException.class)
  public void testError() throws Throwable {
    step = new StepEntity();
    step.setId("step-1");

    command = spy(new SystemPauseBackgroundTasksStepCmd(taskCommand, stepBackend, step));
    command.execute();
  }
}
