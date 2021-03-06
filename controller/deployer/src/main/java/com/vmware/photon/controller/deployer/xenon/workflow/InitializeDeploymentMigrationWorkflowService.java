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

package com.vmware.photon.controller.deployer.xenon.workflow;

import com.vmware.photon.controller.api.model.HostState;
import com.vmware.photon.controller.cloudstore.xenon.entity.DeploymentServiceFactory;
import com.vmware.photon.controller.cloudstore.xenon.entity.HostService;
import com.vmware.photon.controller.cloudstore.xenon.entity.HostServiceFactory;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.InitializationUtils;
import com.vmware.photon.controller.common.xenon.QueryTaskUtils;
import com.vmware.photon.controller.common.xenon.ServiceUriPaths;
import com.vmware.photon.controller.common.xenon.ServiceUtils;
import com.vmware.photon.controller.common.xenon.TaskUtils;
import com.vmware.photon.controller.common.xenon.ValidationUtils;
import com.vmware.photon.controller.common.xenon.deployment.NoMigrationDuringDeployment;
import com.vmware.photon.controller.common.xenon.migration.NoMigrationDuringUpgrade;
import com.vmware.photon.controller.common.xenon.migration.UpgradeInformation;
import com.vmware.photon.controller.common.xenon.validation.DefaultInteger;
import com.vmware.photon.controller.common.xenon.validation.DefaultTaskState;
import com.vmware.photon.controller.common.xenon.validation.Immutable;
import com.vmware.photon.controller.common.xenon.validation.NotNull;
import com.vmware.photon.controller.common.xenon.validation.Positive;
import com.vmware.photon.controller.common.xenon.validation.WriteOnce;
import com.vmware.photon.controller.deployer.xenon.DeployerServiceGroup;
import com.vmware.photon.controller.deployer.xenon.entity.VibFactoryService;
import com.vmware.photon.controller.deployer.xenon.entity.VibService;
import com.vmware.photon.controller.deployer.xenon.task.ChildTaskAggregatorFactoryService;
import com.vmware.photon.controller.deployer.xenon.task.ChildTaskAggregatorService;
import com.vmware.photon.controller.deployer.xenon.task.CopyStateTaskFactoryService;
import com.vmware.photon.controller.deployer.xenon.task.CopyStateTaskService;
import com.vmware.photon.controller.deployer.xenon.task.CopyStateTriggerTaskFactoryService;
import com.vmware.photon.controller.deployer.xenon.task.CopyStateTriggerTaskService;
import com.vmware.photon.controller.deployer.xenon.task.CopyStateTriggerTaskService.ExecutionState;
import com.vmware.photon.controller.deployer.xenon.task.MigrationStatusUpdateTriggerFactoryService;
import com.vmware.photon.controller.deployer.xenon.task.MigrationStatusUpdateTriggerService;
import com.vmware.photon.controller.deployer.xenon.task.UploadVibTaskFactoryService;
import com.vmware.photon.controller.deployer.xenon.task.UploadVibTaskService;
import com.vmware.photon.controller.deployer.xenon.util.HostUtils;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.CompletionHandler;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.OperationSequence;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.NodeGroupService;
import com.vmware.xenon.services.common.NodeState;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.QueryTask.Query;

import com.google.common.annotations.VisibleForTesting;
import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;

import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class implements a Xenon micro-service which performs the task of
 * initializing migration of an existing deployment to a new deployment.
 */
public class InitializeDeploymentMigrationWorkflowService extends StatefulService {

  /**
   * This class defines the state of a {@link InitializeDeploymentMigrationWorkflowService} task.
   */
  public static class TaskState extends com.vmware.xenon.common.TaskState {

    /**
     * This value represents the current sub-stage for the task.
     */
    public SubStage subStage;

    /**
     * This enum represents the possible sub-states for this task.
     */
    public enum SubStage {
      UPLOAD_VIBS,
      CONTINOUS_MIGRATE_DATA,
    }
  }

