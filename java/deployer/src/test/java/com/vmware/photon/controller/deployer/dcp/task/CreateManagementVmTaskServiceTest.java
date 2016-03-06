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

package com.vmware.photon.controller.deployer.dcp.task;

import com.vmware.photon.controller.api.AttachedDiskCreateSpec;
import com.vmware.photon.controller.api.EphemeralDisk;
import com.vmware.photon.controller.api.LocalitySpec;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.api.UsageTag;
import com.vmware.photon.controller.api.VmCreateSpec;
import com.vmware.photon.controller.api.VmMetadata;
import com.vmware.photon.controller.client.ApiClient;
import com.vmware.photon.controller.client.resource.ProjectApi;
import com.vmware.photon.controller.client.resource.TasksApi;
import com.vmware.photon.controller.client.resource.VmApi;
import com.vmware.photon.controller.cloudstore.dcp.entity.FlavorService;
import com.vmware.photon.controller.cloudstore.dcp.entity.HostService;
import com.vmware.photon.controller.cloudstore.dcp.entity.ImageServiceFactory;
import com.vmware.photon.controller.cloudstore.dcp.entity.ProjectServiceFactory;
import com.vmware.photon.controller.common.config.ConfigBuilder;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.TaskUtils;
import com.vmware.photon.controller.common.xenon.exceptions.XenonRuntimeException;
import com.vmware.photon.controller.common.xenon.validation.Immutable;
import com.vmware.photon.controller.common.xenon.validation.NotNull;
import com.vmware.photon.controller.deployer.DeployerConfig;
import com.vmware.photon.controller.deployer.dcp.ApiTestUtils;
import com.vmware.photon.controller.deployer.dcp.ContainersConfig;
import com.vmware.photon.controller.deployer.dcp.entity.ContainerService;
import com.vmware.photon.controller.deployer.dcp.entity.ContainerTemplateService;
import com.vmware.photon.controller.deployer.dcp.entity.VmService;
import com.vmware.photon.controller.deployer.dcp.util.ApiUtils;
import com.vmware.photon.controller.deployer.deployengine.ApiClientFactory;
import com.vmware.photon.controller.deployer.helpers.ReflectionUtils;
import com.vmware.photon.controller.deployer.helpers.TestHelper;
import com.vmware.photon.controller.deployer.helpers.dcp.MockHelper;
import com.vmware.photon.controller.deployer.helpers.dcp.TestEnvironment;
import com.vmware.photon.controller.deployer.helpers.dcp.TestHost;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;

import com.google.common.util.concurrent.FutureCallback;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class implements tests for {@link CreateManagementVmTaskService} class.
 */
public class CreateManagementVmTaskServiceTest {

  /**
   * This dummy test enables IntelliJ to recognize this as a test class.
   */
  @Test(enabled = false)
  public void dummy() {
  }

  /**
   * This class implements tests for object initialization.
   */
  public class InitializationTest {

    private CreateManagementVmTaskService createManagementVmTaskService;

    @BeforeMethod
    public void setUpTest() {
      createManagementVmTaskService = new CreateManagementVmTaskService();
    }

    @Test
    public void testServiceOptions() {
      assertThat(createManagementVmTaskService.getOptions(), is(EnumSet.noneOf(Service.ServiceOption.class)));
    }
  }

  /**
   * This class implements tests for the {@link CreateManagementVmTaskService#handleStart(Operation)} method.
   */
  public class HandleStartTest {

    private CreateManagementVmTaskService createManagementVmTaskService;
    private TestHost testHost;

    @BeforeClass
    public void setUpClass() throws Throwable {
      testHost = TestHost.create();
    }

    @BeforeMethod
    public void setUpTest() {
      createManagementVmTaskService = new CreateManagementVmTaskService();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      try {
        testHost.deleteServiceSynchronously();
      } catch (ServiceHost.ServiceNotFoundException e) {
        // Exceptions are expected in the case where a service was not successfully created.
      }
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      TestHost.destroy(testHost);
    }

