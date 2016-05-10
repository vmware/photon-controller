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

package com.vmware.photon.controller.cloudstore.dcp.task;

import com.vmware.photon.controller.cloudstore.dcp.entity.EntityLockService;
import com.vmware.photon.controller.cloudstore.dcp.entity.EntityLockServiceFactory;
import com.vmware.photon.controller.cloudstore.dcp.entity.TaskService;
import com.vmware.photon.controller.cloudstore.dcp.entity.TaskServiceFactory;
import com.vmware.photon.controller.common.xenon.InitializationUtils;
import com.vmware.photon.controller.common.xenon.PatchUtils;
import com.vmware.photon.controller.common.xenon.ServiceUtils;
import com.vmware.photon.controller.common.xenon.ValidationUtils;
import com.vmware.photon.controller.common.xenon.upgrade.NoMigrationDuringUpgrade;
import com.vmware.photon.controller.common.xenon.validation.DefaultBoolean;
import com.vmware.photon.controller.common.xenon.validation.DefaultInteger;
import com.vmware.photon.controller.common.xenon.validation.DefaultLong;
import com.vmware.photon.controller.common.xenon.validation.DefaultTaskState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.OperationSequence;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.ServiceUriPaths;
import static com.vmware.xenon.common.OperationJoin.JoinedCompletionHandler;

import static com.google.common.base.Preconditions.checkState;

import java.net.URI;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Class implementing service to remove dangling entity locks from the cloud store.
 * Service will query entity locks with pagination and only process the first page to limit load on the network.
 */
public class EntityLockCleanerService extends StatefulService {

  public static final Integer DEFAULT_PAGE_LIMIT = 1000;
  public static final long DEFAULT_DELETE_WATERMARK_TIME_MILLIS = 5 * 60 * 1000L;
  private static final String DOCUMENT_UPDATE_TIME_MICROS = "documentUpdateTimeMicros";

  public EntityLockCleanerService() {
    super(State.class);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
    super.toggleOption(ServiceOption.INSTRUMENTATION, true);
  }

