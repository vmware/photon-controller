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

package com.vmware.photon.controller.apibackend.workflows;

import com.vmware.photon.controller.apibackend.exceptions.AssignFloatingIpToVmException;
import com.vmware.photon.controller.apibackend.servicedocuments.AssignFloatingIpToVmWorkflowDocument;
import com.vmware.photon.controller.apibackend.utils.ServiceHostUtils;
import com.vmware.photon.controller.cloudstore.xenon.entity.DhcpSubnetService;
import com.vmware.photon.controller.cloudstore.xenon.entity.IpLeaseService;
import com.vmware.photon.controller.cloudstore.xenon.entity.VirtualNetworkService;
import com.vmware.photon.controller.cloudstore.xenon.entity.VmService;
import com.vmware.photon.controller.cloudstore.xenon.entity.VmServiceFactory;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.OperationUtils;
import com.vmware.photon.controller.common.xenon.ServiceUriPaths;
import com.vmware.photon.controller.common.xenon.ServiceUtils;
import com.vmware.photon.controller.nsxclient.apis.LogicalRouterApi;
import com.vmware.photon.controller.nsxclient.models.NatRule;
import com.vmware.photon.controller.nsxclient.models.NatRuleCreateSpec;
import com.vmware.photon.controller.nsxclient.utils.NameUtils;
import com.vmware.xenon.common.FactoryService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.TaskState;

import com.google.common.util.concurrent.FutureCallback;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;

/**
 * Implements an Xenon service that represents a workflow to assign a floating IP to a VM.
 */
