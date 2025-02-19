/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  The OpenSearch Contributors require contributions made to
 *  this file be licensed under the Apache-2.0 license or a
 *  compatible open source license.
 *
 *  Modifications Copyright OpenSearch Contributors. See
 *  GitHub history for details.
 */

package com.amazon.dataprepper.plugins.buffer.blockingbuffer;

import com.amazon.dataprepper.model.CheckpointState;
import com.amazon.dataprepper.model.annotations.DataPrepperPlugin;
import com.amazon.dataprepper.model.buffer.AbstractBuffer;
import com.amazon.dataprepper.model.buffer.Buffer;
import com.amazon.dataprepper.model.buffer.SizeOverflowException;
import com.amazon.dataprepper.model.configuration.PluginSetting;
import com.amazon.dataprepper.model.record.Record;
import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

/**
 * A bounded BlockingBuffer is an implementation of {@link Buffer} using {@link LinkedBlockingQueue}, it is bounded
 * to the provided capacity {@link #ATTRIBUTE_BUFFER_CAPACITY} or {@link #DEFAULT_BUFFER_CAPACITY} (if attribute is
 * not provided); {@link #write(Record, int)} inserts specified non-null record into this buffer, waiting up to the
 * specified timeout in milliseconds if necessary for space to become available; and throws an exception if the
 * record is null. {@link #read(int)} retrieves and removes the batch of records from the head of the queue. The
 * batch size is defined/determined by the configuration attribute {@link #ATTRIBUTE_BATCH_SIZE} or the timeout parameter
 */
@DataPrepperPlugin(name = "bounded_blocking", pluginType = Buffer.class)
public class BlockingBuffer<T extends Record<?>> extends AbstractBuffer<T> {
    private static final Logger LOG = LoggerFactory.getLogger(BlockingBuffer.class);
    private static final int DEFAULT_BUFFER_CAPACITY = 512;
    private static final int DEFAULT_BATCH_SIZE = 8;
    private static final String PLUGIN_NAME = "bounded_blocking";
    private static final String ATTRIBUTE_BUFFER_CAPACITY = "buffer_size";
    private static final String ATTRIBUTE_BATCH_SIZE = "batch_size";

    private final int bufferCapacity;
    private final int batchSize;
    private final BlockingQueue<T> blockingQueue;
    private final String pipelineName;

    private final Semaphore capacitySemaphore;

    /**
     * Creates a BlockingBuffer with the given (fixed) capacity.
     *
     * @param bufferCapacity the capacity of the buffer
     * @param batchSize      the batch size for {@link #read(int)}
     * @param pipelineName   the name of the associated Pipeline
     */
    public BlockingBuffer(final int bufferCapacity, final int batchSize, final String pipelineName) {
        super("BlockingBuffer", pipelineName);
        this.bufferCapacity = bufferCapacity;
        this.batchSize = batchSize;
        this.blockingQueue = new LinkedBlockingQueue<>(bufferCapacity);
        this.capacitySemaphore = new Semaphore(bufferCapacity);
        this.pipelineName = pipelineName;
    }

    /**
     * Mandatory constructor for Data Prepper Component - This constructor is used by Data Prepper runtime engine to construct an
     * instance of {@link BlockingBuffer} using an instance of {@link PluginSetting} which has access to
     * pluginSetting metadata from pipeline pluginSetting file. Buffer settings like `buffer-size`, `batch-size`,
     * `batch-timeout` are optional and can be passed via {@link PluginSetting}, if not present default values will
     * be used to create the buffer.
     *
     * @param pluginSetting instance with metadata information from pipeline pluginSetting file.
     */
    public BlockingBuffer(final PluginSetting pluginSetting) {
        this(checkNotNull(pluginSetting, "PluginSetting cannot be null")
                        .getIntegerOrDefault(ATTRIBUTE_BUFFER_CAPACITY, DEFAULT_BUFFER_CAPACITY),
                pluginSetting.getIntegerOrDefault(ATTRIBUTE_BATCH_SIZE, DEFAULT_BATCH_SIZE),
                pluginSetting.getPipelineName());
    }