  /**
   * This class defines the document state associated with a single
   * {@link InitializeDeploymentMigrationWorkflowService} instance.
   */
  @NoMigrationDuringUpgrade
  @NoMigrationDuringDeployment
  public static class State extends ServiceDocument {
    /**
     * This value represents the state of the task.
     */
    @DefaultTaskState(value = TaskState.TaskStage.CREATED)
    public TaskState taskState;

    /**
     * This value allows processing of post and patch operations to be
     * disabled, effectively making all service instances listeners. It is set
     * only in test scenarios.
     */
    @Immutable
    @DefaultInteger(value = 0)
    public Integer controlFlags;

    /**
     * This value represents the interval, in milliseconds, to use when polling
     * the state of a Xenon task.
     */
    @Positive
    public Integer taskPollDelay;

    /**
     * This value represents a reference to the node group to use as the source of the migration
     * operation. This is a URI with form {protocol}://{address}:{port}/core/node-groups/{id} where
     * the ID is usually "default".
     */
    @NotNull
    @Immutable
    public URI sourceNodeGroupReference;

    /**
     * This value represents the base URI of the nodes in the source node group.
     */
    @WriteOnce
    public List<URI> sourceURIs;

    /**
     * This value represents the id of the destination deployment.
     */
    @NotNull
    @Immutable
    public String destinationDeploymentId;
  }

  public InitializeDeploymentMigrationWorkflowService() {
    super(State.class);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
  }

