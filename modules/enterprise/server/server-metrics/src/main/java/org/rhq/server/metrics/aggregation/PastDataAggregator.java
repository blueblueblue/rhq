package org.rhq.server.metrics.aggregation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.datastax.driver.core.ResultSet;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;

import org.rhq.server.metrics.StorageResultSetFuture;
import org.rhq.server.metrics.domain.AggregateNumericMetric;
import org.rhq.server.metrics.domain.CacheIndexEntry;
import org.rhq.server.metrics.domain.CacheIndexEntryMapper;
import org.rhq.server.metrics.domain.MetricsTable;
import org.rhq.server.metrics.domain.NumericMetric;
import org.rhq.server.metrics.domain.RawNumericMetric;
import org.rhq.server.metrics.domain.RawNumericMetricMapper;
import org.rhq.server.metrics.domain.ResultSetMapper;

/**
 * @author John Sanda
 */
class PastDataAggregator extends BaseAggregator {

    private static final Log LOG = LogFactory.getLog(PastDataAggregator.class);

    private DateTime startingDay;

    private DateTime currentDay;

    private PersistFunctions persistFns;

    private AtomicInteger rawSchedulesCount = new AtomicInteger();

    private AtomicInteger oneHourSchedulesCount = new AtomicInteger();

    private AtomicInteger sixHourScheduleCount = new AtomicInteger();

    void setStartingDay(DateTime startingDay) {
        this.startingDay = startingDay;
    }

    void setCurrentDay(DateTime currentDay) {
        this.currentDay = currentDay;
    }

    void setPersistFns(PersistFunctions persistFns) {
        this.persistFns = persistFns;
    }

    @Override
    protected ListenableFuture<List<CacheIndexEntry>> findIndexEntries() {
        return findPastIndexEntries();
    }

    private ListenableFuture<List<CacheIndexEntry>> findPastIndexEntries() {
        List<ListenableFuture<ResultSet>> insertFutures = new ArrayList<ListenableFuture<ResultSet>>();
        DateTime day = startingDay;

        while (day.isBefore(currentDay)) {
            insertFutures.add(dao.findPastCacheIndexEntriesBeforeToday(MetricsTable.RAW, day.getMillis(),
                AggregationManager.INDEX_PARTITION, day.plusHours(startTime.getHourOfDay()).getMillis()));
            day = day.plusDays(1);
        }
        insertFutures.add(dao.findPastCacheIndexEntriesFromToday(MetricsTable.RAW, currentDay.getMillis(),
            AggregationManager.INDEX_PARTITION, startTime.getMillis()));

        ListenableFuture<List<ResultSet>> insertsFuture = Futures.successfulAsList(insertFutures);
        return Futures.transform(insertsFuture, new Function<List<ResultSet>, List<CacheIndexEntry>>() {
            @Override
            public List<CacheIndexEntry> apply(List<ResultSet> resultSets) {
                CacheIndexEntryMapper mapper = new CacheIndexEntryMapper();
                List<CacheIndexEntry> indexEntries = new ArrayList<CacheIndexEntry>();

                for (ResultSet resultSet : resultSets) {
                    indexEntries.addAll(mapper.map(resultSet));
                }

                return indexEntries;
            }
        }, aggregationTasks);
    }

    @Override
    protected Runnable createAggregationTask(final CacheIndexEntry indexEntry) {
        return new Runnable() {
            @Override
            public void run() {
                if (indexEntry.getScheduleIds().isEmpty()) {
                    StorageResultSetFuture cacheFuture = dao.findCacheEntriesAsync(aggregationType.getCacheTable(),
                        indexEntry.getCollectionTimeSlice(), indexEntry.getStartScheduleId());
                    processRawDataCacheBlock(indexEntry, cacheFuture);
                } else {
                    List<StorageResultSetFuture> queryFutures = new ArrayList<StorageResultSetFuture>(PAST_DATA_BATCH_SIZE);
                    for (Integer scheduleId : indexEntry.getScheduleIds()) {
                        queryFutures.add(dao.findRawMetricsAsync(scheduleId, indexEntry.getCollectionTimeSlice(),
                            new DateTime(indexEntry.getCollectionTimeSlice()).plusHours(1).getMillis()));
                        if (queryFutures.size() == PAST_DATA_BATCH_SIZE) {
                            processBatch(queryFutures, indexEntry);
                            queryFutures = new ArrayList<StorageResultSetFuture>(PAST_DATA_BATCH_SIZE);
                        }
                    }
                    if (!queryFutures.isEmpty()) {
                        processBatch(queryFutures, indexEntry);
                    }
                }
            }
        };
    }

