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

package com.vmware.photon.controller.cloudstore.dcp.entity;

import com.vmware.photon.controller.api.DatastoreState;
import com.vmware.photon.controller.common.Constants;
import com.vmware.photon.controller.common.xenon.InitializationUtils;
import com.vmware.photon.controller.common.xenon.PatchUtils;
import com.vmware.photon.controller.common.xenon.ServiceUtils;
import com.vmware.photon.controller.common.xenon.ValidationUtils;
import com.vmware.photon.controller.common.xenon.deployment.MigrateDuringDeployment;
import com.vmware.photon.controller.common.xenon.migration.MigrateDuringUpgrade;
import com.vmware.photon.controller.common.xenon.migration.MigrationUtils;
import com.vmware.photon.controller.common.xenon.validation.DefaultBoolean;
import com.vmware.photon.controller.common.xenon.validation.Immutable;
import com.vmware.photon.controller.common.xenon.validation.NotNull;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.services.common.QueryTask;

import org.apache.commons.lang3.StringUtils;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This class implements a DCP micro-service which provides a plain data object
 * representing a datastore.
 *
 * The datastore document is currently created and updated only by the host
 * service. To create or mark the datastore as active, a POST has to be done.
 * To mark the datastore as missing, a PATCH has to be done.
 */
public class DatastoreService extends StatefulService {

  public static final String TAGS_KEY =
      QueryTask.QuerySpecification.buildCollectionItemName(DatastoreService.State.FIELD_NAME_TAGS);

  public DatastoreService() {
    super(State.class);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
    super.toggleOption(ServiceOption.INSTRUMENTATION, true);
  }