  @Override
  public void handleStart(Operation start) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());
    State s = start.getBody(State.class);
    initializeState(s);
    validateState(s);
    start.setBody(s).complete();

    processStart(s);
  }

  @Override
  public void handlePatch(Operation patch) {
    ServiceUtils.logInfo(this, "Handling patch for service %s", getSelfLink());
    State currentState = getState(patch);
    State patchState = patch.getBody(State.class);

    validatePatch(currentState, patchState);
    applyPatch(currentState, patchState);
    validateState(currentState);
    patch.complete();

    processPatch(currentState);
  }

  /**
   * Processes a patch request to update the execution stage.
   *
   * @param current
   */
  protected void handleStartedStage(final State current) {
    // Handle task sub-state.
    switch (current.taskState.subStage) {
      case DELETE_ENTITY_LOCKS_WITH_DELETED_ENTITIES:
        sendStageProgressPatch(current, com.vmware.xenon.common.TaskState.TaskStage.STARTED,
            TaskState.SubStage.RELEASE_ENTITY_LOCKS_WITH_INACTIVE_TASKS);
        break;
      case RELEASE_ENTITY_LOCKS_WITH_INACTIVE_TASKS:
        processUnreleasedEntityLocks(current);
        break;
      default:
        throw new IllegalStateException("Un-supported substage" + current.taskState.subStage.toString());
    }
  }

  /**
   * Initialize state with defaults.
   *
   * @param current
   */
  private void initializeState(State current) {
    InitializationUtils.initialize(current);

    if (current.taskState.stage == TaskState.TaskStage.STARTED && current.taskState.subStage == null) {
      current.taskState.subStage = TaskState.SubStage.DELETE_ENTITY_LOCKS_WITH_DELETED_ENTITIES;
    }

    if (current.documentExpirationTimeMicros <= 0) {
      current.documentExpirationTimeMicros =
          ServiceUtils.computeExpirationTime(ServiceUtils.DEFAULT_DOC_EXPIRATION_TIME_MICROS);
    }
  }

  /**
   * Validate service state coherence.
   *
   * @param current
   */
  private void validateState(State current) {
    ValidationUtils.validateState(current);
    switch (current.taskState.stage) {
      case STARTED:
        checkState(current.taskState.subStage != null, "subStage cannot be null");
        switch (current.taskState.subStage) {
          case DELETE_ENTITY_LOCKS_WITH_DELETED_ENTITIES:
          case RELEASE_ENTITY_LOCKS_WITH_INACTIVE_TASKS:
            break;
          default:
            checkState(false, "unsupported sub-state: " + current.taskState.subStage.toString());
        }
        break;
      case CREATED:
      case FAILED:
      case FINISHED:
      case CANCELLED:
        checkState(current.taskState.subStage == null, "Invalid stage update. subStage must be null");
        break;
      default:
        checkState(false, "cannot process patches in state: " + current.taskState.stage.toString());
    }
  }

  /**
   * This method applies a patch to a state object.
   *
   * @param current Supplies the start state object.
   * @param patch   Supplies the patch state object.
   */
  private State applyPatch(State current, State patch) {
    ServiceUtils.logInfo(this, "Moving to stage %s", patch.taskState.stage);
    PatchUtils.patchState(current, patch);
    return current;
  }

  /**
   * This method checks a patch object for validity against a document state object.
   *
   * @param current Supplies the start state object.
   * @param patch   Supplies the patch state object.
   */
  private void validatePatch(State current, State patch) {
    ValidationUtils.validatePatch(current, patch);
    ValidationUtils.validateTaskStageProgression(current.taskState, patch.taskState);

    if (patch.taskState != null) {
      if (patch.taskState.subStage != null && current.taskState.subStage != null) {
        checkState(patch.taskState.subStage.ordinal() >= current.taskState.subStage.ordinal(),
            "Invalid stage update. 'subStage' cannot move back.");
      }
    }

  }

  /**
   * Does any additional processing after the start operation has been completed.
   *
   * @param current
   */
  private void processStart(final State current) {
    try {
      if (!isFinalStage(current)) {
        sendStageProgressPatch(current, current.taskState.stage, current.taskState.subStage);
      }
    } catch (Throwable e) {
      failTask(e);
    }
  }

  /**
   * Does any additional processing after the patch operation has been completed.
   *
   * @param current
   */
  private void processPatch(final State current) {
    try {
      switch (current.taskState.stage) {
        case STARTED:
          handleStartedStage(current);
          break;

        case FAILED:
        case FINISHED:
        case CANCELLED:
          break;

        default:
          this.failTask(
              new IllegalStateException(
                  String.format("Un-expected stage: %s", current.taskState.stage))
          );
      }
    } catch (Throwable e) {
      failTask(e);
    }
  }

  /**
   * Retrieves the first page of entity locks and kicks of the subsequent processing.
   *
   * @param current
   */
  private void processUnreleasedEntityLocks(final State current) {
    Operation queryEntityLocksPagination = Operation
        .createPost(UriUtils.buildUri(getHost(), ServiceUriPaths.CORE_LOCAL_QUERY_TASKS))
        .setBody(buildEntityLockQuery(current));

    Operation getFirstPageOfEntityLocks = Operation.createGet(UriUtils.buildUri(getHost(), this.getSelfLink()));

    OperationSequence
        .create(queryEntityLocksPagination)
        .setCompletion(((ops, failures) -> {
          if (failures != null) {
            failTask(failures.values().iterator().next());
            return;
          }
          Operation op = ops.get(queryEntityLocksPagination.getId());
          ServiceDocumentQueryResult results = op.getBody(QueryTask.class).results;
          if (results.nextPageLink != null) {
            getFirstPageOfEntityLocks.setUri(UriUtils.buildUri(getHost(), results.nextPageLink, null));
          } else {
            ServiceUtils.logInfo(this, "No entityLocks found.");
          }

        }))
        .next(getFirstPageOfEntityLocks)
        .setCompletion((ops, throwable) -> {
          if (throwable != null) {
            failTask(throwable.values().iterator().next());
            return;
          }
          URI selfLink = UriUtils.buildUri(getHost(), this.getSelfLink());
          URI entityLocksPageLink = getFirstPageOfEntityLocks.getUri();
          if (!selfLink.equals(entityLocksPageLink)) {
            Operation op = ops.get(getFirstPageOfEntityLocks.getId());

            List<EntityLockService.State> entityLockList =
                parseEntityLockQueryResults(op.getBody(QueryTask.class));

            if (entityLockList.size() == 0) {
              ServiceUtils.logInfo(EntityLockCleanerService.this, "No entityLocks found.");
              finishTask(current);
              return;
            }
            releaseEntityLocksWithInactiveTasks(current, entityLockList);
          } else {
            finishTask(current);
            return;
          }
        })
        .sendWith(this);
  }

  private QueryTask buildEntityLockQuery(final State current) {
    Long durationInMicros = Utils.getNowMicrosUtc() - current.entityLockDeleteWatermarkTimeInMicros;

    QueryTask.Query kindClause = new QueryTask.Query()
        .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
        .setTermMatchValue(Utils.buildKind(EntityLockService.State.class));

    QueryTask.QuerySpecification querySpec = new QueryTask.QuerySpecification();

    QueryTask.NumericRange range = QueryTask.NumericRange.createLessThanRange(durationInMicros);
    range.precisionStep = Integer.MAX_VALUE;
    QueryTask.Query timeClause = new QueryTask.Query()
        .setTermPropertyName(DOCUMENT_UPDATE_TIME_MICROS)
        .setNumericRange(range);

    querySpec.query
        .addBooleanClause(kindClause)
        .addBooleanClause(timeClause);

    querySpec.options = EnumSet.of(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT);
    querySpec.resultLimit = DEFAULT_PAGE_LIMIT;
    return QueryTask.create(querySpec).setDirect(true);
  }

  private void releaseEntityLocksWithInactiveTasks(final State current, List<EntityLockService.State> entityLockList) {
    Collection<Operation> getTaskOperations = getTasksAssociatedWithEntityLocks(entityLockList);
    JoinedCompletionHandler processReleaseEntityLocksHandler =
        releaseEntityLocksAssociatedWithInactiveTasks(current);
    OperationJoin join = OperationJoin.create(getTaskOperations);
    join.setCompletion(processReleaseEntityLocksHandler);
    join.sendWith(this);
  }

  private Collection<Operation> getTasksAssociatedWithEntityLocks(
      List<EntityLockService.State> entityLockList) {
    Collection<Operation> getTaskOperations = new LinkedList<>();

    for (EntityLockService.State entityLock : entityLockList) {
      if (entityLock.ownerTaskId != null) {
        Operation getTaskOperation = Operation
            .createGet(UriUtils.buildUri(getHost(), TaskServiceFactory.SELF_LINK + "/" + entityLock.ownerTaskId))
            .setReferer(UriUtils.buildUri(getHost(), getSelfLink()));

        getTaskOperations.add(getTaskOperation);
      } else {
        ServiceUtils.logSevere(this, "Found entity lock with null taskId. EntityLock Id: %s", entityLock.entityId);
      }
    }

    return getTaskOperations;
  }

  private JoinedCompletionHandler releaseEntityLocksAssociatedWithInactiveTasks(final State finishPatch) {
    JoinedCompletionHandler releaseLockResponseHandler = getEntityLockReleaseResponseHandler(finishPatch);
    return (ops, failures) -> {
      if (failures != null && !failures.isEmpty()) {
        failTask(failures.values().iterator().next());
        return;
      }

      Collection<Operation> releaseLockOperations = getReleaseLockOperationsForEntityLocks(ops);

      finishPatch.danglingEntityLocks = releaseLockOperations.size();
      if (releaseLockOperations.size() == 0) {
        ServiceUtils.logInfo(this, "No unreleased entityLocks found.");
        finishPatch.releasedEntityLocks = 0;
        finishTask(finishPatch);
        return;
      }

      OperationJoin join = OperationJoin.create(releaseLockOperations);
      join.setCompletion(releaseLockResponseHandler);
      join.sendWith(this);
    };
  }

  private Collection<Operation> getReleaseLockOperationsForEntityLocks(Map<Long, Operation> ops) {
    Collection<Operation> releaseLockOperations = new LinkedList<>();

    for (Operation op : ops.values()) {
      TaskService.State task = op.getBody(TaskService.State.class);
      if (task.state != TaskService.State.TaskState.QUEUED &&
          task.state != TaskService.State.TaskState.STARTED) {
        ServiceUtils.logSevere(this, "Deleting a dangling EntityLock. Investigation needed on associated " +
                "TaskService. EntityLock Id: %s, TaskService documentSelfLink:  %s",
            task.entityId,
            task.documentSelfLink);

        EntityLockService.State state = new EntityLockService.State();
        state.ownerTaskId = ServiceUtils.getIDFromDocumentSelfLink(task.documentSelfLink);
        state.entityId = task.entityId;
        state.entityKind = task.entityKind;
        state.documentSelfLink = EntityLockServiceFactory.SELF_LINK + "/" + task.entityId;
        state.lockOperation = EntityLockService.State.LockOperation.RELEASE;
        Operation releaseLockOperation = Operation
            .createPut(UriUtils.buildUri(getHost(), EntityLockServiceFactory.SELF_LINK + "/" + state.entityId))
            .setReferer(UriUtils.buildUri(getHost(), getSelfLink()))
            .setBody(state);

        releaseLockOperations.add(releaseLockOperation);
      }
    }
    return releaseLockOperations;
  }

  private JoinedCompletionHandler getEntityLockReleaseResponseHandler(final State finishPatch) {
    return (ops, failures) -> {
      if (failures != null && !failures.isEmpty()) {
        this.failTask(failures.values().iterator().next());
        return;
      }
      finishPatch.releasedEntityLocks = ops.size();
      this.finishTask(finishPatch);
    };
  }


  private List<EntityLockService.State> parseEntityLockQueryResults(QueryTask result) {
    List<EntityLockService.State> entityLockList = new LinkedList<>();

    if (result != null && result.results != null && result.results.documentCount > 0) {
      for (Map.Entry<String, Object> doc : result.results.documents.entrySet()) {
        entityLockList.add(
            Utils.fromJson(doc.getValue(), EntityLockService.State.class));
      }
    }

    return entityLockList;
  }

  /**
   * Determines if the task is in a final state.
   *
   * @param s
   * @return
   */
  private boolean isFinalStage(State s) {
    return s.taskState.stage == TaskState.TaskStage.FINISHED ||
        s.taskState.stage == TaskState.TaskStage.FAILED ||
        s.taskState.stage == TaskState.TaskStage.CANCELLED;
  }

  private void finishTask(final State patch) {
    ServiceUtils.logInfo(this, "Finished deleting unreleased entityLocks.");
    if (patch.taskState == null) {
      patch.taskState = new TaskState();
    }
    patch.taskState.stage = TaskState.TaskStage.FINISHED;
    patch.taskState.subStage = null;

    this.sendSelfPatch(patch);
  }

  /**
   * Moves the service into the FAILED state.
   *
   * @param e
   */
  private void failTask(Throwable e) {
    ServiceUtils.logSevere(this, e);
    this.sendSelfPatch(buildPatch(TaskState.TaskStage.FAILED, null, e));
  }

  /**
   * Send a patch message to ourselves to update the execution stage.
   *
   * @param stage
   */
  private void sendStageProgressPatch(final State current, TaskState.TaskStage stage, TaskState.SubStage subStage) {
    if (current.isSelfProgressionDisabled) {
      ServiceUtils.logInfo(this, "Skipping patch handling (disabled)");
      return;
    }

    this.sendSelfPatch(buildPatch(stage, subStage, null));
  }

  /**
   * Send a patch message to ourselves to update the execution stage.
   *
   * @param state
   */
  private void sendSelfPatch(State state) {
    Operation patch = Operation
        .createPatch(UriUtils.buildUri(getHost(), getSelfLink()))
        .setBody(state);
    this.sendRequest(patch);
  }

  /**
   * Build a state object that can be used to submit a stage progress
   * self patch.
   *
   * @param stage
   * @param e
   * @return
   */
  private State buildPatch(TaskState.TaskStage stage, TaskState.SubStage subStage, Throwable e) {
    State s = new State();
    s.taskState = new TaskState();
    s.taskState.stage = stage;
    s.taskState.subStage = subStage;

    if (e != null) {
      s.taskState.failure = Utils.toServiceErrorResponse(e);
    }

    return s;
  }

  /**
   * Service execution stages.
   */
  public static class TaskState extends com.vmware.xenon.common.TaskState {
    /**
     * The execution substage.
     */
    public SubStage subStage;

    /**
     * Execution sub-stage.
     */
    public static enum SubStage {
      DELETE_ENTITY_LOCKS_WITH_DELETED_ENTITIES,
      RELEASE_ENTITY_LOCKS_WITH_INACTIVE_TASKS,
    }
  }

  /**
   * Durable service state data.
   */
  @NoMigrationDuringUpgrade
  public static class State extends ServiceDocument {

    /**
     * Service execution stage.
     */
    @DefaultTaskState(value = TaskState.TaskStage.STARTED)
    public TaskState taskState;

    /**
     * The number of entity locks to delete.
     */
    @DefaultInteger(value = 0)
    public Integer danglingEntityLocks;

    /**
     * The number of entity locks that were deleted successfully.
     */
    @DefaultInteger(value = 0)
    public Integer releasedEntityLocks;

    /**
     * Flag that controls if we should self patch to make forward progress.
     */
    @DefaultBoolean(value = false)
    public Boolean isSelfProgressionDisabled;

    /**
     * Duration that controls how old the entity locks should be for cleaning.
     */
    @DefaultLong(value = DEFAULT_DELETE_WATERMARK_TIME_MILLIS)
    public Long entityLockDeleteWatermarkTimeInMicros;

  }
}