    @Override
    protected Map<AggregationType, Integer> getAggregationCounts() {
        return ImmutableMap.of(
            AggregationType.RAW, rawSchedulesCount.get(),
            AggregationType.ONE_HOUR, oneHourSchedulesCount.get(),
            AggregationType.SIX_HOUR, sixHourScheduleCount.get()
        );
    }

    private void processBatch(List<StorageResultSetFuture> queryFutures, CacheIndexEntry indexEntry) {

        ListenableFuture<List<ResultSet>> queriesFuture = Futures.successfulAsList(queryFutures);

        ListenableFuture<Iterable<List<RawNumericMetric>>> iterableFuture = Futures.transform(queriesFuture,
            toIterable(new RawNumericMetricMapper()), aggregationTasks);

        ListenableFuture<List<AggregateNumericMetric>> metricsFuture = Futures.transform(iterableFuture,
            computeAggregates(indexEntry.getCollectionTimeSlice(), RawNumericMetric.class), aggregationTasks);

        ListenableFuture<IndexAggregatesPair> pairFuture = Futures.transform(metricsFuture,
            indexAggregatesPair(indexEntry));

        boolean is6HourTimeSliceFinished = dateTimeService.is6HourTimeSliceFinished(
            indexEntry.getCollectionTimeSlice());
        boolean is24HourTimeSliceFinished = dateTimeService.is24HourTimeSliceFinished(
            indexEntry.getCollectionTimeSlice());
        ListenableFuture<List<ResultSet>> oneHourInsertsFuture;
        ListenableFuture<List<ResultSet>> insertsFuture;

        if (is6HourTimeSliceFinished) {
            oneHourInsertsFuture = Futures.transform(pairFuture, persistFns.persist1HourMetrics(), aggregationTasks);

            MetricsFuturesPair sixHourFuturesPair = process1HourData(indexEntry,
                proceedWithMetricsAfterInserts(new MetricsFuturesPair(oneHourInsertsFuture, metricsFuture)));

            if (is24HourTimeSliceFinished) {
                MetricsFuturesPair twentyFourHourFuturesPair = process6HourData(indexEntry,
                    proceedWithMetricsAfterInserts(sixHourFuturesPair));
                insertsFuture = twentyFourHourFuturesPair.resultSetsFuture;
            } else {
                insertsFuture = sixHourFuturesPair.resultSetsFuture;
            }
        } else {
            oneHourInsertsFuture = Futures.transform(pairFuture, persistFns.persist1HourMetricsAndUpdateCache(),
                aggregationTasks);

            insertsFuture = oneHourInsertsFuture;
        }

        ListenableFuture<ResultSet> deleteCacheFuture = Futures.transform(insertsFuture,
            deleteCacheEntry(indexEntry), aggregationTasks);

        ListenableFuture<ResultSet> deleteCacheIndexFuture = Futures.transform(deleteCacheFuture,
            deleteCacheIndexEntry(indexEntry), aggregationTasks);

        aggregationTaskFinished(deleteCacheIndexFuture, pairFuture, is6HourTimeSliceFinished, is24HourTimeSliceFinished);
    }