  @Override
  public void handleStart(Operation startOperation) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());
    try {
      State startState = startOperation.getBody(State.class);

      if (startState.state == null) {
        startState.state = DatastoreState.ACTIVE;
      }

      // If the datastore document is being created by the host service, then find the host ID and add it to the
      // reference list
      String hostId = getHostIdFromReferrer(startOperation);
      if (StringUtils.isNotBlank(hostId)) {

        startState.referenceList = new HashSet<>();
        startState.referenceList.add(hostId);
      }

      InitializationUtils.initialize(startState);
      validateState(startState);
      startOperation.complete();
    } catch (IllegalStateException t) {
      ServiceUtils.failOperationAsBadRequest(this, startOperation, t);
    } catch (Throwable t) {
      ServiceUtils.logSevere(this, t);
      startOperation.fail(t);
    }
  }

  @Override
  public void handlePut(Operation putOperation) {
    ServiceUtils.logInfo(this, "Handling put for service %s", getSelfLink());
    if (!putOperation.hasBody()) {
      putOperation.fail(new IllegalArgumentException("body is required"));
      return;
    }

    State currentState = getState(putOperation);
    State newState = putOperation.getBody(State.class);

    if (newState.state == null) {
      newState.state = DatastoreState.ACTIVE;
    }

    // On put, if the referrer is a host,
    // - If the datastore state is ACTIVE -> mark datastore as ACTIVE and add the host ID to the reference list
    String hostId = getHostIdFromReferrer(putOperation);
    if (StringUtils.isNotBlank(hostId)) {
      if (newState.referenceList == null) {
        newState.referenceList = new HashSet<>();
        newState.referenceList.addAll(currentState.referenceList);
      }

      if (newState.state == DatastoreState.ACTIVE) {
        newState.referenceList.add(hostId);
      }
    }

    if (ServiceDocument.equals(getDocumentTemplate().documentDescription, currentState, newState)) {
      putOperation.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
      putOperation.complete();
      return;
    }

    try {
      validateState(newState);
      validatePut(currentState, newState);
    } catch (IllegalStateException e) {
      ServiceUtils.failOperationAsBadRequest(this, putOperation, e);
      return;
    }

    setState(putOperation, newState);
    putOperation.complete();
  }

  private void validatePut(State currentState, State newState) {
    checkState(newState.id.equals(currentState.id));
    checkState(newState.name.equals(currentState.name));
    checkState(newState.type.equals(currentState.type));
  }

  @Override
  public void handlePatch(Operation patchOperation) {
    ServiceUtils.logInfo(this, "Handling patch for service %s", getSelfLink());
    try {
      State currentState = getState(patchOperation);
      validateState(currentState);
      State patchState = patchOperation.getBody(State.class);

      // If the patch operation comes from a host service,
      // - If the datastore state is marked as missing, remove the host id from the reference list
      // - If the reference list has no more hosts, mark the datastore as missing
      String hostId = getHostIdFromReferrer(patchOperation);
      if (StringUtils.isNotBlank(hostId)) {
        if (patchState.referenceList == null) {
          patchState.referenceList = new HashSet<>();
          patchState.referenceList.addAll(currentState.referenceList);
        }

        if (patchState.state == DatastoreState.MISSING && patchState.referenceList.contains(hostId)) {
          patchState.referenceList.remove(hostId);
          if (patchState.referenceList.size() == 0) {
            patchState.state = DatastoreState.MISSING;
          } else {
            patchState.state = DatastoreState.ACTIVE;
          }
        }
      }

      validatePatchState(currentState, patchState);
      applyPatch(currentState, patchState);
      validateState(currentState);
      patchOperation.complete();
    } catch (IllegalStateException t) {
      ServiceUtils.failOperationAsBadRequest(this, patchOperation, t);
    } catch (Throwable t) {
      ServiceUtils.logSevere(this, t);
      patchOperation.fail(t);
    }
  }

  @Override
  public void handleDelete(Operation deleteOperation) {
    State currentState = getState(deleteOperation);
    // Expire the document in a minute as we might recreate the document and we do not want to fail with document
    // deleted already exception. We are doing 1 minute instead of immediate because the delete to be replicated across
    // all the nodes.
    currentState.documentExpirationTimeMicros = ServiceUtils.computeExpirationTime(TimeUnit.MINUTES.toMicros(1));
    ServiceUtils.expireDocumentOnDelete(this, State.class, deleteOperation);
  }

  @Override
  public ServiceDocument getDocumentTemplate() {
    ServiceDocument template = super.getDocumentTemplate();
    ServiceUtils.setExpandedIndexing(template, State.FIELD_NAME_TAGS);
    return template;
  }

  private void validateState(State currentState) {
    ValidationUtils.validateState(currentState);
    ValidationUtils.validateEntitySelfLink(this, currentState.id);
  }

  private void validatePatchState(State startState, State patchState) {
    checkNotNull(patchState, "patch can not be null");
    ValidationUtils.validatePatch(startState, patchState);
  }

  private void applyPatch(State currentState, State patchState) {
    PatchUtils.patchState(currentState, patchState);
  }

  private String getHostIdFromReferrer(Operation operation) {
    if (operation.getReferer().getPath().contains(HostServiceFactory.SELF_LINK)) {
      String[] components = operation.getReferer().getPath().split("/");
      return components[components.length - 1];
    }
    return null;
  }

  /**
   * This class defines the document state associated with a single
   * {@link DatastoreService} instance.
   */
  @MigrateDuringUpgrade(transformationServicePath = MigrationUtils.REFLECTION_TRANSFORMATION_SERVICE_LINK,
      sourceFactoryServicePath = DatastoreServiceFactory.SELF_LINK,
      destinationFactoryServicePath = DatastoreServiceFactory.SELF_LINK,
      serviceName = Constants.CLOUDSTORE_SERVICE_NAME)
  @MigrateDuringDeployment(
      factoryServicePath = DatastoreServiceFactory.SELF_LINK,
      serviceName = Constants.CLOUDSTORE_SERVICE_NAME)
  public static class State extends ServiceDocument {

    public static final String FIELD_NAME_TAGS = "tags";
    public static final String FIELD_NAME_ID = "id";
    public static final String FIELD_NAME_TYPE = "type";

    /**
     * This value represents the hardware id, a.k.a the real name, of the datastore.
     */
    @NotNull
    @Immutable
    public String id;

    /**
     * This value represents the name of the datastore.
     */
    @NotNull
    @Immutable
    public String name;

    /**
     * This value represents the type of the datastore.
     */
    @NotNull
    @Immutable
    public String type;

    /**
     * This value represents the usage tag associated with the datastore.
     */
    public Set<String> tags;

    /**
     * Indicates whether this datastore is used as a image datastore. Chairman
     * sets this flag to true when it receives a register request from an agent
     * that specifies this datastore as the image datastore.
     * <p>
     * Note that chairman does not reset this field to false even when all the
     * agents that use this datastore as the image datastore un-register. If you
     * need to keep this field up-to-date, it must be cleaned up by a background
     * job..
     */
    @NotNull
    @DefaultBoolean(value = false)
    public Boolean isImageDatastore;

    /**
     * This value reperesents the set of active hosts through which this datastore
     * can be accessed.
     */
    public Set<String> referenceList;

    /**
     * This value represents the current state of the datastore.
     */
    @NotNull
    public DatastoreState state;
  }
}
