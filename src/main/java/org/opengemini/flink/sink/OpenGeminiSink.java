/*
 * Copyright 2025 openGemini authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opengemini.flink.sink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.*;
import org.apache.flink.runtime.metrics.DescriptiveStatisticsHistogram;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.opengemini.flink.utils.EnhancedOpenGeminiClient;
import org.opengemini.flink.utils.OpenGeminiClientFactory;

import io.github.openfacade.http.HttpClientConfig;
import io.opengemini.client.api.Address;
import io.opengemini.client.api.Point;

import lombok.extern.slf4j.Slf4j;
// random chars inserted  to change file for github actions
/**
 * An Apache Flink sink connector for OpenGemini using the official OpenGemini client. This
 * implementation provides efficient batch writing with at-least-once semantics.
 *
 * @param <T> The type of elements handled by this sink
 */
@Slf4j
public class OpenGeminiSink<T> extends RichSinkFunction<T> implements CheckpointedFunction {
    private static final long serialVersionUID = 1L;

    // Configuration
    private final OpenGeminiSinkConfiguration<T> configuration;

    // if the converter is OpenGeminiLineProtocolConverter, we can use direct conversion
    // otherwise we need to convert Point to line protocol in invoke method
    private boolean useDirectConversion = false;
    // either of lineProtocolConverter or openGeminiPointConverter must be set
    private OpenGeminiLineProtocolConverter<T> lineProtocolConverter;
    private OpenGeminiPointConverter<T> pointConverter;
    private transient ListState<List<String>> checkpointedState;
    // Lock for synchronizing batch access
    @SuppressWarnings("synchronization")
    private transient Object batchLock;

    // Runtime state
    private transient EnhancedOpenGeminiClient client;
    private transient List<String> currentBatch;
    private transient long lastFlushTime;

    // Statistics for metrics
    private transient AtomicLong totalBytesWritten;
    private transient AtomicLong errorCount;
    private transient AtomicLong batchesWritten;
    private transient AtomicLong lastSuccessfulWriteTime;
    // not updated real time, only updated when flush() is called
    private transient AtomicLong pointsWrittenPerSecond;
    private transient long lastMetricUpdateTime;
    private transient long lastPointCount;

    // Flink Metrics
    private transient Histogram writeLatency;
    private transient Counter writeErrors;
    private transient Counter totalPointsWritten;

    // Dynamic batch size adjustment
    private transient int currentBatchSize;
    private transient int initialBatchSize;
    private transient int evaluationCounter;
    private transient long totalLatency;
    private transient int errorInWindow;

    public static final String METRIC_FIRST_NAME = "opengemini";
    public static final String METRIC_SECOND_NAME = "sink";
    public static final String WRITE_LATENCY_METRIC = "writeLatency";
    public static final String CURRENT_BATCH_SIZE_METRIC = "currentBatchSize";
    public static final String WRITE_ERRORS_METRIC = "writeErrors";
    public static final String LAST_SUCCESSFUL_WRITE_TIME_METRIC = "lastSuccessfulWriteTime";
    public static final String TOTAL_POINTS_WRITTEN_METRIC = "totalPointsWritten";
    public static final String POINTS_WRITTEN_PER_SECOND = "pointsWrittenPerSecond";
    public static final String TOTAL_BYTES_WRITTEN_METRIC = "totalBytesWritten";
    // metric for dynamic batch size
    public static final String DYNAMIC_BATCH_SIZE_METRIC = "dynamicBatchSize";
    public static final int DYNAMIC_BATCH_SIZE_EVALUATION_INTERVAL = 10; // evaluate every 10 writes
    public static final int DYNAMIC_BATCH_SIZE_INCREASE_THRESHOLD = 300; // ms
    public static final int DYNAMIC_BATCH_SIZE_DECREASE_THRESHOLD = 500; // ms
    public static final double DYNAMIC_BATCH_INCREASE_FACTOR = 1.2; // multiplicative increase
    public static final double DYNAMIC_BATCH_DECREASE_FACTOR = 0.7; // division decrease