  @Override
  public void handleStart(Operation startOperation) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());
    State startState = startOperation.getBody(State.class);
    InitializationUtils.initialize(startState);

    if (null == startState.taskPollDelay) {
      startState.taskPollDelay = HostUtils.getDeployerContext(this).getTaskPollDelay();
    }
    validateState(startState);

    if (TaskState.TaskStage.CREATED == startState.taskState.stage) {
      startState.taskState.stage = TaskState.TaskStage.STARTED;
      startState.taskState.subStage = TaskState.SubStage.UPLOAD_VIBS;
    }

    if (startState.documentExpirationTimeMicros <= 0) {
      startState.documentExpirationTimeMicros =
          ServiceUtils.computeExpirationTime(ServiceUtils.DEFAULT_DOC_EXPIRATION_TIME_MICROS);
    }

    startOperation.setBody(startState).complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == startState.taskState.stage) {
        sendStageProgressPatch(startState.taskState.stage, startState.taskState.subStage);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  @Override
  public void handlePatch(Operation patchOperation) {
    ServiceUtils.logInfo(this, "Handling patch for service %s", getSelfLink());
    State startState = getState(patchOperation);
    State patchState = patchOperation.getBody(State.class);
    validatePatchState(startState, patchState);
    State currentState = applyPatch(startState, patchState);
    validateState(currentState);
    patchOperation.complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(currentState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping patch operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == currentState.taskState.stage) {
        if (currentState.sourceURIs == null) {
          populateCurrentState(currentState);
          return;
        }
        processStartedState(currentState);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  private void populateCurrentState(State currentState) {

    sendRequest(Operation
        .createGet(currentState.sourceNodeGroupReference)
        .setCompletion((o, e) -> {
          try {
            if (e != null) {
              failTask(e);
            } else {
              processNodeGroupState(currentState, o.getBody(NodeGroupService.NodeGroupState.class));
            }
          } catch (Throwable t) {
            failTask(t);
          }
        }));
  }

  private void processNodeGroupState(State currentState, NodeGroupService.NodeGroupState nodeGroupState) {
    List<URI> sourceURIs = nodeGroupState.nodes.values().stream()
        .map(this::extractBaseURI).collect(Collectors.toList());
    State patchState = buildPatch(currentState.taskState.stage, currentState.taskState.subStage, null);
    patchState.sourceURIs = sourceURIs;
    TaskUtils.sendSelfPatch(this, patchState);
  }

  private URI extractBaseURI(NodeState nodeState) {
    return extractBaseURI(nodeState.groupReference);
  }

  private URI extractBaseURI(URI uri) {
    return UriUtils.buildUri(uri.getScheme(), uri.getHost(), uri.getPort(), null, null);
  }

  /**
   * This method performs the appropriate tasks while in the STARTED state.
   *
   * @param currentState Supplies the current state object.
   */
  private void processStartedState(State currentState) throws Throwable {
    switch (currentState.taskState.subStage) {
      case UPLOAD_VIBS:
        migrateHostEntities(currentState);
        break;
      case CONTINOUS_MIGRATE_DATA:
        migrateDataContinously(currentState);
        break;
    }
  }

  private Operation generateKindQuery(Class<?> clazz) {
    QueryTask.Query typeClause = new QueryTask.Query()
        .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
        .setTermMatchValue(Utils.buildKind(clazz));
    QueryTask.QuerySpecification querySpecification = new QueryTask.QuerySpecification();
    querySpecification.query = typeClause;
    querySpecification.options = EnumSet.of(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT);

    return Operation
        .createPost(UriUtils.buildBroadcastRequestUri(
            UriUtils.buildUri(
                getHost(), ServiceUriPaths.CORE_LOCAL_QUERY_TASKS), ServiceUriPaths.DEFAULT_NODE_SELECTOR))
        .setBody(QueryTask.create(querySpecification).setDirect(true));
  }

  private QueryTask.QuerySpecification buildHostQuerySpecification() {
    QueryTask.Query kindClause = new QueryTask.Query()
        .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
        .setTermMatchValue(Utils.buildKind(HostService.State.class));

    QueryTask.QuerySpecification querySpecification = new QueryTask.QuerySpecification();
    querySpecification.query
        .addBooleanClause(kindClause)
        .addBooleanClause(
            Query.Builder.create()
                .addFieldClause(HostService.State.FIELD_NAME_STATE, HostState.READY.name())
                .build());
    return querySpecification;
  }

  private void migrateHostEntities(State currentState) throws Throwable {

    List<UpgradeInformation> hostUpgradeInformation = HostUtils.getDeployerContext(this)
        .getUpgradeInformation().stream()
        .filter(e -> e.destinationFactoryServicePath.equals(HostServiceFactory.SELF_LINK))
        .collect(Collectors.toList());

    Stream<Operation> copyStateTaskStartOps = hostUpgradeInformation.stream().map((upgradeInfo) -> {
      String sourceFactoryLink = upgradeInfo.sourceFactoryServicePath;
      if (!sourceFactoryLink.endsWith("/")) {
        sourceFactoryLink += "/";
      }

      CopyStateTaskService.State startState = new CopyStateTaskService.State();
      startState.sourceURIs = currentState.sourceURIs;
      startState.sourceFactoryLink = sourceFactoryLink;
      startState.destinationURI = getHost().getUri();
      startState.destinationFactoryLink = upgradeInfo.destinationFactoryServicePath;
      startState.performHostTransformation = true;
      return Operation.createPost(this, CopyStateTaskFactoryService.SELF_LINK).setBody(startState);
    });

    OperationJoin
        .create(copyStateTaskStartOps)
        .setCompletion((ops, exs) -> {
          try {
            if (exs != null && !exs.isEmpty()) {
              failTask(exs.values());
            } else {
              processCopyStateTasks(currentState);
            }
          } catch (Throwable t) {
            failTask(t);
          }
        })
        .sendWith(this);
  }

  private void processCopyStateTasks(State currentState) {

    waitUntilCopyStateTasksFinished((o, e) -> {
      try {
        if (e != null) {
          failTask(e);
        } else {
          deleteOldTasks(currentState);
        }
      } catch (Throwable t) {
        failTask(t);
      }
    }, currentState);
  }

  private void deleteOldTasks(final State currentState) {

    Operation copyStateQuery = generateKindQuery(CopyStateTaskService.State.class);
    Operation uploadVibQuery = generateKindQuery(UploadVibTaskService.State.class);

    OperationJoin.create(copyStateQuery, uploadVibQuery)
        .setCompletion((os, ts) -> {
          if (ts != null && !ts.isEmpty()) {
            failTask(ts.values());
            return;
          }
          Collection<String> linksToDelete = new HashSet<String>();
          for (Operation op : os.values()) {
            linksToDelete.addAll(QueryTaskUtils.getBroadcastQueryDocumentLinks(op));
          }

          if (linksToDelete.isEmpty()) {
            uploadVibs(currentState);
            return;
          }

          OperationJoin.create(
              linksToDelete.stream()
                  .map(link -> {
                    return Operation.createDelete(this, link);
                  })
                  .collect(Collectors.toList())
          )
              .setCompletion((ops, ths) -> {
                if (ths != null && !ths.isEmpty()) {
                  failTask(ths.values());
                  return;
                }
                uploadVibs(currentState);
              })
              .sendWith(this);
        })
        .sendWith(this);
  }

  private void uploadVibs(State currentState) {

    sendRequest(HostUtils
        .getCloudStoreHelper(this)
        .createBroadcastPost(ServiceUriPaths.CORE_LOCAL_QUERY_TASKS, ServiceUriPaths.DEFAULT_NODE_SELECTOR)
        .setBody(QueryTask.create(buildHostQuerySpecification()).setDirect(true))
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                uploadVibs(QueryTaskUtils.getBroadcastQueryDocumentLinks(o));
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void uploadVibs(Set<String> hostServiceLinks) {

    if (hostServiceLinks.isEmpty()) {
      ServiceUtils.logInfo(this, "Found no hosts to provision");
      sendStageProgressPatch(TaskStage.STARTED, TaskState.SubStage.CONTINOUS_MIGRATE_DATA);
      return;
    }

    File sourceDirectory = new File(HostUtils.getDeployerContext(this).getVibDirectory());
    if (!sourceDirectory.exists() || !sourceDirectory.isDirectory()) {
      throw new IllegalStateException("Invalid VIB source directory " + sourceDirectory);
    }

    File[] vibFiles = sourceDirectory.listFiles((file) -> file.getName().toUpperCase().endsWith(".VIB"));
    if (vibFiles.length == 0) {
      throw new IllegalStateException("No VIB files found in source directory " + sourceDirectory);
    }

    Stream<Operation> vibStartOps = Stream.of(vibFiles).flatMap((vibFile) ->
        hostServiceLinks.stream().map((hostServiceLink) -> {
          VibService.State startState = new VibService.State();
          startState.vibName = vibFile.getName();
          startState.hostServiceLink = hostServiceLink;
          return Operation.createPost(this, VibFactoryService.SELF_LINK).setBody(startState);
        }));

    OperationJoin
        .create(vibStartOps)
        .setCompletion(
            (ops, exs) -> {
              if (exs != null && !exs.isEmpty()) {
                failTask(exs.values());
                return;
              }

              try {
                createUploadVibTasks(ops.values());
              } catch (Throwable t) {
                failTask(t);
              }
            })
        .sendWith(this);
  }

  private void createUploadVibTasks(Collection<Operation> vibStartOps) {

    /**
     * N.B. The error threshold is set to 1.0, which means that the aggregator service will not
     * report failure even if all of the child tasks fail. Failures in VIB upload tasks will be
     * reflected in host provisioning failures during finalize.
     */

    ChildTaskAggregatorService.State startState = new ChildTaskAggregatorService.State();
    startState.parentTaskLink = getSelfLink();
    startState.parentPatchBody = Utils.toJson(false, false,
        buildPatch(TaskStage.STARTED, TaskState.SubStage.CONTINOUS_MIGRATE_DATA, null));
    startState.pendingCompletionCount = vibStartOps.size();
    startState.errorThreshold = 1.0;

    sendRequest(Operation
        .createPost(this, ChildTaskAggregatorFactoryService.SELF_LINK)
        .setBody(startState)
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                createUploadVibTasks(vibStartOps, o.getBody(ServiceDocument.class).documentSelfLink);
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void createUploadVibTasks(Collection<Operation> vibStartOps, String aggregatorServiceLink) {

    Stream<Operation> taskStartOps = vibStartOps.stream().map((vibStartOp) -> {
      UploadVibTaskService.State startState = new UploadVibTaskService.State();
      startState.parentTaskServiceLink = aggregatorServiceLink;
      startState.workQueueServiceLink = DeployerServiceGroup.UPLOAD_VIB_WORK_QUEUE_SELF_LINK;
      startState.vibServiceLink = vibStartOp.getBody(ServiceDocument.class).documentSelfLink;
      return Operation.createPost(this, UploadVibTaskFactoryService.SELF_LINK).setBody(startState);
    });

    OperationJoin
        .create(taskStartOps)
        .setCompletion(
            (ops, exs) -> {
              if (exs != null && !exs.isEmpty()) {
                failTask(exs.values());
              }
            })
        .sendWith(this);
  }

  private void migrateDataContinously(State currentState) {
    // Start MigrationStatusUpdateService
    MigrationStatusUpdateTriggerService.State startState = new MigrationStatusUpdateTriggerService.State();
    startState.deploymentServiceLink = DeploymentServiceFactory.SELF_LINK + "/" + currentState.destinationDeploymentId;
    startState.documentSelfLink = currentState.destinationDeploymentId;

    OperationSequence
        .create(createStartMigrationOperations(currentState))
        .setCompletion((os, ts) -> {
          if (ts != null) {
            failTask(ts.values());
          }
        })
        .next(Operation
            .createPost(UriUtils.buildUri(getHost(), MigrationStatusUpdateTriggerFactoryService.SELF_LINK, null))
            .setBody(startState))
        .setCompletion((os, ts) -> {
          if (ts != null) {
            failTask(ts.values());
            return;
          }
          sendStageProgressPatch(TaskState.TaskStage.FINISHED, null);
        })
        .sendWith(this);
  }

  private OperationJoin createStartMigrationOperations(State currentState) {

    Stream<Operation> copyStateTriggerTaskStartOps = HostUtils.getDeployerContext(this)
        .getUpgradeInformation().stream().map((upgradeInfo) -> {
          CopyStateTriggerTaskService.State startState = new CopyStateTriggerTaskService.State();
          startState.executionState = ExecutionState.RUNNING;
          startState.sourceURIs = currentState.sourceURIs;
          startState.sourceFactoryLink = upgradeInfo.sourceFactoryServicePath;
          startState.destinationURI = getHost().getUri();
          startState.destinationFactoryLink = upgradeInfo.destinationFactoryServicePath;
          startState.performHostTransformation = true;
          return Operation.createPost(this, CopyStateTriggerTaskFactoryService.SELF_LINK).setBody(startState);
        });

    return OperationJoin.create(copyStateTriggerTaskStartOps);
  }

  private void waitUntilCopyStateTasksFinished(CompletionHandler handler, State currentState) {
    // wait until all the copy-state services are done
    generateQueryCopyStateTaskQuery()
        .setCompletion((op, t) -> {
          if (t != null) {
            handler.handle(op, t);
            return;
          }
          List<CopyStateTaskService.State> documents =
              QueryTaskUtils.getBroadcastQueryDocuments(CopyStateTaskService.State.class, op);
          List<CopyStateTaskService.State> runningServices = documents.stream()
              .filter((d) -> d.taskState.stage == TaskStage.CREATED || d.taskState.stage == TaskStage.STARTED)
              .collect(Collectors.toList());
          if (runningServices.isEmpty()) {
            handler.handle(op, t);
            return;
          }
          getHost().schedule(
              () -> waitUntilCopyStateTasksFinished(handler, currentState),
              currentState.taskPollDelay,
              TimeUnit.MILLISECONDS);
        })
        .sendWith(this);
  }

  private Operation generateQueryCopyStateTaskQuery() {
    QueryTask queryTask = QueryTask.Builder.createDirectTask()
        .setQuery(QueryTask.Query.Builder.create()
            .addKindFieldClause(CopyStateTaskService.State.class)
            .build())
        .addOption(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT)
        .build();
    return Operation
        .createPost(UriUtils.buildBroadcastRequestUri(
            UriUtils.buildUri(getHost(), ServiceUriPaths.CORE_LOCAL_QUERY_TASKS),
            ServiceUriPaths.DEFAULT_NODE_SELECTOR))
        .setBody(queryTask);
  }

  private State applyPatch(State currentState, State patchState) {
    if (patchState.taskState.stage != currentState.taskState.stage
        || patchState.taskState.subStage != currentState.taskState.subStage) {
      ServiceUtils.logInfo(this, "Moving to stage %s:%s", patchState.taskState.stage, patchState.taskState.subStage);
      currentState.taskState = patchState.taskState;
    }

    if (patchState.sourceURIs != null) {
      currentState.sourceURIs = patchState.sourceURIs;
    }

    return currentState;
  }


  private void validateState(State currentState) {
    ValidationUtils.validateState(currentState);
    ValidationUtils.validateTaskStage(currentState.taskState);
    validateTaskSubStage(currentState.taskState);

    if (TaskState.TaskStage.STARTED == currentState.taskState.stage) {
      switch (currentState.taskState.subStage) {
        case CONTINOUS_MIGRATE_DATA:
        case UPLOAD_VIBS:
          break;
        default:
          throw new IllegalStateException("Unknown task sub-stage: " + currentState.taskState.subStage);
      }
    }
  }

  private void validateTaskSubStage(TaskState taskState) {
    switch (taskState.stage) {
      case CREATED:
        checkState(null == taskState.subStage);
        break;
      case STARTED:
        checkState(null != taskState.subStage);
        break;
      case FINISHED:
      case FAILED:
      case CANCELLED:
        checkState(null == taskState.subStage);
        break;
    }
  }

  private void validatePatchState(State currentState, State patchState) {
    ValidationUtils.validatePatch(currentState, patchState);
    ValidationUtils.validateTaskStage(patchState.taskState);
    ValidationUtils.validateTaskStageProgression(currentState.taskState, patchState.taskState);

    if (null != currentState.taskState.subStage && null != patchState.taskState.subStage) {
      checkState(patchState.taskState.subStage.ordinal() >= currentState.taskState.subStage.ordinal());
    }
  }

  private void failTask(Throwable t) {
    ServiceUtils.logSevere(this, t);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, t));
  }

  private void failTask(Collection<Throwable> failures) {
    failures.forEach((throwable) -> ServiceUtils.logSevere(this, throwable));
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, failures.iterator().next()));
  }

  private void sendStageProgressPatch(TaskState.TaskStage patchStage, @Nullable TaskState.SubStage patchSubStage) {
    ServiceUtils.logInfo(this, "Sending self-patch to stage %s:%s", patchStage, patchSubStage);
    TaskUtils.sendSelfPatch(this, buildPatch(patchStage, patchSubStage, null));
  }

  @VisibleForTesting
  protected static State buildPatch(
      TaskState.TaskStage patchStage,
      @Nullable TaskState.SubStage patchSubStage,
      @Nullable Throwable t) {

    State patchState = new State();
    patchState.taskState = new TaskState();
    patchState.taskState.stage = patchStage;
    patchState.taskState.subStage = patchSubStage;
    if (null != t) {
      patchState.taskState.failure = Utils.toServiceErrorResponse(t);
    }
    return patchState;
  }
}