    @Test(dataProvider = "ValidStartStages")
    public void testValidStartState(TaskState.TaskStage taskStage,
                                    CreateManagementVmTaskService.TaskState.SubStage subStage)
        throws Throwable {

      CreateManagementVmTaskService.State startState = buildValidStartState(taskStage, subStage);
      Operation op = testHost.startServiceSynchronously(createManagementVmTaskService, startState);
      assertThat(op.getStatusCode(), is(200));

      CreateManagementVmTaskService.State serviceState =
          testHost.getServiceState(CreateManagementVmTaskService.State.class);

      assertThat(serviceState.vmServiceLink, is("VM_SERVICE_LINK"));
    }

    @DataProvider(name = "ValidStartStages")
    public Object[][] getValidStartStages() {
      return new Object[][]{
          {null, null},
          {TaskState.TaskStage.CREATED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE},
          {TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.CANCELLED, null},
      };
    }

    @Test(dataProvider = "TransitionalStartStages")
    public void testTransitionalStartState(TaskState.TaskStage taskStage,
                                           CreateManagementVmTaskService.TaskState.SubStage subStage)
        throws Throwable {

      CreateManagementVmTaskService.State startState = buildValidStartState(taskStage, subStage);
      Operation op = testHost.startServiceSynchronously(createManagementVmTaskService, startState);
      assertThat(op.getStatusCode(), is(200));

      CreateManagementVmTaskService.State serviceState =
          testHost.getServiceState(CreateManagementVmTaskService.State.class);

      assertThat(serviceState.taskState.stage, is(TaskState.TaskStage.STARTED));
      assertThat(serviceState.taskState.subStage, is(CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM));
    }

    @DataProvider(name = "TransitionalStartStages")
    public Object[][] getTransitionalStartStages() {
      return new Object[][]{
          {null, null},
          {TaskState.TaskStage.CREATED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},
      };
    }

    @Test(dataProvider = "TerminalStartStages")
    public void testTerminalStartState(TaskState.TaskStage taskStage,
                                       CreateManagementVmTaskService.TaskState.SubStage subStage)
        throws Throwable {

      CreateManagementVmTaskService.State startState = buildValidStartState(taskStage, subStage);
      startState.controlFlags = null;
      Operation op = testHost.startServiceSynchronously(createManagementVmTaskService, startState);
      assertThat(op.getStatusCode(), is(200));

      CreateManagementVmTaskService.State serviceState =
          testHost.getServiceState(CreateManagementVmTaskService.State.class);

      assertThat(serviceState.taskState.stage, is(taskStage));
      assertThat(serviceState.taskState.subStage, is(subStage));
    }

    @DataProvider(name = "TerminalStartStages")
    public Object[][] getTerminalStartStages() {
      return new Object[][]{
          {TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.CANCELLED, null},
      };
    }

    @Test(dataProvider = "OptionalFieldNames")
    public void testOptionalFieldValuePersisted(String fieldName) throws Throwable {
      CreateManagementVmTaskService.State startState = buildValidStartState(null, null);
      Field declaredField = startState.getClass().getDeclaredField(fieldName);
      declaredField.set(startState, ReflectionUtils.getDefaultAttributeValue(declaredField));
      Operation op = testHost.startServiceSynchronously(createManagementVmTaskService, startState);
      assertThat(op.getStatusCode(), is(200));

      CreateManagementVmTaskService.State serviceState =
          testHost.getServiceState(CreateManagementVmTaskService.State.class);

      assertThat(declaredField.get(serviceState), is(ReflectionUtils.getDefaultAttributeValue(declaredField)));
    }

    @DataProvider(name = "OptionalFieldNames")
    public Object[][] getOptionalFieldNames() {
      return new Object[][]{
          {"createVmTaskId"},
          {"createVmPollCount"},
          {"vmId"},
          {"updateVmMetadataTaskId"},
          {"updateVmMetadataPollCount"},
      };
    }

    @Test(dataProvider = "RequiredFieldNames", expectedExceptions = XenonRuntimeException.class)
    public void testInvalidStartStateRequiredFieldMissing(String fieldName) throws Throwable {
      CreateManagementVmTaskService.State startState = buildValidStartState(null, null);
      Field declaredField = startState.getClass().getDeclaredField(fieldName);
      declaredField.set(startState, null);
      testHost.startServiceSynchronously(createManagementVmTaskService, startState);
    }

    @DataProvider(name = "RequiredFieldNames")
    public Object[][] getRequiredFieldNames() {
      return TestHelper.toDataProvidersList(
          ReflectionUtils.getAttributeNamesWithAnnotation(
              CreateManagementVmTaskService.State.class, NotNull.class));
    }
  }