    /**
     * Creates a new OpenGeminiSink with the specified configuration.
     *
     * @param configuration The sink configuration
     */
    @SuppressWarnings("unchecked")
    public OpenGeminiSink(OpenGeminiSinkConfiguration<T> configuration) {
        this.configuration = configuration;
        Object converter = configuration.getConverter();
        if (converter instanceof OpenGeminiLineProtocolConverter) {
            this.lineProtocolConverter = (OpenGeminiLineProtocolConverter<T>) converter;
        } else if (converter instanceof OpenGeminiPointConverter) {
            this.pointConverter = (OpenGeminiPointConverter<T>) converter;
        } else {
            throw new IllegalArgumentException(
                    "Converter must be either OpenGeminiLineProtocolConverter or OpenGeminiPointConverter");
        }
    }

    /**
     * Creates a new OpenGeminiSink with the specified configuration and point converter. NOT
     * recommended, as it incurs extra overhead of converting Point to line protocol.
     *
     * @param configuration
     * @param converter
     */
    public OpenGeminiSink(
            OpenGeminiSinkConfiguration<T> configuration, OpenGeminiPointConverter<T> converter) {
        this.configuration = configuration;
        this.pointConverter = converter;
    }

    /**
     * Creates a new OpenGeminiSink with the specified configuration and line protocol converter.
     * Recommended for best performance.
     *
     * @param configuration
     * @param converter
     */
    public OpenGeminiSink(
            OpenGeminiSinkConfiguration<T> configuration,
            OpenGeminiLineProtocolConverter<T> converter) {
        this.configuration = configuration;
        this.lineProtocolConverter = converter;
    }

