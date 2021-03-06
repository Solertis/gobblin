/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package gobblin.eventhub.writer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.typesafe.config.Config;

import gobblin.util.ConfigUtils;
import gobblin.writer.Batch;
import gobblin.writer.BatchAccumulator;
import gobblin.writer.RecordMetadata;
import gobblin.writer.WriteCallback;


/**
 * Eventhub Accumulator based on batch size and TTL
 */

public class EventhubBatchAccumulator extends BatchAccumulator<JsonObject> {

  private Deque<EventhubBatch> dq = new ArrayDeque<>();
  private IncompleteRecordBatches incomplete = new IncompleteRecordBatches();
  private final long batchSizeLimit;
  private final long expireInMilliSecond;
  private static final Logger LOG = LoggerFactory.getLogger(EventhubBatchAccumulator.class);

  public EventhubBatchAccumulator () {
    this (1024 * 128, 3000);
  }

  public EventhubBatchAccumulator (Properties properties) {
    Config config = ConfigUtils.propertiesToConfig(properties);
    this.batchSizeLimit = ConfigUtils.getLong(config, EventhubWriterConfigurationKeys.BATCH_SIZE,
        EventhubWriterConfigurationKeys.BATCH_SIZE_DEFAULT);

    this.expireInMilliSecond = ConfigUtils.getLong(config, EventhubWriterConfigurationKeys.BATCH_TTL,
        EventhubWriterConfigurationKeys.BATCH_TTL_DEFAULT);
  }

  public EventhubBatchAccumulator (long batchSizeLimit, long expireInMilliSecond) {
    this.batchSizeLimit = batchSizeLimit;
    this.expireInMilliSecond = expireInMilliSecond;
  }

  public long getMemSizeLimit () {
    return this.batchSizeLimit;
  }

  public long getExpireInMilliSecond () {
    return this.expireInMilliSecond;
  }

  /**
   * Add a data to internal dequeu data structure
   */
  public final Future<RecordMetadata> enqueue (JsonObject record, WriteCallback callback) throws InterruptedException {

    synchronized (dq) {
      Batch last = dq.peekLast();
      if (last != null) {
        Future<RecordMetadata> future = last.tryAppend(record, callback);
        if (future != null)
          return future;
      }

      // Create a new batch because previous one has no space
      EventhubBatch batch = new EventhubBatch(this.batchSizeLimit, this.expireInMilliSecond);
      LOG.info ("Batch " + batch.getId() + " is generated");
      Future<RecordMetadata> future = batch.tryAppend(record, callback);
      dq.addLast(batch);
      incomplete.add(batch);
      return future;
    }
  }

  /**
   * A threadsafe helper class to hold RecordBatches that haven't been ack'd yet
   * This is mainly used for flush operation so that all the batches waiting in
   * the incomplete set will be blocked
   */
  private final static class IncompleteRecordBatches {
    private final Set<Batch> incomplete;

    public IncompleteRecordBatches() {
      this.incomplete = new HashSet<>();
    }

    public void add(Batch batch) {
      synchronized (incomplete) {
        this.incomplete.add(batch);
      }
    }

    public void remove(Batch batch) {
      synchronized (incomplete) {
        boolean removed = this.incomplete.remove(batch);
        if (!removed)
          throw new IllegalStateException("Remove from the incomplete set failed. This should be impossible.");
      }
    }

    public Iterable<Batch> all() {
      synchronized (incomplete) {
        return new ArrayList (this.incomplete);
      }
    }
  }

  public Iterator<Batch<JsonObject>> iterator() {
    return new EventhubBatchIterator();
  }


  /**
   * An internal iterator that will iterate all the available batches
   * This will be used by external BufferedAsyncDataWriter
   */
  private class EventhubBatchIterator implements Iterator<Batch<JsonObject>> {
    public Batch<JsonObject> next () {
      synchronized (dq) {
        return dq.poll();
      }
    }

    public boolean hasNext() {
      synchronized (dq) {
        if (EventhubBatchAccumulator.this.isClosed()) {
          return dq.size() > 0;
        }
        if (dq.size() > 1)
            return true;
        if (dq.size() == 0)
            return false;
        EventhubBatch first = dq.peekFirst();
        if (first.isTTLExpire()) {
            LOG.info ("Batch " + first.getId() + " is expired");
            return true;
        }

        return false;
      }
    }

    public void remove() {
      // Do nothing
    }
  }

  /**
   * This will block until all the incomplete batches are acknowledged
   */
  public void flush() {
    try {
      for (Batch batch: this.incomplete.all()) {
        batch.await();
      }
    } catch (Exception e) {
      LOG.info ("Error happens when flushing");
    }
  }

  /**
   * Once batch is acknowledged, remove it from incomplete list
   */
  public void deallocate (Batch<JsonObject> batch) {
    this.incomplete.remove(batch);
  }
}