    @SuppressWarnings("unchecked")
    protected void processRawDataCacheBlock(CacheIndexEntry indexEntry, StorageResultSetFuture cacheFuture) {

        ListenableFuture<Iterable<List<RawNumericMetric>>> iterableFuture = Futures.transform(cacheFuture,
            toIterable(aggregationType.getCacheMapper()), aggregationTasks);

        ListenableFuture<List<AggregateNumericMetric>> metricsFuture = Futures.transform(iterableFuture,
            computeAggregates(indexEntry.getCollectionTimeSlice(), RawNumericMetric.class), aggregationTasks);

        ListenableFuture<IndexAggregatesPair> pairFuture = Futures.transform(metricsFuture,
            indexAggregatesPair(indexEntry));

        boolean is6HourTimeSliceFinished = dateTimeService.is6HourTimeSliceFinished(
            indexEntry.getCollectionTimeSlice());
        boolean is24HourTimeSliceFinished = dateTimeService.is24HourTimeSliceFinished(
            indexEntry.getCollectionTimeSlice());
        ListenableFuture<List<ResultSet>> oneHourInsertsFuture;
        ListenableFuture<List<ResultSet>> insertsFuture;

        if (is6HourTimeSliceFinished) {
            oneHourInsertsFuture = Futures.transform(pairFuture, persistFns.persist1HourMetrics(), aggregationTasks);

            MetricsFuturesPair sixHourFuturesPair = process1HourData(indexEntry,
                proceedWithMetricsAfterInserts(new MetricsFuturesPair(oneHourInsertsFuture, metricsFuture)));

            if (is24HourTimeSliceFinished) {
                MetricsFuturesPair twentyFourHourFuturesPair = process6HourData(indexEntry,
                    proceedWithMetricsAfterInserts(sixHourFuturesPair));
                insertsFuture = twentyFourHourFuturesPair.resultSetsFuture;
            } else {
                insertsFuture = sixHourFuturesPair.resultSetsFuture;
            }
        } else {
            oneHourInsertsFuture = Futures.transform(pairFuture, persistFns.persist1HourMetricsAndUpdateCache(),
                aggregationTasks);

            insertsFuture = oneHourInsertsFuture;
        }

        ListenableFuture<ResultSet> deleteCacheFuture = Futures.transform(insertsFuture,
            deleteCacheEntry(indexEntry), aggregationTasks);

        ListenableFuture<ResultSet> deleteCacheIndexFuture = Futures.transform(deleteCacheFuture,
            deleteCacheIndexEntry(indexEntry), aggregationTasks);

        aggregationTaskFinished(deleteCacheIndexFuture, pairFuture, is6HourTimeSliceFinished, is24HourTimeSliceFinished);
    }