    /**
     * Initializes the sink, setting up the OpenGemini client and preparing runtime state.
     *
     * @param parameters The configuration containing the parameters attached to the contract.
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);

        // Initialize OpenGemini client
        HttpClientConfig.Builder httpConfigBuilder =
                new HttpClientConfig.Builder()
                        .connectTimeout(configuration.getConnectionTimeout())
                        .timeout(configuration.getRequestTimeout());

        // Set authentication if provided
        if (configuration.hasAuthentication()) {
            httpConfigBuilder.addRequestFilter(
                    new io.github.openfacade.http.BasicAuthRequestFilter(
                            configuration.getUsername(), configuration.getPassword()));
        }

        HttpClientConfig httpConfig = httpConfigBuilder.build();

        io.opengemini.client.api.Configuration clientConfiguration =
                io.opengemini.client.api.Configuration.builder()
                        .addresses(
                                Collections.singletonList(
                                        new Address(
                                                configuration.getHost(), configuration.getPort())))
                        .httpConfig(httpConfig)
                        .build();

        this.client = OpenGeminiClientFactory.createEnhanced(clientConfiguration);

        if (lineProtocolConverter != null) {
            this.useDirectConversion = true;
            log.info("Using direct line protocol conversion mode");
        } else if (pointConverter != null) {
            this.useDirectConversion = false;
            log.info("Using point-based conversion mode");
        } else {
            throw new IllegalStateException(
                    "No converter configured. Either lineProtocolConverter or pointConverter must be set");
        }
        // Initialize runtime components
        this.batchLock = new Object();
        totalBytesWritten = new AtomicLong(0);
        errorCount = new AtomicLong(0);
        batchesWritten = new AtomicLong(0);
        pointsWrittenPerSecond = new AtomicLong(0);
        lastSuccessfulWriteTime = new AtomicLong(System.currentTimeMillis());

        // Initialize dynamic batch size tracking
        this.initialBatchSize = configuration.getBatchSize();
        this.currentBatchSize = configuration.getBatchSize();
        this.evaluationCounter = 0;
        this.totalLatency = 0;
        this.errorInWindow = 0;

        registerMetrics();

        // Ensure database exists
        try {
            client.createDatabase(configuration.getDatabase()).get(5, TimeUnit.SECONDS);
            log.info("Created database: {}", configuration.getDatabase());
        } catch (Exception e) {
            // Database might already exist, which is fine
            log.debug(
                    "Database {} might already exist: {}",
                    configuration.getDatabase(),
                    e.getMessage());
        }

        log.info(
                "OpenGeminiSink initialized with host={}:{}, database={}, measurement={}, batchSize={}, flushInterval={}ms",
                configuration.getHost(),
                configuration.getPort(),
                configuration.getDatabase(),
                configuration.getMeasurement(),
                configuration.getBatchSize(),
                configuration.getFlushIntervalMillis());
    }

    /** Register Flink metrics for monitoring */
    private void registerMetrics() {
        log.info("checking metric group: {}", getRuntimeContext().getMetricGroup());
        MetricGroup metricGroup =
                getRuntimeContext()
                        .getMetricGroup()
                        .addGroup(METRIC_FIRST_NAME)
                        .addGroup(METRIC_SECOND_NAME);

        this.writeLatency =
                metricGroup.histogram(
                        WRITE_LATENCY_METRIC, new DescriptiveStatisticsHistogram(1000));

        metricGroup.gauge(
                CURRENT_BATCH_SIZE_METRIC,
                new Gauge<Integer>() {
                    @Override
                    public Integer getValue() {
                        return currentBatch != null ? currentBatch.size() : 0;
                    }
                });

        this.writeErrors = metricGroup.counter(WRITE_ERRORS_METRIC);

        this.totalPointsWritten = metricGroup.counter(TOTAL_POINTS_WRITTEN_METRIC);

        metricGroup.gauge(
                POINTS_WRITTEN_PER_SECOND,
                new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        return pointsWrittenPerSecond.get();
                    }
                });

        metricGroup.gauge(
                LAST_SUCCESSFUL_WRITE_TIME_METRIC,
                new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        return lastSuccessfulWriteTime.get();
                    }
                });

        metricGroup.gauge(
                TOTAL_BYTES_WRITTEN_METRIC,
                new Gauge<Long>() {
                    @Override
                    public Long getValue() {
                        return totalBytesWritten.get();
                    }
                });

        metricGroup.gauge(
                DYNAMIC_BATCH_SIZE_METRIC,
                new Gauge<Integer>() {
                    @Override
                    public Integer getValue() {
                        return currentBatchSize;
                    }
                });
    }

    /**
     * Processes each incoming record, converting it to line protocol and adding it to the current
     * batch. If the batch size or flush interval is reached, the batch is written to OpenGemini.
     *
     * @param value The input record.
     * @param context Additional context about the input record.
     * @throws Exception
     */
    @Override
    public void invoke(T value, Context context) throws Exception {
        if (value == null) return;
        String lineProtocol;
        try {
            if (useDirectConversion) {
                lineProtocol =
                        lineProtocolConverter.convertToLineProtocol(
                                value, configuration.getMeasurement());
            } else {
                Point point = pointConverter.convertToPoint(value, configuration.getMeasurement());
                if (point == null) {
                    log.debug("Converter returned null point for value: {}", value);
                    return;
                }
                lineProtocol = point.lineProtocol();
            }

            if (lineProtocol == null || lineProtocol.isEmpty()) {
                log.debug("Empty line protocol for value: {}", value);
                return;
            }

            synchronized (batchLock) {
                currentBatch.add(lineProtocol);
                if (shouldFlush()) {
                    flush();
                }
            }
        } catch (Exception e) {
            log.error("Error converting value: {}", value, e);
            throw e; // or skip based on configuration
        }
    }

    /**
     * Determines if the current batch should be flushed based on size or time interval.
     *
     * @return
     */
    private boolean shouldFlush() {
        boolean batchFull =
                currentBatch.size()
                        >= currentBatchSize; // use dynamic batch size, not configured batch size
        boolean timeoutReached =
                configuration.getFlushIntervalMillis() > 0
                        && (System.currentTimeMillis() - lastFlushTime)
                                >= configuration.getFlushIntervalMillis();

        return batchFull || (timeoutReached && !currentBatch.isEmpty());
    }

    /**
     * Flushes the current batch to OpenGemini. This method is synchronized to ensure thread safety.
     *
     * @throws Exception
     */
    private void flush() throws Exception {
        if (currentBatch == null || currentBatch.isEmpty()) {
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            writeBatchLineProtocols(currentBatch);
            long writeTime = System.currentTimeMillis() - startTime;

            // Track latency for evaluation
            totalLatency += writeTime;
            evaluateBatchSize();

            lastFlushTime = System.currentTimeMillis();
            currentBatch.clear();
        } catch (Exception e) {
            log.error("Error writing batch to OpenGemini: {}", e.getMessage(), e);
            pointsWrittenPerSecond.set(0);
            errorCount.incrementAndGet();
            writeErrors.inc();

            // Track error for evaluation
            errorInWindow++;
            evaluateBatchSize();

            throw e;
        }
    }

    /**
     * Writes a batch of points to OpenGemini. This method handles retries.
     *
     * @param lineProtocols a list of line protocol strings to write
     * @throws Exception
     */
    private void writeBatchLineProtocols(List<String> lineProtocols) throws Exception {
        if (lineProtocols.isEmpty()) return;

        log.debug(
                "Writing {} points to {}.{}",
                lineProtocols.size(),
                configuration.getDatabase(),
                configuration.getMeasurement());

        int retries = 0;
        Exception lastException = null;

        // Flag to track if this is the first retry, used by writeErrors metric
        boolean isFirstRetry = true;
        while (retries <= configuration.getMaxRetries()) {
            try {
                long startTime = System.currentTimeMillis();

                // use async API but wait synchronously for completion
                CompletableFuture<Void> future =
                        client.writeLineProtocols(configuration.getDatabase(), lineProtocols);
                future.get(configuration.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);

                long writeTime = System.currentTimeMillis() - startTime;

                // Update metrics
                writeLatency.update(writeTime);
                lastSuccessfulWriteTime.set(System.currentTimeMillis());
                totalPointsWritten.inc(lineProtocols.size());
                batchesWritten.incrementAndGet();
                long currentTime = System.currentTimeMillis();
                long timeDiff = currentTime - lastMetricUpdateTime;
                if (timeDiff > 1000) { // updated per second
                    long pointDiff = totalPointsWritten.getCount() - lastPointCount;
                    pointsWrittenPerSecond.set((pointDiff * 1000) / timeDiff);
                    lastMetricUpdateTime = currentTime;
                    lastPointCount = totalPointsWritten.getCount();
                }
                long estimatedBytes = calculateLinesSize(lineProtocols);
                totalBytesWritten.addAndGet(estimatedBytes);

                log.debug(
                        "Successfully wrote batch with {} points in {}ms",
                        lineProtocols.size(),
                        writeTime);
                // success
                return;

            } catch (Exception e) {
                lastException = e;
                log.warn(
                        "Error writing to OpenGemini (attempt {}/{}): {}",
                        retries + 1,
                        configuration.getMaxRetries() + 1,
                        e.getMessage());
                errorCount.incrementAndGet();
                // only increment writeErrors on retries after the first attempt
                // as flush() has already incremented it
                if (isFirstRetry) {
                    isFirstRetry = false;
                } else {
                    writeErrors.inc();
                    pointsWrittenPerSecond.set(0);
                }

                retries++;

                if (retries <= configuration.getMaxRetries()) {
                    // exponential backoff
                    long backoffMs = Math.min(1000L * (1L << retries), 10000L);
                    Thread.sleep(backoffMs);
                }
            }
        }

        throw new RuntimeException(
                "Failed to write batch after " + configuration.getMaxRetries() + " retries",
                lastException);
    }

    /**
     * Used to calculate line protocol size for Flink Metrics
     *
     * @param lineProtocols list of line protocol strings
     * @return total size in bytes
     */
    private long calculateLinesSize(List<String> lineProtocols) {
        long totalSize = 0;
        for (String lineProtocol : lineProtocols) {
            // Estimate based on line protocol size
            totalSize += lineProtocol.getBytes().length;
        }
        return totalSize;
    }

    /**
     * Called to close the sink and perform any final cleanup. This will flush any remaining points
     */
    @Override
    public void close() throws Exception {
        log.info("Closing OpenGeminiSink...");

        try {
            // write any remaining points
            flush();
        } catch (Exception e) {
            log.error("Error during final flush", e);
        }

        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing OpenGemini client", e);
                throw new RuntimeException("Failed to close OpenGemini client", e);
            }
        }
        log.info(
                "OpenGeminiSink closed. Final stats: totalPoints={}, batches={}, errors={}",
                totalPointsWritten.getCount(),
                batchesWritten.get(),
                errorCount.get());
    }

    /**
     * Called to snapshot the state of the sink for checkpointing. Batches are flushed and if any
     * exception thrown during flushing, currentBatch will be saved to checkpointedState.
     *
     * @param context
     * @throws Exception
     */
    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        log.info("Starting checkpoint {} for OpenGeminiSink", context.getCheckpointId());
        synchronized (batchLock) {
            try {
                // force flush current batch
                flush();
            } catch (Exception e) {
                log.error(
                        "Failed to flush during checkpoint, will save data that are not successfully flushed to state",
                        e);
            }

            checkpointedState.clear();
            if (!currentBatch.isEmpty()) {
                checkpointedState.add(new ArrayList<>(currentBatch));
            }
        }

        log.info(
                "Completed checkpoint {} with stats: points={}, batches={}, errors={}",
                context.getCheckpointId(),
                totalPointsWritten.getCount(),
                batchesWritten.get(),
                errorCount.get());
    }

    /**
     * Called to initialize currentBatch arraylist or recover currentBatch from checkpointedState.
     *
     * @param context the context for initializing the operator
     * @throws Exception
     */
    // TODO: check the usability of checkpointing
    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        ListStateDescriptor<List<String>> descriptor =
                new ListStateDescriptor<>(
                        "opengemini-sink-state",
                        TypeInformation.of(new TypeHint<List<String>>() {}));

        checkpointedState = context.getOperatorStateStore().getListState(descriptor);

        if (currentBatch == null) {
            currentBatch = new ArrayList<>(configuration.getBatchSize());
        }

        if (context.isRestored()) {
            // Restore state after a failure
            log.info("Restoring state for OpenGeminiSink");
            int restoredCount = 0;
            for (List<String> batch : checkpointedState.get()) {
                currentBatch.addAll(batch);
                restoredCount += batch.size();
            }
            log.info("Restored {} lines from checkpoint", restoredCount);
        }
    }

    /**
     * Evaluate batch size based on average latency and error count in the evaluation window. This
     * method is called after each successful or failed batch write. If the average latency is below
     * the increase threshold and there are no errors, the batch size is increased.
     */
    private void evaluateBatchSize() {
        evaluationCounter++;
        if (evaluationCounter >= DYNAMIC_BATCH_SIZE_EVALUATION_INTERVAL) {
            double avgLatency = totalLatency / ((double) (DYNAMIC_BATCH_SIZE_EVALUATION_INTERVAL));

            log.debug(
                    "Evaluating batch size: avgLatency={}ms, errors={}, currentBatchSize={}",
                    avgLatency,
                    errorInWindow,
                    currentBatchSize);

            if (avgLatency < DYNAMIC_BATCH_SIZE_INCREASE_THRESHOLD && errorInWindow == 0) {
                increaseBatchSize();
            } else if (avgLatency > DYNAMIC_BATCH_SIZE_DECREASE_THRESHOLD || errorInWindow > 0) {
                decreaseBatchSize();
            }

            resetEvaluationWindow();
        }
    }

    /** Increase batch size using multiplicative growth */
    private void increaseBatchSize() {
        int newSize = (int) (currentBatchSize * DYNAMIC_BATCH_INCREASE_FACTOR);
        int maxSize = initialBatchSize * 4;
        int oldSize = currentBatchSize;

        currentBatchSize = Math.min(newSize, maxSize);

        if (currentBatchSize != oldSize) {
            log.info("Increased batch size from {} to {}", oldSize, currentBatchSize);
        }
    }

    /** Decrease batch size using division */
    private void decreaseBatchSize() {
        int newSize = (int) (currentBatchSize * DYNAMIC_BATCH_DECREASE_FACTOR);
        int oldSize = currentBatchSize;

        // minimal batch size should not be less than initialBatchSize
        currentBatchSize = Math.max(newSize, initialBatchSize);

        if (currentBatchSize != oldSize) {
            log.info("Decreased batch size from {} to {}", oldSize, currentBatchSize);
        }
    }

    /** Reset evaluation window counters */
    private void resetEvaluationWindow() {
        evaluationCounter = 0;
        totalLatency = 0;
        errorInWindow = 0;
    }
}
