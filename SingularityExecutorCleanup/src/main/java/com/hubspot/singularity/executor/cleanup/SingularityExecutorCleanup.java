package com.hubspot.singularity.executor.cleanup;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import org.apache.mesos.Protos.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.mesos.MesosUtils;
import com.hubspot.mesos.client.MesosClient;
import com.hubspot.mesos.json.MesosExecutorObject;
import com.hubspot.mesos.json.MesosSlaveFrameworkObject;
import com.hubspot.mesos.json.MesosSlaveStateObject;
import com.hubspot.mesos.json.MesosTaskObject;
import com.hubspot.singularity.executor.TemplateManager;
import com.hubspot.singularity.executor.cleanup.SingularityExecutorCleanupStatistics.SingularityExecutorCleanupStatisticsBuilder;
import com.hubspot.singularity.executor.cleanup.config.SingularityExecutorCleanupConfiguration;
import com.hubspot.singularity.executor.cleanup.config.SingularityExecutorCleanupConfigurationLoader;
import com.hubspot.singularity.executor.config.SingularityExecutorConfiguration;
import com.hubspot.singularity.executor.task.SingularityExecutorTaskCleanup;
import com.hubspot.singularity.executor.task.SingularityExecutorTaskConfiguration;
import com.hubspot.singularity.executor.task.SingularityExecutorTaskDefinition;
import com.hubspot.singularity.executor.task.SingularityExecutorTaskLogManager;
import com.hubspot.singularity.runner.base.shared.JsonObjectFileHelper;

public class SingularityExecutorCleanup {
  
  private final static Logger LOG = LoggerFactory.getLogger(SingularityExecutorCleanup.class);
  
  private final JsonObjectFileHelper jsonObjectFileHelper;
  private final SingularityExecutorConfiguration executorConfiguration;
  private final MesosClient mesosClient;
  private final TemplateManager templateManager;
  private final SingularityExecutorCleanupConfiguration cleanupConfiguration;
  
  @Inject
  public SingularityExecutorCleanup(MesosClient mesosClient, JsonObjectFileHelper jsonObjectFileHelper, SingularityExecutorConfiguration executorConfiguration, SingularityExecutorCleanupConfiguration cleanupConfiguration, TemplateManager templateManager) {
    this.jsonObjectFileHelper = jsonObjectFileHelper;
    this.executorConfiguration = executorConfiguration;
    this.cleanupConfiguration = cleanupConfiguration;
    this.mesosClient = mesosClient;
    this.templateManager = templateManager;
  }
  
  public void clean() {
    final SingularityExecutorCleanupStatisticsBuilder statisticsBldr = new SingularityExecutorCleanupStatisticsBuilder();
    final Path directory = Paths.get(executorConfiguration.getGlobalTaskDefinitionDirectory());
    
    final Set<String> runningTaskIds = getRunningTaskIds();
   
    LOG.info("Found {} running tasks", runningTaskIds);
    
    if (runningTaskIds.isEmpty()) {
      if (cleanupConfiguration.isSafeModeWontRunWithNoTasks()) {
        LOG.error("Running in safe mode ({}) and found 0 running tasks - aborting cleanup", SingularityExecutorCleanupConfigurationLoader.SAFE_MODE_WONT_RUN_WITH_NO_TASKS);
        return;
      } else {
        LOG.warn("Found 0 running tasks - proceeding with cleanup as we are not in safe mode ({})", SingularityExecutorCleanupConfigurationLoader.SAFE_MODE_WONT_RUN_WITH_NO_TASKS);
      }
    }
    
    for (Path file : JavaUtils.iterable(directory)) {
      if (!file.getFileName().endsWith(executorConfiguration.getGlobalTaskDefinitionSuffix())) {
        LOG.debug("Ignoring file {} that doesn't have suffix {}", file, executorConfiguration.getGlobalTaskDefinitionSuffix());
        continue;
      }
      
      statisticsBldr.incrTotalTasks();
      
      try {
        Optional<SingularityExecutorTaskDefinition> taskDefinition = jsonObjectFileHelper.read(file, LOG, SingularityExecutorTaskDefinition.class);
     
        if (!taskDefinition.isPresent()) {
          statisticsBldr.incrInvalidTasks();
        }
        
        if (runningTaskIds.contains(taskDefinition.get().getTaskId())) {
          statisticsBldr.incrRunningTasks();
          continue;
        }
        
        if (!cleanTask(taskDefinition.get())) {
          statisticsBldr.incrSuccessfullyCleanedTasks();
        } else {
          statisticsBldr.incrErrorTasks();
        }
        
      } catch (IOException ioe) {
        LOG.error("Couldn't read file {}", file, ioe);
        
        statisticsBldr.incrInvalidTasks();
      }
    }
  }
 
  private MesosSlaveStateObject getSlaveState() {
    final String slaveUri = mesosClient.getSlaveUri("localhost");
    
    return mesosClient.getSlaveState(slaveUri);
  }
  
  private Set<String> getRunningTaskIds() {
    final Set<String> runningTaskIds = Sets.newHashSet();
    
    for (MesosSlaveFrameworkObject framework: getSlaveState().getFrameworks()) {
      for (MesosExecutorObject executor : framework.getExecutors()) {
        for (MesosTaskObject task : executor.getTasks()) {
          if (!MesosUtils.isTaskDone(TaskState.valueOf(task.getState()))) {
            runningTaskIds.add(task.getId());
          }
        }
      }
    }
    
    return runningTaskIds;
  }
  
  private boolean cleanTask(SingularityExecutorTaskDefinition taskDefinition) {
    SingularityExecutorTaskLogManager logManager = new SingularityExecutorTaskLogManager(new SingularityExecutorTaskConfiguration(taskDefinition, executorConfiguration), templateManager, executorConfiguration, LOG, jsonObjectFileHelper);
    
    SingularityExecutorTaskCleanup taskCleanup = new SingularityExecutorTaskCleanup(logManager, executorConfiguration, taskDefinition, LOG);
  
    return taskCleanup.cleanup();
  }
  
}