  /**
   * This class implements tests for the {@link CreateManagementVmTaskService#handlePatch(Operation)} method.
   */
  public class HandlePatchTest {

    private CreateManagementVmTaskService createManagementVmTaskService;
    private TestHost testHost;

    @BeforeClass
    public void setUpClass() throws Throwable {
      testHost = TestHost.create();
    }

    @BeforeMethod
    public void setUpTest() {
      createManagementVmTaskService = new CreateManagementVmTaskService();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      testHost.deleteServiceSynchronously();
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      TestHost.destroy(testHost);
    }

    @Test(dataProvider = "ValidStageTransitions")
    public void testValidStageTransition(TaskState.TaskStage startStage,
                                         CreateManagementVmTaskService.TaskState.SubStage startSubStage,
                                         TaskState.TaskStage patchStage,
                                         CreateManagementVmTaskService.TaskState.SubStage patchSubStage)
        throws Throwable {

      CreateManagementVmTaskService.State startState = buildValidStartState(startStage, startSubStage);
      Operation op = testHost.startServiceSynchronously(createManagementVmTaskService, startState);
      assertThat(op.getStatusCode(), is(200));

      Operation patchOp = Operation
          .createPatch(UriUtils.buildUri(testHost, TestHost.SERVICE_URI))
          .setBody(CreateManagementVmTaskService.buildPatch(patchStage, patchSubStage, null));

      op = testHost.sendRequestAndWait(patchOp);
      assertThat(op.getStatusCode(), is(200));

      CreateManagementVmTaskService.State serviceState =
          testHost.getServiceState(CreateManagementVmTaskService.State.class);

      assertThat(serviceState.taskState.stage, is(patchStage));
      assertThat(serviceState.taskState.subStage, is(patchSubStage));
      assertThat(serviceState.controlFlags, is(ControlFlags.CONTROL_FLAG_OPERATION_PROCESSING_DISABLED));
    }

    @DataProvider(name = "ValidStageTransitions")
    public Object[][] getValidStageTransitions() {
      return new Object[][]{
          {TaskState.TaskStage.CREATED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
              TaskState.TaskStage.FINISHED, null},

          {TaskState.TaskStage.CREATED, null,
              TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM,
              TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION,
              TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA,
              TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
              TaskState.TaskStage.FINISHED, null},

          {TaskState.TaskStage.CREATED, null,
              TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM,
              TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION,
              TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA,
              TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
              TaskState.TaskStage.FAILED, null},

          {TaskState.TaskStage.CREATED, null,
              TaskState.TaskStage.CANCELLED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM,
              TaskState.TaskStage.CANCELLED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION,
              TaskState.TaskStage.CANCELLED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA,
              TaskState.TaskStage.CANCELLED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
              TaskState.TaskStage.CANCELLED, null},
      };
    }

    @Test(dataProvider = "InvalidStageTransitions", expectedExceptions = XenonRuntimeException.class)
    public void testInvalidStageTransition(TaskState.TaskStage startStage,
                                           CreateManagementVmTaskService.TaskState.SubStage startSubStage,
                                           TaskState.TaskStage patchStage,
                                           CreateManagementVmTaskService.TaskState.SubStage patchSubStage)
        throws Throwable {

      CreateManagementVmTaskService.State startState = buildValidStartState(startStage, startSubStage);
      Operation op = testHost.startServiceSynchronously(createManagementVmTaskService, startState);
      assertThat(op.getStatusCode(), is(200));

      Operation patchOp = Operation
          .createPatch(UriUtils.buildUri(testHost, TestHost.SERVICE_URI))
          .setBody(CreateManagementVmTaskService.buildPatch(patchStage, patchSubStage, null));

      testHost.sendRequestAndWait(patchOp);
    }

