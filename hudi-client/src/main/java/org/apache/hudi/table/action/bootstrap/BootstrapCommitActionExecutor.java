/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.action.bootstrap;

import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.avro.model.HoodieFileStatus;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.bootstrap.BootstrapMode;
import org.apache.hudi.client.bootstrap.BootstrapRecordPayload;
import org.apache.hudi.client.bootstrap.BootstrapSchemaProvider;
import org.apache.hudi.client.bootstrap.BootstrapWriteStatus;
import org.apache.hudi.client.bootstrap.FullRecordBootstrapDataProvider;
import org.apache.hudi.client.bootstrap.selector.BootstrapModeSelector;
import org.apache.hudi.client.bootstrap.translator.BootstrapPartitionPathTranslator;
import org.apache.hudi.common.bootstrap.FileStatusUtils;
import org.apache.hudi.common.bootstrap.index.BootstrapIndex;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.BootstrapFileMapping;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieInstant.State;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.ParquetReaderIterator;
import org.apache.hudi.common.util.ReflectionUtils;
import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.common.util.queue.BoundedInMemoryExecutor;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.execution.SparkBoundedInMemoryExecutor;
import org.apache.hudi.io.HoodieBootstrapHandle;
import org.apache.hudi.keygen.KeyGenerator;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.WorkloadProfile;
import org.apache.hudi.table.action.HoodieWriteMetadata;
import org.apache.hudi.table.action.commit.BaseCommitActionExecutor;
import org.apache.hudi.table.action.commit.BulkInsertCommitActionExecutor;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hudi.table.action.commit.CommitActionExecutor;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroReadSupport;
import org.apache.parquet.avro.AvroSchemaConverter;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.MessageType;
import org.apache.spark.Partitioner;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BootstrapCommitActionExecutor<T extends HoodieRecordPayload<T>>
    extends BaseCommitActionExecutor<T, HoodieBootstrapWriteMetadata> {

  private static final Logger LOG = LogManager.getLogger(BootstrapCommitActionExecutor.class);
  protected String bootstrapSchema = null;
  private transient FileSystem bootstrapSourceFileSystem;

  public BootstrapCommitActionExecutor(JavaSparkContext jsc, HoodieWriteConfig config, HoodieTable<?> table,
      Option<Map<String, String>> extraMetadata) {
    super(jsc, new HoodieWriteConfig.Builder().withProps(config.getProps())
        .withAutoCommit(true).withWriteStatusClass(BootstrapWriteStatus.class)
        .withBulkInsertParallelism(config.getBootstrapParallelism())
        .build(), table, HoodieTimeline.METADATA_BOOTSTRAP_INSTANT_TS, WriteOperationType.BOOTSTRAP,
        extraMetadata);
    bootstrapSourceFileSystem = FSUtils.getFs(config.getBootstrapSourceBasePath(), hadoopConf);
  }

  private void validate() {
    ValidationUtils.checkArgument(config.getBootstrapSourceBasePath() != null,
        "Ensure Bootstrap Source Path is set");
    ValidationUtils.checkArgument(config.getBootstrapModeSelectorClass() != null,
        "Ensure Bootstrap Partition Selector is set");
    ValidationUtils.checkArgument(config.getBootstrapKeyGeneratorClass() != null,
        "Ensure bootstrap key generator class is set");
  }

  @Override
  public HoodieBootstrapWriteMetadata execute() {
    validate();
    try {
      HoodieTableMetaClient metaClient = table.getMetaClient();
      Option<HoodieInstant> completetedInstant =
          metaClient.getActiveTimeline().getCommitsTimeline().filterCompletedInstants().lastInstant();
      ValidationUtils.checkArgument(!completetedInstant.isPresent(),
          "Active Timeline is expected to be empty for bootstrap to be performed. "
              + "If you want to re-bootstrap, please rollback bootstrap first !!");
      Map<BootstrapMode, List<Pair<String, List<HoodieFileStatus>>>> partitionSelections = listAndProcessSourcePartitions();

      // First run metadata bootstrap which will auto commit
      Option<HoodieWriteMetadata> metadataResult = metadataBootstrap(partitionSelections.get(BootstrapMode.METADATA_ONLY));
      // if there are full bootstrap to be performed, perform that too
      Option<HoodieWriteMetadata> fullBootstrapResult = fullBootstrap(partitionSelections.get(BootstrapMode.FULL_RECORD));
      return new HoodieBootstrapWriteMetadata(metadataResult, fullBootstrapResult);
    } catch (IOException ioe) {
      throw new HoodieIOException(ioe.getMessage(), ioe);
    }
  }

  protected String getSchemaToStoreInCommit() {
    return bootstrapSchema;
  }

  /**
   * Perform Metadata Bootstrap.
   * @param partitionFilesList List of partitions and files within that partitions
   */
  protected Option<HoodieWriteMetadata> metadataBootstrap(List<Pair<String, List<HoodieFileStatus>>> partitionFilesList) {
    if (null == partitionFilesList || partitionFilesList.isEmpty()) {
      return Option.empty();
    }

    HoodieTableMetaClient metaClient = table.getMetaClient();
    metaClient.getActiveTimeline().createNewInstant(
        new HoodieInstant(State.REQUESTED, metaClient.getCommitActionType(),
            HoodieTimeline.METADATA_BOOTSTRAP_INSTANT_TS));

    table.getActiveTimeline().transitionRequestedToInflight(new HoodieInstant(State.REQUESTED,
        metaClient.getCommitActionType(), HoodieTimeline.METADATA_BOOTSTRAP_INSTANT_TS), Option.empty());

    JavaRDD<BootstrapWriteStatus> bootstrapWriteStatuses = runMetadataBootstrap(partitionFilesList);

    HoodieWriteMetadata result = new HoodieWriteMetadata();
    updateIndexAndCommitIfNeeded(bootstrapWriteStatuses.map(w -> w), result);
    return Option.of(result);
  }

  @Override
  protected void commit(Option<Map<String, String>> extraMetadata, HoodieWriteMetadata result) {
    // Perform bootstrap index write and then commit. Make sure both record-key and bootstrap-index
    // is all done in a single job DAG.
    Map<String, List<Pair<BootstrapFileMapping, HoodieWriteStat>>> bootstrapSourceAndStats =
        result.getWriteStatuses().collect().stream()
            .map(w -> {
              BootstrapWriteStatus ws = (BootstrapWriteStatus) w;
              return Pair.of(ws.getBootstrapSourceFileMapping(), ws.getStat());
            }).collect(Collectors.groupingBy(w -> w.getKey().getPartitionPath()));
    HoodieTableMetaClient metaClient = table.getMetaClient();
    try (BootstrapIndex.IndexWriter indexWriter = BootstrapIndex.getBootstrapIndex(metaClient)
        .createWriter(metaClient.getTableConfig().getBootstrapBasePath().get())) {
      LOG.info("Starting to write bootstrap index for source " + config.getBootstrapSourceBasePath() + " in table "
          + config.getBasePath());
      indexWriter.begin();
      bootstrapSourceAndStats.forEach((key, value) -> indexWriter.appendNextPartition(key,
          value.stream().map(Pair::getKey).collect(Collectors.toList())));
      indexWriter.finish();
      LOG.info("Finished writing bootstrap index for source " + config.getBootstrapSourceBasePath() + " in table "
          + config.getBasePath());
    }

    super.commit(extraMetadata, result, bootstrapSourceAndStats.values().stream()
        .flatMap(f -> f.stream().map(Pair::getValue)).collect(Collectors.toList()));
    LOG.info("Committing metadata bootstrap !!");
  }

  /**
   * Perform Metadata Bootstrap.
   * @param partitionFilesList List of partitions and files within that partitions
   */
  protected Option<HoodieWriteMetadata> fullBootstrap(List<Pair<String, List<HoodieFileStatus>>> partitionFilesList) {
    if (null == partitionFilesList || partitionFilesList.isEmpty()) {
      return Option.empty();
    }
    TypedProperties properties = new TypedProperties();
    properties.putAll(config.getProps());
    FullRecordBootstrapDataProvider inputProvider =
        (FullRecordBootstrapDataProvider) ReflectionUtils.loadClass(config.getFullBootstrapInputProvider(),
            properties, jsc);
    JavaRDD<HoodieRecord> inputRecordsRDD =
        inputProvider.generateInputRecordRDD("bootstrap_source", config.getBootstrapSourceBasePath(),
            partitionFilesList);
    // Start Full Bootstrap
    final HoodieInstant requested = new HoodieInstant(State.REQUESTED, table.getMetaClient().getCommitActionType(),
        HoodieTimeline.FULL_BOOTSTRAP_INSTANT_TS);
    table.getActiveTimeline().createNewInstant(requested);

    // Setup correct schema and run bulk insert.
    return Option.of(getBulkInsertActionExecutor(inputRecordsRDD).execute());
  }

  protected CommitActionExecutor<T> getBulkInsertActionExecutor(JavaRDD<HoodieRecord> inputRecordsRDD) {
    return new BulkInsertCommitActionExecutor(jsc, new HoodieWriteConfig.Builder().withProps(config.getProps())
        .withSchema(bootstrapSchema).build(), table, HoodieTimeline.FULL_BOOTSTRAP_INSTANT_TS,
        inputRecordsRDD, extraMetadata);
  }

  private BootstrapWriteStatus handleMetadataBootstrap(String srcPartitionPath, String partitionPath,
                                                       HoodieFileStatus srcFileStatus, KeyGenerator keyGenerator) {

    Path sourceFilePath = FileStatusUtils.toPath(srcFileStatus.getPath());
    HoodieBootstrapHandle bootstrapHandle = new HoodieBootstrapHandle(config, HoodieTimeline.METADATA_BOOTSTRAP_INSTANT_TS,
        table, partitionPath, FSUtils.createNewFileIdPfx(), table.getSparkTaskContextSupplier());
    Schema avroSchema = null;
    try {
      ParquetMetadata readFooter = ParquetFileReader.readFooter(table.getHadoopConf(), sourceFilePath,
          ParquetMetadataConverter.NO_FILTER);
      MessageType parquetSchema = readFooter.getFileMetaData().getSchema();
      avroSchema = new AvroSchemaConverter().convert(parquetSchema);
      Schema recordKeySchema = HoodieAvroUtils.generateProjectionSchema(avroSchema,
          keyGenerator.getRecordKeyFieldNames());
      LOG.info("Schema to be used for reading record Keys :" + recordKeySchema);
      AvroReadSupport.setAvroReadSchema(table.getHadoopConf(), recordKeySchema);
      AvroReadSupport.setRequestedProjection(table.getHadoopConf(), recordKeySchema);

      BoundedInMemoryExecutor<GenericRecord, HoodieRecord, Void> wrapper = null;
      try (ParquetReader<IndexedRecord> reader =
          AvroParquetReader.<IndexedRecord>builder(sourceFilePath).withConf(table.getHadoopConf()).build()) {
        wrapper = new SparkBoundedInMemoryExecutor<GenericRecord, HoodieRecord, Void>(config,
            new ParquetReaderIterator(reader), new BootstrapRecordConsumer(bootstrapHandle), inp -> {
          String recKey = keyGenerator.getKey(inp).getRecordKey();
          GenericRecord gr = new GenericData.Record(HoodieAvroUtils.RECORD_KEY_SCHEMA);
          gr.put(HoodieRecord.RECORD_KEY_METADATA_FIELD, recKey);
          BootstrapRecordPayload payload = new BootstrapRecordPayload(gr);
          HoodieRecord rec = new HoodieRecord(new HoodieKey(recKey, partitionPath), payload);
          return rec;
        });
        wrapper.execute();
      } catch (Exception e) {
        throw new HoodieException(e);
      } finally {
        bootstrapHandle.close();
        if (null != wrapper) {
          wrapper.shutdownNow();
        }
      }
    } catch (IOException e) {
      throw new HoodieIOException(e.getMessage(), e);
    }
    BootstrapWriteStatus writeStatus = (BootstrapWriteStatus)bootstrapHandle.getWriteStatus();
    BootstrapFileMapping bootstrapFileMapping = new BootstrapFileMapping(
        config.getBootstrapSourceBasePath(), srcPartitionPath, partitionPath,
        srcFileStatus, writeStatus.getFileId());
    writeStatus.setBootstrapSourceFileMapping(bootstrapFileMapping);
    return writeStatus;
  }

  /**
   * Return Bootstrap Mode selections for partitions listed and figure out bootstrap Schema.
   * @return
   * @throws IOException
   */
  private Map<BootstrapMode, List<Pair<String, List<HoodieFileStatus>>>> listAndProcessSourcePartitions() throws IOException {
    List<Pair<String, List<HoodieFileStatus>>> folders = BootstrapUtils.getAllLeafFoldersWithFiles(
            table.getMetaClient(), bootstrapSourceFileSystem, config.getBootstrapSourceBasePath(), jsc);

    LOG.info("Fetching Bootstrap Schema !!");
    BootstrapSchemaProvider sourceSchemaProvider = new BootstrapSchemaProvider(config);
    bootstrapSchema = sourceSchemaProvider.getBootstrapSchema(jsc, folders).toString();
    LOG.info("Bootstrap Schema :" + bootstrapSchema);

    BootstrapModeSelector selector =
        (BootstrapModeSelector) ReflectionUtils.loadClass(config.getBootstrapModeSelectorClass(), config);

    Map<BootstrapMode, List<String>> result = selector.select(folders);
    Map<String, List<HoodieFileStatus>> partitionToFiles = folders.stream().collect(
        Collectors.toMap(Pair::getKey, Pair::getValue));

    // Ensure all partitions are accounted for
    ValidationUtils.checkArgument(partitionToFiles.keySet().equals(
        result.values().stream().flatMap(Collection::stream).collect(Collectors.toSet())));

    return result.entrySet().stream().map(e -> Pair.of(e.getKey(), e.getValue().stream()
        .map(p -> Pair.of(p, partitionToFiles.get(p))).collect(Collectors.toList())))
        .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  private JavaRDD<BootstrapWriteStatus> runMetadataBootstrap(List<Pair<String, List<HoodieFileStatus>>> partitions) {
    if (null == partitions || partitions.isEmpty()) {
      return jsc.emptyRDD();
    }

    TypedProperties properties = new TypedProperties();
    properties.putAll(config.getProps());
    KeyGenerator keyGenerator  = (KeyGenerator) ReflectionUtils.loadClass(config.getBootstrapKeyGeneratorClass(),
        properties);
    BootstrapPartitionPathTranslator translator = (BootstrapPartitionPathTranslator) ReflectionUtils.loadClass(
        config.getBootstrapPartitionPathTranslatorClass(), properties);

    List<Pair<String, Pair<String, HoodieFileStatus>>> bootstrapPaths = partitions.stream()
        .flatMap(p -> {
          String translatedPartitionPath = translator.getBootstrapTranslatedPath(p.getKey());
          return p.getValue().stream().map(f -> Pair.of(p.getKey(), Pair.of(translatedPartitionPath, f)));
        })
        .collect(Collectors.toList());

    return jsc.parallelize(bootstrapPaths, config.getBootstrapParallelism())
        .map(partitionFsPair -> handleMetadataBootstrap(partitionFsPair.getLeft(), partitionFsPair.getRight().getLeft(),
            partitionFsPair.getRight().getRight(), keyGenerator));
  }

  //TODO: Once we decouple commit protocol, we should change the class hierarchy to avoid doing this.
  @Override
  protected Partitioner getUpsertPartitioner(WorkloadProfile profile) {
    throw new UnsupportedOperationException("Should not called in bootstrap code path");
  }

  @Override
  protected Partitioner getInsertPartitioner(WorkloadProfile profile) {
    throw new UnsupportedOperationException("Should not called in bootstrap code path");
  }

  @Override
  protected Iterator<List<WriteStatus>> handleInsert(String idPfx, Iterator<HoodieRecord<T>> recordItr) {
    throw new UnsupportedOperationException("Should not called in bootstrap code path");
  }

  @Override
  protected Iterator<List<WriteStatus>> handleUpdate(String partitionPath, String fileId, Iterator<HoodieRecord<T>> recordItr) {
    throw new UnsupportedOperationException("Should not called in bootstrap code path");
  }
}