    public BlockingBuffer(final String pipelineName) {
        this(DEFAULT_BUFFER_CAPACITY, DEFAULT_BATCH_SIZE, pipelineName);
    }

    @Override
    public void doWrite(T record, int timeoutInMillis) throws TimeoutException {
        try {
            final boolean permitAcquired = capacitySemaphore.tryAcquire(timeoutInMillis, TimeUnit.MILLISECONDS);
            if (!permitAcquired) {
                throw new TimeoutException(format("Pipeline [%s] - Buffer is full, timed out waiting for a slot",
                        pipelineName));
            }
            blockingQueue.offer(record);
        } catch (InterruptedException ex) {
            LOG.error("Pipeline [{}] - Buffer is full, interrupted while waiting to write the record", pipelineName, ex);
            throw new TimeoutException("Buffer is full, timed out waiting for a slot");
        }
    }

    @Override
    public void doWriteAll(Collection<T> records, int timeoutInMillis) throws Exception {
        final int size = records.size();
        if (size > bufferCapacity) {
            throw new SizeOverflowException(format("Buffer capacity too small for the size of records: %d", size));
        }
        try {
            final boolean permitAcquired = capacitySemaphore.tryAcquire(size, timeoutInMillis, TimeUnit.MILLISECONDS);
            if (!permitAcquired) {
                throw new TimeoutException(
                        format("Pipeline [%s] - Buffer does not have enough capacity left for the size of records: %d, " +
                                        "timed out waiting for slots.",
                        pipelineName, size));
            }
            blockingQueue.addAll(records);
        } catch (InterruptedException ex) {
            LOG.error("Pipeline [{}] - Buffer does not have enough capacity left for the size of records: {}, " +
                            "interrupted while waiting to write the records",
                    pipelineName, size, ex);
            throw new TimeoutException(
                    format("Pipeline [%s] - Buffer does not have enough capacity left for the size of records: %d, " +
                            "timed out waiting for slots.",
                    pipelineName, size));
        }
    }

    /**
     * Retrieves and removes the batch of records from the head of the queue. The batch size is defined/determined by
     * the configuration attribute {@link #ATTRIBUTE_BATCH_SIZE} or the @param timeoutInMillis. The timeoutInMillis
     * is also used for retrieving each record
     *
     * @param timeoutInMillis how long to wait before giving up
     * @return The earliest batch of records in the buffer which are still not read.
     */
    @Override
    public Map.Entry<Collection<T>, CheckpointState> doRead(int timeoutInMillis) {
        final List<T> records = new ArrayList<>();
        final Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            while (stopwatch.elapsed(TimeUnit.MILLISECONDS) < timeoutInMillis && records.size() < batchSize) {
                final T record = blockingQueue.poll(timeoutInMillis, TimeUnit.MILLISECONDS);
                if (record != null) { //record can be null, avoiding adding nulls
                    records.add(record);
                }
                if (records.size() < batchSize) {
                    blockingQueue.drainTo(records, batchSize - records.size());
                }
            }
        } catch (InterruptedException ex) {
            LOG.info("Pipeline [{}] - Interrupt received while reading from buffer", pipelineName);
            throw new RuntimeException(ex);
        }
        final CheckpointState checkpointState = new CheckpointState(records.size());
        return new AbstractMap.SimpleEntry<>(records, checkpointState);
    }

    /**
     * Returns the default PluginSetting object with default values.
     * @return PluginSetting
     */
    public static PluginSetting getDefaultPluginSettings() {
        final Map<String, Object> settings = new HashMap<>();
        settings.put(ATTRIBUTE_BUFFER_CAPACITY, DEFAULT_BUFFER_CAPACITY);
        settings.put(ATTRIBUTE_BATCH_SIZE, DEFAULT_BATCH_SIZE);
        return new PluginSetting(PLUGIN_NAME, settings);
    }

    @Override
    public void doCheckpoint(final CheckpointState checkpointState) {
        final int numCheckedRecords = checkpointState.getNumRecordsToBeChecked();
        capacitySemaphore.release(numCheckedRecords);
    }

    @Override
    public boolean isEmpty() {
        return blockingQueue.isEmpty() && getRecordsInFlight() == 0;
    }
}
