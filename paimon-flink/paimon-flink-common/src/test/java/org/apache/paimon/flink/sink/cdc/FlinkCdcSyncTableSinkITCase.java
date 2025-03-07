/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.sink.cdc;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.flink.util.AbstractTestBase;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.options.MemorySize;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReaderIterator;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.SchemaUtils;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.FailingFileIO;
import org.apache.paimon.utils.TraceableFileIO;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** IT cases for {@link CdcSinkBuilder}. */
public class FlinkCdcSyncTableSinkITCase extends AbstractTestBase {

    @TempDir java.nio.file.Path tempDir;

    @Test
    @Timeout(120)
    public void testRandomCdcEvents() throws Exception {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        int numEvents = random.nextInt(1500) + 1;
        int numSchemaChanges = Math.min(numEvents / 2, random.nextInt(10) + 1);
        int numPartitions = random.nextInt(3) + 1;
        int numKeys = random.nextInt(150) + 1;
        int numBucket = random.nextInt(5) + 1;
        boolean enableFailure = random.nextBoolean();

        TestTable testTable =
                new TestTable("test_tbl", numEvents, numSchemaChanges, numPartitions, numKeys);

        Path tablePath;
        FileIO fileIO;
        String failingName = UUID.randomUUID().toString();
        if (enableFailure) {
            tablePath = new Path(FailingFileIO.getFailingPath(failingName, tempDir.toString()));
            fileIO = new FailingFileIO();
        } else {
            tablePath = new Path(TraceableFileIO.SCHEME + "://" + tempDir.toString());
            fileIO = LocalFileIO.create();
        }

        // no failure when creating table
        FailingFileIO.reset(failingName, 0, 1);

        FileStoreTable table =
                createFileStoreTable(
                        tablePath,
                        fileIO,
                        testTable.initialRowType(),
                        Collections.singletonList("pt"),
                        Arrays.asList("pt", "k"),
                        numBucket);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.getCheckpointConfig().setCheckpointInterval(100);
        if (!enableFailure) {
            env.setRestartStrategy(RestartStrategies.noRestart());
        }

        TestCdcSourceFunction sourceFunction = new TestCdcSourceFunction(testTable.events());
        DataStreamSource<TestCdcEvent> source = env.addSource(sourceFunction);
        source.setParallelism(2);
        new CdcSinkBuilder<TestCdcEvent>()
                .withInput(source)
                .withParserFactory(TestCdcEventParser::new)
                .withTable(table)
                .withParallelism(3)
                .build();

        // enable failure when running jobs if needed
        FailingFileIO.reset(failingName, 10, 10000);

        env.execute();

        // no failure when checking results
        FailingFileIO.reset(failingName, 0, 1);

        table = table.copyWithLatestSchema();
        SchemaManager schemaManager = new SchemaManager(table.fileIO(), table.location());
        TableSchema schema = schemaManager.latest().get();

        ReadBuilder readBuilder = table.newReadBuilder();
        TableScan.Plan plan = readBuilder.newScan().plan();
        try (RecordReaderIterator<InternalRow> it =
                new RecordReaderIterator<>(readBuilder.newRead().createReader(plan))) {
            testTable.assertResult(schema, it);
        }
    }

    private FileStoreTable createFileStoreTable(
            Path tablePath,
            FileIO fileIO,
            RowType rowType,
            List<String> partitions,
            List<String> primaryKeys,
            int numBucket)
            throws Exception {
        Options conf = new Options();
        conf.set(CoreOptions.BUCKET, numBucket);
        conf.set(CoreOptions.WRITE_BUFFER_SIZE, new MemorySize(4096 * 3));
        conf.set(CoreOptions.PAGE_SIZE, new MemorySize(4096));

        TableSchema tableSchema =
                SchemaUtils.forceCommit(
                        new SchemaManager(fileIO, tablePath),
                        new Schema(rowType.getFields(), partitions, primaryKeys, conf.toMap(), ""));
        return FileStoreTableFactory.create(fileIO, tablePath, tableSchema);
    }
}