    @DataProvider(name = "InvalidStageTransitions")
    public Object[][] getInvalidStageTransitions() {
      return new Object[][]{
          {TaskState.TaskStage.CREATED, null,
              TaskState.TaskStage.CREATED, null},

          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM,
              TaskState.TaskStage.CREATED, null},

          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION,
              TaskState.TaskStage.CREATED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},

          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA,
              TaskState.TaskStage.CREATED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION},

          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
              TaskState.TaskStage.CREATED, null},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION},
          {TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA},

          {TaskState.TaskStage.FINISHED, null,
              TaskState.TaskStage.CREATED, null},
          {TaskState.TaskStage.FINISHED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},
          {TaskState.TaskStage.FINISHED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION},
          {TaskState.TaskStage.FINISHED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA},
          {TaskState.TaskStage.FINISHED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE},
          {TaskState.TaskStage.FINISHED, null,
              TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.FINISHED, null,
              TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.FINISHED, null,
              TaskState.TaskStage.CANCELLED, null},

          {TaskState.TaskStage.FAILED, null,
              TaskState.TaskStage.CREATED, null},
          {TaskState.TaskStage.FAILED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},
          {TaskState.TaskStage.FAILED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION},
          {TaskState.TaskStage.FAILED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA},
          {TaskState.TaskStage.FAILED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE},
          {TaskState.TaskStage.FAILED, null,
              TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.FAILED, null,
              TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.FAILED, null,
              TaskState.TaskStage.CANCELLED, null},

          {TaskState.TaskStage.CANCELLED, null,
              TaskState.TaskStage.CREATED, null},
          {TaskState.TaskStage.CANCELLED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM},
          {TaskState.TaskStage.CANCELLED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_VM_CREATION},
          {TaskState.TaskStage.CANCELLED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.UPDATE_METADATA},
          {TaskState.TaskStage.CANCELLED, null,
              TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.WAIT_FOR_METADATA_UPDATE},
          {TaskState.TaskStage.CANCELLED, null,
              TaskState.TaskStage.FINISHED, null},
          {TaskState.TaskStage.CANCELLED, null,
              TaskState.TaskStage.FAILED, null},
          {TaskState.TaskStage.CANCELLED, null,
              TaskState.TaskStage.CANCELLED, null},
      };
    }

    @Test(dataProvider = "ImmutableFieldNames", expectedExceptions = XenonRuntimeException.class)
    public void testInvalidPatchImmutableFieldSet(String fieldName) throws Throwable {
      CreateManagementVmTaskService.State startState = buildValidStartState(null, null);
      Operation op = testHost.startServiceSynchronously(createManagementVmTaskService, startState);
      assertThat(op.getStatusCode(), is(200));

      CreateManagementVmTaskService.State patchState = CreateManagementVmTaskService.buildPatch(
          TaskState.TaskStage.STARTED, CreateManagementVmTaskService.TaskState.SubStage.CREATE_VM, null);
      Field declaredField = patchState.getClass().getDeclaredField(fieldName);
      declaredField.set(patchState, ReflectionUtils.getDefaultAttributeValue(declaredField));

      Operation patchOp = Operation
          .createPatch(UriUtils.buildUri(testHost, TestHost.SERVICE_URI))
          .setBody(patchState);

      testHost.sendRequestAndWait(patchOp);
    }

    @DataProvider(name = "ImmutableFieldNames")
    public Object[][] getImmutableFieldNames() {
      return TestHelper.toDataProvidersList(
          ReflectionUtils.getAttributeNamesWithAnnotation(
              CreateManagementVmTaskService.State.class, Immutable.class));
    }
  }

  /**
   * This class implements end-to-end tests for the {@link CreateManagementVmTaskService} class.
   */
  public class EndToEndTest {

    private final Task failedTask = ApiTestUtils.createFailingTask(2, 1, "errorCode", "errorMessage");

    private ApiClientFactory apiClientFactory;
    private com.vmware.photon.controller.cloudstore.dcp.helpers.TestEnvironment cloudStoreEnvironment;
    private DeployerConfig deployerConfig;
    private ProjectApi projectApi;
    private CreateManagementVmTaskService.State startState;
    private TasksApi tasksApi;
    private TestEnvironment testEnvironment;
    private VmApi vmApi;
    private String vmId;

    @BeforeClass
    public void setUpClass() throws Throwable {
      apiClientFactory = mock(ApiClientFactory.class);
      cloudStoreEnvironment = com.vmware.photon.controller.cloudstore.dcp.helpers.TestEnvironment.create(1);
      deployerConfig = ConfigBuilder.build(DeployerConfig.class, this.getClass().getResource("/config.yml").getPath());
      TestHelper.setContainersConfig(deployerConfig);

      testEnvironment = new TestEnvironment.Builder()
          .apiClientFactory(apiClientFactory)
          .cloudServerSet(cloudStoreEnvironment.getServerSet())
          .deployerContext(deployerConfig.getDeployerContext())
          .hostCount(1)
          .build();
    }

    @BeforeMethod
    public void setUpTest() throws Throwable {

      ApiClient apiClient = mock(ApiClient.class);
      doReturn(apiClient).when(apiClientFactory).create();
      projectApi = mock(ProjectApi.class);
      doReturn(projectApi).when(apiClient).getProjectApi();
      tasksApi = mock(TasksApi.class);
      doReturn(tasksApi).when(apiClient).getTasksApi();
      vmApi = mock(VmApi.class);
      doReturn(vmApi).when(apiClient).getVmApi();
      vmId = UUID.randomUUID().toString();

      TestHelper.assertNoServicesOfType(cloudStoreEnvironment, FlavorService.State.class);
      TestHelper.assertNoServicesOfType(cloudStoreEnvironment, HostService.State.class);
      TestHelper.assertNoServicesOfType(testEnvironment, ContainerService.State.class);
      TestHelper.assertNoServicesOfType(testEnvironment, ContainerTemplateService.State.class);
      TestHelper.assertNoServicesOfType(testEnvironment, VmService.State.class);

      FlavorService.State diskFlavorState = TestHelper.createFlavor(cloudStoreEnvironment, "DISK_FLAVOR_NAME");
      HostService.State hostState = TestHelper.createHostService(cloudStoreEnvironment,
          Collections.singleton(UsageTag.MGMT.name()));
      FlavorService.State vmFlavorState = TestHelper.createFlavor(cloudStoreEnvironment, "VM_FLAVOR_NAME");

      VmService.State vmStartState = TestHelper.getVmServiceStartState(hostState);
      vmStartState.diskFlavorServiceLink = diskFlavorState.documentSelfLink;
      vmStartState.imageServiceLink = ImageServiceFactory.SELF_LINK + "/" + "IMAGE_ID";
      vmStartState.projectServiceLink = ProjectServiceFactory.SELF_LINK + "/" + "PROJECT_ID";
      vmStartState.vmFlavorServiceLink = vmFlavorState.documentSelfLink;
      VmService.State vmState = TestHelper.createVmService(testEnvironment, vmStartState);

      for (ContainersConfig.ContainerType containerType : ContainersConfig.ContainerType.values()) {

        //
        // Lightwave and the load balancer can't be placed on the same VM, so skip the load balancer here.
        //

        if (containerType == ContainersConfig.ContainerType.LoadBalancer) {
          continue;
        }

        ContainerTemplateService.State templateState = TestHelper.createContainerTemplateService(testEnvironment,
            deployerConfig.getContainersConfig().getContainerSpecs().get(containerType.name()));
        TestHelper.createContainerService(testEnvironment, templateState, vmState);
      }

      startState = buildValidStartState(null, null);
      startState.vmServiceLink = vmState.documentSelfLink;
      startState.controlFlags = null;
      startState.taskPollDelay = 10;
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      TestHelper.deleteServicesOfType(cloudStoreEnvironment, FlavorService.State.class);
      TestHelper.deleteServicesOfType(cloudStoreEnvironment, HostService.State.class);
      TestHelper.deleteServicesOfType(testEnvironment, ContainerService.State.class);
      TestHelper.deleteServicesOfType(testEnvironment, ContainerTemplateService.State.class);
      TestHelper.deleteServicesOfType(testEnvironment, VmService.State.class);
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      testEnvironment.stop();
      cloudStoreEnvironment.stop();
    }

    @Test
    public void testSuccess() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "STARTED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "COMPLETED"))
          .when(tasksApi)
          .getTaskAsync(eq("CREATE_VM_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockSetMetadataAsync("SET_METADATA_TASK_ID", vmId, "QUEUED"))
          .when(vmApi)
          .setMetadataAsync(anyString(), any(VmMetadata.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockGetTaskAsync("SET_METADATA_TASK_ID", vmId, "QUEUED"))
          .doAnswer(MockHelper.mockGetTaskAsync("SET_METADATA_TASK_ID", vmId, "STARTED"))
          .doAnswer(MockHelper.mockGetTaskAsync("SET_METADATA_TASK_ID", vmId, "COMPLETED"))
          .when(tasksApi)
          .getTaskAsync(eq("SET_METADATA_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      TestHelper.assertTaskStateFinished(finalState.taskState);
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.createVmTaskId, is("CREATE_VM_TASK_ID"));
      assertThat(finalState.createVmPollCount, is(3));
      assertThat(finalState.vmId, is(vmId));
      assertThat(finalState.updateVmMetadataTaskId, is("SET_METADATA_TASK_ID"));
      assertThat(finalState.updateVmMetadataPollCount, is(3));

      ArgumentCaptor<VmCreateSpec> createSpecCaptor = ArgumentCaptor.forClass(VmCreateSpec.class);

      verify(projectApi).createVmAsync(
          eq("PROJECT_ID"),
          createSpecCaptor.capture(),
          Matchers.<FutureCallback<Task>>any());

      assertThat(createSpecCaptor.getValue(), is(getExpectedCreateSpec()));

      verify(tasksApi, times(3)).getTaskAsync(
          eq("CREATE_VM_TASK_ID"),
          Matchers.<FutureCallback<Task>>any());

      ArgumentCaptor<VmMetadata> metadataCaptor = ArgumentCaptor.forClass(VmMetadata.class);

      verify(vmApi).setMetadataAsync(
          eq(vmId),
          metadataCaptor.capture(),
          Matchers.<FutureCallback<Task>>any());

      assertThat(metadataCaptor.getValue().getMetadata(), is(getExpectedMetadata()));

      verify(tasksApi, times(3)).getTaskAsync(
          eq("SET_METADATA_TASK_ID"),
          Matchers.<FutureCallback<Task>>any());

      VmService.State vmState = testEnvironment.getServiceState(startState.vmServiceLink, VmService.State.class);
      assertThat(vmState.vmId, is(vmId));
    }

    @Test
    public void testSuccessNoTaskPolling() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync("CREATE_VM_TASK_ID", vmId, "COMPLETED"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockSetMetadataAsync("SET_METADATA_TASK_ID", vmId, "COMPLETED"))
          .when(vmApi)
          .setMetadataAsync(anyString(), any(VmMetadata.class), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      TestHelper.assertTaskStateFinished(finalState.taskState);
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.createVmTaskId, nullValue());
      assertThat(finalState.createVmPollCount, is(0));
      assertThat(finalState.vmId, is(vmId));
      assertThat(finalState.updateVmMetadataTaskId, nullValue());
      assertThat(finalState.updateVmMetadataPollCount, is(0));

      ArgumentCaptor<VmCreateSpec> createSpecCaptor = ArgumentCaptor.forClass(VmCreateSpec.class);

      verify(projectApi).createVmAsync(
          eq("PROJECT_ID"),
          createSpecCaptor.capture(),
          Matchers.<FutureCallback<Task>>any());

      assertThat(createSpecCaptor.getValue(), is(getExpectedCreateSpec()));

      ArgumentCaptor<VmMetadata> captor = ArgumentCaptor.forClass(VmMetadata.class);

      verify(vmApi).setMetadataAsync(
          eq(vmId),
          captor.capture(),
          Matchers.<FutureCallback<Task>>any());

      assertThat(captor.getValue().getMetadata(), is(getExpectedMetadata()));

      VmService.State vmState = testEnvironment.getServiceState(startState.vmServiceLink, VmService.State.class);
      assertThat(vmState.vmId, is(vmId));
    }

    private VmCreateSpec getExpectedCreateSpec() {
      AttachedDiskCreateSpec bootDiskCreateSpec = new AttachedDiskCreateSpec();
      bootDiskCreateSpec.setName("NAME-bootdisk");
      bootDiskCreateSpec.setBootDisk(true);
      bootDiskCreateSpec.setFlavor("DISK_FLAVOR_NAME");
      bootDiskCreateSpec.setKind(EphemeralDisk.KIND);

      LocalitySpec hostLocalitySpec = new LocalitySpec();
      hostLocalitySpec.setId("hostAddress");
      hostLocalitySpec.setKind("host");

      LocalitySpec datastoreLocalitySpec = new LocalitySpec();
      datastoreLocalitySpec.setId("datastore1");
      datastoreLocalitySpec.setKind("datastore");

      LocalitySpec portGroupLocalitySpec = new LocalitySpec();
      portGroupLocalitySpec.setId("VM Network");
      portGroupLocalitySpec.setKind("portGroup");

      VmCreateSpec vmCreateSpec = new VmCreateSpec();
      vmCreateSpec.setName("NAME");
      vmCreateSpec.setFlavor("VM_FLAVOR_NAME");
      vmCreateSpec.setSourceImageId("IMAGE_ID");
      vmCreateSpec.setEnvironment(new HashMap<>());
      vmCreateSpec.setAttachedDisks(Collections.singletonList(bootDiskCreateSpec));
      vmCreateSpec.setAffinities(Arrays.asList(hostLocalitySpec, datastoreLocalitySpec, portGroupLocalitySpec));
      return vmCreateSpec;
    }

    private Map<String, String> getExpectedMetadata() {
      return Stream.of(ContainersConfig.ContainerType.values())
          .filter((containerType) -> containerType != ContainersConfig.ContainerType.LoadBalancer)
          .map((containerType) -> deployerConfig.getContainersConfig().getContainerSpecs().get(containerType.name()))
          .flatMap((containerSpec) -> containerSpec.getPortBindings().values().stream()
              .collect(Collectors.toMap(
                  (hostPort) -> "CONTAINER_" + hostPort,
                  (hostPort -> containerSpec.getServiceName())))
              .entrySet().stream())
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Test
    public void testCreateVmFailure() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "STARTED"))
          .doAnswer(MockHelper.mockGetTaskAsync(failedTask))
          .when(tasksApi)
          .getTaskAsync(eq("CREATE_VM_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.taskState.failure.statusCode, is(400));
      assertThat(finalState.taskState.failure.message, containsString(ApiUtils.getErrors(failedTask)));
      assertThat(finalState.createVmTaskId, is("CREATE_VM_TASK_ID"));
      assertThat(finalState.createVmPollCount, is(3));
    }

    @Test
    public void testCreateVmFailureNoTaskPolling() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync(failedTask))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.taskState.failure.statusCode, is(400));
      assertThat(finalState.taskState.failure.message, containsString(ApiUtils.getErrors(failedTask)));
      assertThat(finalState.createVmTaskId, nullValue());
      assertThat(finalState.createVmPollCount, is(0));
    }

    @Test
    public void testCreateVmExceptionInCreateVmCall() throws Throwable {

      doThrow(new IOException("I/O exception during createVmAsync call"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.taskState.failure.statusCode, is(400));
      assertThat(finalState.taskState.failure.message, containsString("I/O exception during createVmAsync call"));
      assertThat(finalState.createVmTaskId, nullValue());
      assertThat(finalState.createVmPollCount, is(0));
    }

    @Test
    public void testCreateVmExceptionInGetTaskCall() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      doThrow(new IOException("I/O exception during getTaskAsync call"))
          .when(tasksApi)
          .getTaskAsync(eq("CREATE_VM_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.taskState.failure.statusCode, is(400));
      assertThat(finalState.taskState.failure.message, containsString("I/O exception during getTaskAsync call"));
      assertThat(finalState.createVmTaskId, is("CREATE_VM_TASK_ID"));
      assertThat(finalState.createVmPollCount, is(1));
    }

    @Test
    public void testSetMetadataFailure() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "STARTED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "COMPLETED"))
          .when(tasksApi)
          .getTaskAsync(eq("CREATE_VM_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockSetMetadataAsync("SET_METADATA_TASK_ID", vmId, "QUEUED"))
          .when(vmApi)
          .setMetadataAsync(eq(vmId), any(VmMetadata.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockGetTaskAsync("SET_METADATA_TASK_ID", vmId, "QUEUED"))
          .doAnswer(MockHelper.mockGetTaskAsync("SET_METADATA_TASK_ID", vmId, "STARTED"))
          .doAnswer(MockHelper.mockGetTaskAsync(failedTask))
          .when(tasksApi)
          .getTaskAsync(eq("SET_METADATA_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.taskState.failure.statusCode, is(400));
      assertThat(finalState.taskState.failure.message, containsString(ApiUtils.getErrors(failedTask)));
      assertThat(finalState.createVmTaskId, is("CREATE_VM_TASK_ID"));
      assertThat(finalState.createVmPollCount, is(3));
      assertThat(finalState.updateVmMetadataTaskId, is("SET_METADATA_TASK_ID"));
      assertThat(finalState.updateVmMetadataPollCount, is(3));
    }

    @Test
    public void testSetMetadataFailureNoTaskPolling() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "STARTED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "COMPLETED"))
          .when(tasksApi)
          .getTaskAsync(eq("CREATE_VM_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockSetMetadataAsync(failedTask))
          .when(vmApi)
          .setMetadataAsync(eq(vmId), any(VmMetadata.class), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.taskState.failure.statusCode, is(400));
      assertThat(finalState.taskState.failure.message, containsString(ApiUtils.getErrors(failedTask)));
      assertThat(finalState.createVmTaskId, is("CREATE_VM_TASK_ID"));
      assertThat(finalState.createVmPollCount, is(3));
      assertThat(finalState.updateVmMetadataTaskId, nullValue());
      assertThat(finalState.updateVmMetadataPollCount, is(0));
    }

    @Test
    public void testSetMetadataExceptionInSetMetadataCall() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "STARTED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "COMPLETED"))
          .when(tasksApi)
          .getTaskAsync(eq("CREATE_VM_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      doThrow(new IOException("I/O exception during setMetadataAsync call"))
          .when(vmApi)
          .setMetadataAsync(eq(vmId), any(VmMetadata.class), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.taskState.failure.statusCode, is(400));
      assertThat(finalState.taskState.failure.message, containsString("I/O exception during setMetadataAsync call"));
      assertThat(finalState.createVmTaskId, is("CREATE_VM_TASK_ID"));
      assertThat(finalState.createVmPollCount, is(3));
      assertThat(finalState.updateVmMetadataTaskId, nullValue());
      assertThat(finalState.updateVmMetadataPollCount, is(0));
    }

    @Test
    public void testSetMetadataExceptionInGetTaskCall() throws Throwable {

      doAnswer(MockHelper.mockCreateVmAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .when(projectApi)
          .createVmAsync(anyString(), any(VmCreateSpec.class), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "QUEUED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "STARTED"))
          .doAnswer(MockHelper.mockGetTaskAsync("CREATE_VM_TASK_ID", vmId, "COMPLETED"))
          .when(tasksApi)
          .getTaskAsync(eq("CREATE_VM_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      doAnswer(MockHelper.mockSetMetadataAsync("SET_METADATA_TASK_ID", vmId, "QUEUED"))
          .when(vmApi)
          .setMetadataAsync(eq(vmId), any(VmMetadata.class), Matchers.<FutureCallback<Task>>any());

      doThrow(new IOException("I/O exception during getTaskAsync call"))
          .when(tasksApi)
          .getTaskAsync(eq("SET_METADATA_TASK_ID"), Matchers.<FutureCallback<Task>>any());

      CreateManagementVmTaskService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementVmTaskFactoryService.SELF_LINK,
              startState,
              CreateManagementVmTaskService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      assertThat(finalState.taskState.subStage, nullValue());
      assertThat(finalState.taskState.failure.statusCode, is(400));
      assertThat(finalState.taskState.failure.message, containsString("I/O exception during getTaskAsync call"));
      assertThat(finalState.createVmTaskId, is("CREATE_VM_TASK_ID"));
      assertThat(finalState.createVmPollCount, is(3));
      assertThat(finalState.updateVmMetadataTaskId, is("SET_METADATA_TASK_ID"));
      assertThat(finalState.updateVmMetadataPollCount, is(1));
    }
  }

  private CreateManagementVmTaskService.State buildValidStartState(
      TaskState.TaskStage taskStage,
      CreateManagementVmTaskService.TaskState.SubStage subStage) {

    CreateManagementVmTaskService.State startState = new CreateManagementVmTaskService.State();
    startState.vmServiceLink = "VM_SERVICE_LINK";
    startState.controlFlags = ControlFlags.CONTROL_FLAG_OPERATION_PROCESSING_DISABLED;

    if (taskStage != null) {
      startState.taskState = new CreateManagementVmTaskService.TaskState();
      startState.taskState.stage = taskStage;
      startState.taskState.subStage = subStage;
    }

    return startState;
  }
}