    private <T extends NumericMetric> Function<List<ResultSet>, Iterable<List<T>>> toIterable(
        final ResultSetMapper<T> mapper) {

        return new Function<List<ResultSet>, Iterable<List<T>>>() {
            @Override
            public Iterable<List<T>> apply(final List<ResultSet> resultSets) {
                return new Iterable<List<T>>() {
                    private Iterator<ResultSet> resultSetIterator = resultSets.iterator();

                    @Override
                    public Iterator<List<T>> iterator() {
                        return new Iterator<List<T>>() {
                            @Override
                            public boolean hasNext() {
                                return resultSetIterator.hasNext();
                            }

                            @Override
                            public List<T> next() {
                                return mapper.mapAll(resultSetIterator.next());
                            }

                            @Override
                            public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }
                };
            }
        };
    }

    private Function<List<CombinedMetricsPair>, Iterable<List<AggregateNumericMetric>>> toIterable() {
        return new Function<List<CombinedMetricsPair>, Iterable<List<AggregateNumericMetric>>>() {
            @Override
            public Iterable<List<AggregateNumericMetric>> apply(final List<CombinedMetricsPair> pairs) {
                return new Iterable<List<AggregateNumericMetric>>() {
                    @Override
                    public Iterator<List<AggregateNumericMetric>> iterator() {
                        return new CombinedMetricsIterator(pairs);
                    }
                };
            }
        };
    }

    @SuppressWarnings("unchecked")
    private void aggregationTaskFinished(ListenableFuture<ResultSet> deleteCacheIndexFuture,
        ListenableFuture<IndexAggregatesPair> pairFuture, final boolean oneHourDataAggregated,
        final boolean sixHourDataAggregated) {

        final ListenableFuture<List<Object>> argsFuture = Futures.allAsList(deleteCacheIndexFuture, pairFuture);

        Futures.addCallback(argsFuture, new AggregationTaskFinishedCallback<List<Object>>() {
            @Override
            protected void onFinish(List<Object> args) {
                IndexAggregatesPair pair = (IndexAggregatesPair) args.get(1);

                rawSchedulesCount.addAndGet(pair.metrics.size());

                if (oneHourDataAggregated) {
                    oneHourSchedulesCount.addAndGet(pair.metrics.size());
                }

                if (sixHourDataAggregated) {
                    sixHourScheduleCount.addAndGet(pair.metrics.size());
                }
            }
        }, aggregationTasks);
    }

    private MetricsFuturesPair process1HourData(CacheIndexEntry indexEntry,
        ListenableFuture<List<AggregateNumericMetric>> metricsFuture) {

        DateTime sixHourTimeSlice = dateTimeService.get6HourTimeSlice(new DateTime(indexEntry.getCollectionTimeSlice()));

        boolean is24HourTimeSliceFinished = dateTimeService.is24HourTimeSliceFinished(new DateTime(
            indexEntry.getCollectionTimeSlice()));

        ListenableFuture<List<CombinedMetricsPair>> pairFutures = Futures.transform(metricsFuture,
            fetch1HourData(sixHourTimeSlice), aggregationTasks);

        ListenableFuture<Iterable<List<AggregateNumericMetric>>> iterableFuture = Futures.transform(pairFutures,
            toIterable(), aggregationTasks);

        ListenableFuture<List<AggregateNumericMetric>> sixHourMetricsFuture = Futures.transform(iterableFuture,
            computeAggregates(sixHourTimeSlice.getMillis(), AggregateNumericMetric.class), aggregationTasks);

        ListenableFuture<IndexAggregatesPair> pairFuture = Futures.transform(sixHourMetricsFuture,
            indexAggregatesPair(indexEntry));

        ListenableFuture<List<ResultSet>> insertsFuture;
        if (is24HourTimeSliceFinished) {
            insertsFuture = Futures.transform(pairFuture, persistFns.persist6HourMetrics(), aggregationTasks);
        } else {
            insertsFuture = Futures.transform(pairFuture, persistFns.persist6HourMetricsAndUpdateCache(),
                aggregationTasks);
        }

        return new MetricsFuturesPair(insertsFuture, sixHourMetricsFuture);
    }

    private MetricsFuturesPair process6HourData(CacheIndexEntry indexEntry,
        ListenableFuture<List<AggregateNumericMetric>> sixHourMetricsFuture) {

        DateTime timeSlice = dateTimeService.get24HourTimeSlice(indexEntry.getCollectionTimeSlice());

        ListenableFuture<List<CombinedMetricsPair>> pairFutures = Futures.transform(sixHourMetricsFuture,
            fetch6HourData(timeSlice));

        ListenableFuture<Iterable<List<AggregateNumericMetric>>> iterableFuture = Futures.transform(pairFutures,
            toIterable(), aggregationTasks);

        ListenableFuture<List<AggregateNumericMetric>> twentyFourHourMetricsFuture = Futures.transform(iterableFuture,
            computeAggregates(timeSlice.getMillis(), AggregateNumericMetric.class), aggregationTasks);

        ListenableFuture<IndexAggregatesPair> pairFuture = Futures.transform(twentyFourHourMetricsFuture,
            indexAggregatesPair(indexEntry));

        ListenableFuture<List<ResultSet>> insertsFuture = Futures.transform(pairFuture,
            persistFns.persist24HourMetrics(), aggregationTasks);

        return new MetricsFuturesPair(insertsFuture, twentyFourHourMetricsFuture);
    }

    /**
     * <p>
     * This method is intended for use when aggregating past data and 6 hour and 24 hour data need to be recomputed. It
     * serves two purposes. First, it ensures computations proceed only after the necessary writes complete
     * successfully and makes the written data (which is still in memory) available through <code>metricsFuture</code>.
     * </p>
     * <p>
     * See {@link CombinedMetricsPair} and {@link CombinedMetricsIterator} for details on why it is important to use
     * the data still sitting in memory.
     * </p>
     *
     * @param pair A container for the future of the inserts of 1 hour or 6 hour data that was just aggregated coupled
     *             with the future of the in memory aggregate metrics just inserted.
     *
     * @return A future of the inserted aggregate data. Note that if any of the inserts fail, then any subsequent
     * functions that using the returned future as input, will not be executed.
     */
    @SuppressWarnings("unchecked")
    private ListenableFuture<List<AggregateNumericMetric>> proceedWithMetricsAfterInserts(MetricsFuturesPair pair) {

        final ListenableFuture<List<List<?>>> futures = Futures.allAsList(pair.resultSetsFuture, pair.metricsFuture);
        return Futures.transform(futures, new Function<List<List<?>>, List<AggregateNumericMetric>>() {
            @Override
            public List<AggregateNumericMetric> apply(List<List<?>> input) {
                return (List<AggregateNumericMetric>) input.get(1);
            }
        });
    }

    private AsyncFunction<List<AggregateNumericMetric>, List<CombinedMetricsPair>> fetch1HourData(
        final DateTime timeSliceStart) {

        return new AsyncFunction<List<AggregateNumericMetric>, List<CombinedMetricsPair>>() {

            final DateTime timeSliceEnd = dateTimeService.get6HourTimeSliceEnd(timeSliceStart);

            @Override
            public ListenableFuture<List<CombinedMetricsPair>> apply(List<AggregateNumericMetric> metrics) {
                List<ListenableFuture<CombinedMetricsPair>> pairFutures =
                    new ArrayList<ListenableFuture<CombinedMetricsPair>>();

                for (AggregateNumericMetric metric : metrics) {
                    StorageResultSetFuture queryFuture = dao.findOneHourMetricsAsync(metric.getScheduleId(),
                        timeSliceStart.getMillis(), timeSliceEnd.getMillis());
                    ListenableFuture<CombinedMetricsPair> pairFuture = Futures.transform(queryFuture,
                        combineMetrics(metric));
                    pairFutures.add(pairFuture);
                }

                return Futures.allAsList(pairFutures);
            }
        };
    }

    private AsyncFunction<List<AggregateNumericMetric>, List<CombinedMetricsPair>> fetch6HourData(
        final DateTime timeSliceStart) {

        final DateTime timeSliceEnd = dateTimeService.get24HourTimeSliceEnd(timeSliceStart);

        return new AsyncFunction<List<AggregateNumericMetric>, List<CombinedMetricsPair>>() {
            @Override
            public ListenableFuture<List<CombinedMetricsPair>> apply(List<AggregateNumericMetric> metrics)
                throws Exception {
                List<ListenableFuture<CombinedMetricsPair>> pairFutures =
                    new ArrayList<ListenableFuture<CombinedMetricsPair>>();

                for (AggregateNumericMetric metric : metrics) {
                    StorageResultSetFuture queryFuture = dao.findSixHourMetricsAsync(metric.getScheduleId(),
                        timeSliceStart.getMillis(), timeSliceEnd.getMillis());
                    ListenableFuture<CombinedMetricsPair> pairFuture = Futures.transform(queryFuture,
                        combineMetrics(metric));
                    pairFutures.add(pairFuture);
                }

                return Futures.allAsList(pairFutures);
            }
        };
    }

    private Function<ResultSet, CombinedMetricsPair> combineMetrics(final AggregateNumericMetric metric) {
        return new Function<ResultSet, CombinedMetricsPair>() {
            @Override
            public CombinedMetricsPair apply(ResultSet resultSet) {
                return new CombinedMetricsPair(resultSet, metric);
            }
        };
    }

}