public class AssignFloatingIpToVmWorkflowService extends BaseWorkflowService<AssignFloatingIpToVmWorkflowDocument,
    AssignFloatingIpToVmWorkflowDocument.TaskState, AssignFloatingIpToVmWorkflowDocument.TaskState.SubStage> {

  public static final String FACTORY_LINK = ServiceUriPaths.APIBACKEND_ROOT + "/assign-floating-ip";

  public static FactoryService createFactory() {
    return FactoryService.create(AssignFloatingIpToVmWorkflowService.class, AssignFloatingIpToVmWorkflowDocument.class);
  }

  public AssignFloatingIpToVmWorkflowService() {
    super(AssignFloatingIpToVmWorkflowDocument.class,
        AssignFloatingIpToVmWorkflowDocument.TaskState.class,
        AssignFloatingIpToVmWorkflowDocument.TaskState.SubStage.class);
  }

  @Override
  public void handleCreate(Operation createOperation) {
    ServiceUtils.logInfo(this, "Creating service %s", getSelfLink());
    AssignFloatingIpToVmWorkflowDocument state =
        createOperation.getBody(AssignFloatingIpToVmWorkflowDocument.class);

    try {
      initializeState(state);
      validateState(state);

      if (ControlFlags.isOperationProcessingDisabled(state.controlFlags) ||
          ControlFlags.isHandleCreateDisabled(state.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping create operation processing (disabled)");
        createOperation.complete();
        return;
      }

      getVirtualNetwork(state, createOperation);
    } catch (Throwable t) {
      ServiceUtils.logSevere(this, t);
      if (!OperationUtils.isCompleted(createOperation)) {
        createOperation.fail(t);
      }
    }
  }

  @Override
  public void handleStart(Operation startOperation) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());
    AssignFloatingIpToVmWorkflowDocument state = startOperation.getBody(AssignFloatingIpToVmWorkflowDocument.class);

    try {
      initializeState(state);
      validateStartState(state);

      startOperation.setBody(state).complete();

      if (ControlFlags.isOperationProcessingDisabled(state.controlFlags) ||
          ControlFlags.isHandleStartDisabled(state.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
        return;
      }

      start(state);
    } catch (Throwable t) {
      ServiceUtils.logSevere(this, t);
      if (!OperationUtils.isCompleted(startOperation)) {
        startOperation.fail(t);
      }
    }
  }

  @Override
  public void handlePatch(Operation patchOperation) {
    ServiceUtils.logInfo(this, "Handling patch %s", getSelfLink());

    try {
      AssignFloatingIpToVmWorkflowDocument currentState = getState(patchOperation);
      AssignFloatingIpToVmWorkflowDocument patchState =
          patchOperation.getBody(AssignFloatingIpToVmWorkflowDocument.class);
      validatePatchState(currentState, patchState);

      applyPatch(currentState, patchState);
      validateState(currentState);
      patchOperation.complete();

      if (ControlFlags.isOperationProcessingDisabled(currentState.controlFlags) ||
          ControlFlags.isHandlePatchDisabled(currentState.controlFlags) ||
          TaskState.TaskStage.STARTED != currentState.taskState.stage) {
        ServiceUtils.logInfo(this, "Skipping patch operation processing (disabled)");
        return;
      }

      processPatch(currentState);
    } catch (Throwable t) {
      ServiceUtils.logSevere(this, t);
      if (!OperationUtils.isCompleted(patchOperation)) {
        patchOperation.fail(t);
      }
    }
  }

  private void processPatch(AssignFloatingIpToVmWorkflowDocument state) {
    try {
      switch (state.taskState.subStage) {
        case GET_VM_PRIVATE_IP_AND_MAC:
          getVmPrivateIp(state);
          break;
        case ALLOCATE_VM_FLOATING_IP:
          allocateVmFloatingIp(state);
          break;
        case CREATE_NAT_RULE:
          createNatRule(state);
          break;
      }
    } catch (Throwable t) {
      fail(state, t);
    }
  }

  private void getVmPrivateIp(AssignFloatingIpToVmWorkflowDocument state) throws Throwable {
    ServiceHostUtils.getCloudStoreHelper(getHost())
        .createGet(VmServiceFactory.SELF_LINK + "/" + state.vmId)
        .setCompletion((op, ex) -> {
          if (ex != null) {
            fail(state, ex);
            return;
          }

          try {
            VmService.State vmState = op.getBody(VmService.State.class);
            if (!vmState.networkInfo.containsKey(state.networkId)) {
              throw new AssignFloatingIpToVmException(
                  "VM " + state.vmId + " does not belong to network " + state.networkId);
            }

            VmService.NetworkInfo vmNetworkInfo = vmState.networkInfo.get(state.networkId);
            if (StringUtils.isBlank(vmNetworkInfo.privateIpAddress)) {
              throw new AssignFloatingIpToVmException(
                  "VM " + state.vmId + " does not have private IP on network " + state.networkId);
            }

            if (StringUtils.isBlank(vmNetworkInfo.macAddress)) {
              throw new AssignFloatingIpToVmException(
                  "VM " + state.vmId + " does not have a MAC address on network " + state.networkId);
            }

            AssignFloatingIpToVmWorkflowDocument patchState = buildPatch(
                TaskState.TaskStage.STARTED,
                AssignFloatingIpToVmWorkflowDocument.TaskState.SubStage.ALLOCATE_VM_FLOATING_IP);
            patchState.vmPrivateIpAddress = vmNetworkInfo.privateIpAddress;
            patchState.vmMacAddress = vmNetworkInfo.macAddress;

            progress(state, patchState);
          } catch (Throwable t) {
            fail(state, t);
          }
        })
        .sendWith(this);
  }

  private void allocateVmFloatingIp(AssignFloatingIpToVmWorkflowDocument state) throws Throwable {
    DhcpSubnetService.IpOperationPatch allocateIp = new DhcpSubnetService.IpOperationPatch(
        DhcpSubnetService.IpOperationPatch.Kind.AllocateIpToMac,
        state.vmMacAddress);

    ServiceHostUtils.getCloudStoreHelper(getHost())
        .createPatch(DhcpSubnetService.SINGLETON_LINK)
        .setBody(allocateIp)
        .setCompletion((op, ex) -> {
          if (ex != null) {
            fail(state, ex);
            return;
          }

          try {
            DhcpSubnetService.IpOperationPatch allocateIpResult = op.getBody(DhcpSubnetService.IpOperationPatch.class);
            allocateVmFloatingIp(state, allocateIpResult.ipLeaseId);
          } catch (Throwable t) {
            fail(state, t);
          }
        })
        .sendWith(this);
  }

  private void allocateVmFloatingIp(AssignFloatingIpToVmWorkflowDocument state, String ipLeaseId) throws Throwable {
    ServiceHostUtils.getCloudStoreHelper(getHost())
        .createGet(IpLeaseService.FACTORY_LINK + "/" + ipLeaseId)
        .setCompletion((op, ex) -> {
          if (ex != null) {
            fail(state, ex);
            return;
          }

          try {
            IpLeaseService.State ipLeaseState = op.getBody(IpLeaseService.State.class);

            AssignFloatingIpToVmWorkflowDocument patchState = buildPatch(
                TaskState.TaskStage.STARTED,
                AssignFloatingIpToVmWorkflowDocument.TaskState.SubStage.CREATE_NAT_RULE);
            patchState.vmFloatingIpAddress = ipLeaseState.ip;

            progress(state, patchState);
          } catch (Throwable t) {
            fail(state, t);
          }

        })
        .sendWith(this);
  }

  private void createNatRule(AssignFloatingIpToVmWorkflowDocument state) throws Throwable {
    LogicalRouterApi logicalRouterApi = ServiceHostUtils.getNsxClient(getHost(),
        state.nsxAddress,
        state.nsxUsername,
        state.nsxPassword)
        .getLogicalRouterApi();

    NatRuleCreateSpec natRuleCreateSpec = new NatRuleCreateSpec();
    natRuleCreateSpec.setDisplayName(NameUtils.getDnatRuleName(state.vmPrivateIpAddress));
    natRuleCreateSpec.setDescription(NameUtils.getDnatRuleDescription(state.vmPrivateIpAddress));
    natRuleCreateSpec.setMatchDestinationNetwork(state.vmFloatingIpAddress);
    natRuleCreateSpec.setTranslatedNetwork(state.vmPrivateIpAddress);

    logicalRouterApi.createNatRule(state.taskServiceEntity.logicalRouterId,
        natRuleCreateSpec,
        new FutureCallback<NatRule>() {
          @Override
          public void onSuccess(NatRule result) {
            try {
              if (state.taskServiceEntity.vmIdToNatRuleIdMap == null) {
                state.taskServiceEntity.vmIdToNatRuleIdMap = new HashMap<>();
              }

              state.taskServiceEntity.vmIdToNatRuleIdMap.put(state.vmId, result.getId());
              updateVirtualNetwork(state);
            } catch (Throwable t) {
              fail(state, t);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            fail(state, t);
          }
        });
  }

  /**
   * Gets a VirtualNetworkService.State from {@link com.vmware.photon.controller.cloudstore.xenon.entity
   * .VirtualNetworkService.State} entity in cloud-store.
   */
  private void getVirtualNetwork(AssignFloatingIpToVmWorkflowDocument state, Operation operation) {
    ServiceHostUtils.getCloudStoreHelper(getHost())
        .createGet(VirtualNetworkService.FACTORY_LINK + "/" + state.networkId)
        .setCompletion((op, ex) -> {
          if (ex != null) {
            operation.fail(ex);
            fail(state, ex);
            return;
          }

          state.taskServiceEntity = op.getBody(VirtualNetworkService.State.class);
          create(state, operation);
        })
        .sendWith(this);
  }

  /**
   * Updates a VirtualNetworkService.State entity in cloud-store.
   */
  private void updateVirtualNetwork(AssignFloatingIpToVmWorkflowDocument state) {
    VirtualNetworkService.State virtualNetworkPatchState = new VirtualNetworkService.State();
    virtualNetworkPatchState.vmIdToNatRuleIdMap = state.taskServiceEntity.vmIdToNatRuleIdMap;

    ServiceHostUtils.getCloudStoreHelper(getHost())
        .createPatch(state.taskServiceEntity.documentSelfLink)
        .setBody(virtualNetworkPatchState)
        .setCompletion((op, ex) -> {
          if (ex != null) {
            fail(state, ex);
            return;
          }

          try {
            AssignFloatingIpToVmWorkflowDocument patchState = buildPatch(
                TaskState.TaskStage.FINISHED,
                null);
            patchState.taskServiceEntity = state.taskServiceEntity;
            finish(state, patchState);
          } catch (Throwable t) {
            fail(state, t);
          }
        })
        .sendWith(this);

  }
}
