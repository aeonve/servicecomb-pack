/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.core;

import java.lang.invoke.MethodHandles;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Saga {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final IdGenerator<Long> idGenerator;
  private final IdGenerator<Long> taskIdGenerator;
  private final EventStore eventStore;
  private final SagaRequest[] requests;
  private final RecoveryPolicy recoveryPolicy;

  private volatile SagaState currentState;

  private final Deque<SagaTask> executedTasks = new ConcurrentLinkedDeque<>();
  private final Queue<SagaTask> pendingTasks;

  public Saga(IdGenerator<Long> idGenerator, EventStore eventStore, SagaRequest... requests) {
    this(idGenerator, eventStore, new BackwardRecovery(), requests);
  }

  public Saga(IdGenerator<Long> idGenerator, EventStore eventStore, RecoveryPolicy recoveryPolicy,
      SagaRequest... requests) {

    this.idGenerator = idGenerator;
    this.eventStore = eventStore;
    this.requests = requests;
    this.recoveryPolicy = new LoggingRecoveryPolicy(recoveryPolicy);
    this.taskIdGenerator = new LongIdGenerator();
    this.pendingTasks = populatePendingSagaTasks(requests);

    currentState = TransactionState.INSTANCE;
  }

  public void run() {
    log.info("Starting Saga");
    do {
      try {
        currentState.invoke(executedTasks, pendingTasks);
      } catch (Exception e) {
        log.error("Failed to run operation", e);
        currentState = recoveryPolicy.apply(currentState);
      }
    } while (!pendingTasks.isEmpty() && !executedTasks.isEmpty());
    log.info("Completed Saga");
  }

  public void abort() {
    currentState = CompensationState.INSTANCE;
    executedTasks.push(new SagaAbortTask(taskIdGenerator.nextId(), eventStore, idGenerator));
  }

  private Queue<SagaTask> populatePendingSagaTasks(SagaRequest[] requests) {
    Queue<SagaTask> pendingTasks = new LinkedList<>();

    pendingTasks.add(new SagaStartTask(taskIdGenerator.nextId(), eventStore, idGenerator));

    for (SagaRequest request : requests) {
      pendingTasks.add(new RequestProcessTask(taskIdGenerator.nextId(), request, eventStore, idGenerator));
    }

    pendingTasks.add(new SagaEndTask(taskIdGenerator.nextId(), eventStore, idGenerator));
    return pendingTasks;
  }

  public void play(Iterable<SagaEvent> events) {
    log.info("Start playing events");
    for (SagaEvent event : events) {
      log.info("Start playing event {} id={}", event.description(), event.id());
      currentState = event.play(currentState, pendingTasks, executedTasks, idGenerator);
      log.info("Completed playing event {} id={}", event.description(), event.id());
    }
    log.info("Completed playing events");
  }
}