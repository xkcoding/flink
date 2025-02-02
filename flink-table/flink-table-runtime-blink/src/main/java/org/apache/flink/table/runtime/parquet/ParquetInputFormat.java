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

package org.apache.flink.table.runtime.parquet;

import org.apache.flink.api.common.io.FileInputFormat;
import org.apache.flink.core.fs.FileInputSplit;
import org.apache.flink.core.fs.FileStatus;
import org.apache.flink.core.fs.Path;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.Preconditions;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetInputSplit;
import org.apache.parquet.hadoop.ParquetRecordReader;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.MessageType;

import java.io.IOException;
import java.util.Map;

/**
 * The base InputFormat class to read from Parquet files.
 * For specific input types the {@link #convert(Object)} method need to be implemented.
 *
 * <p>Using {@link ParquetRecordReader} to read files instead of {@link org.apache.flink.core.fs.FSDataInputStream},
 * we override {@link #open(FileInputSplit)} and {@link #close()} to change the behaviors.
 *
 * <p>Additionally, we should avoid reading all of the footers to create {@link org.apache.flink.core.io.InputSplit}.
 * As mentioned in <a href="https://issues.apache.org/jira/browse/PARQUET-139">PARQUET-139</a>: "reading all of the
 * footers to get row group information is a bottle-neck when working with a large number of files and can
 * significantly delay a job because only one machine is working". Parquet was able to calculate splits based on the
 * absolute offset without reading file footers. So we can use the result of {@link #createInputSplits(int)} directly.
 */
public abstract class ParquetInputFormat<T, R> extends FileInputFormat<T> {
	private static final long serialVersionUID = 4308499696607786440L;

	protected final LogicalType[] fieldTypes;
	protected final String[] fieldNames;

	/**
	 * Stores filter instance as bytes, FilterPredicate is not serializable.
	 */
	private byte[] filterBytes;
	protected transient RecordReaderIterator<R> readerIterator;

	protected ParquetInputFormat(Path filePath, LogicalType[] fieldTypes, String[] fieldNames) {
		super(filePath);
		Preconditions.checkArgument(fieldNames != null && fieldNames.length > 0);
		Preconditions.checkArgument(fieldTypes != null && fieldTypes.length == fieldNames.length);
		this.fieldNames = fieldNames;
		this.fieldTypes = fieldTypes;
	}

	public void setFilterPredicate(FilterPredicate filter) throws Exception {
		if (filter != null) {
			filterBytes = InstantiationUtil.serializeObject(filter);
		} else {
			filterBytes = null;
		}
	}

	private FilterPredicate getFilterPredicate() {
		if (filterBytes != null) {
			try {
				return InstantiationUtil.deserializeObject(filterBytes, Thread.currentThread().getContextClassLoader());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		} else {
			return null;
		}
	}

	@Override
	public void open(FileInputSplit fileSplit) throws IOException {
		// init and register file system
		Configuration hadoopConf = new Configuration();

		ParquetInputSplit split = new ParquetInputSplit(
				new org.apache.hadoop.fs.Path(fileSplit.getPath().toUri()),
				fileSplit.getStart(),
				fileSplit.getStart() + fileSplit.getLength(),
				fileSplit.getLength(),
				fileSplit.getHostnames(),
				null
		);

		FilterPredicate filter = getFilterPredicate();
		if (filter != null) {
			org.apache.parquet.hadoop.ParquetInputFormat.setFilterPredicate(hadoopConf, filter);
		}

		TaskAttemptID attemptId = new TaskAttemptID(new TaskID(new JobID(), TaskType.MAP, 0), 0);
		TaskAttemptContext taskAttemptContext = new TaskAttemptContextImpl(hadoopConf, attemptId);
		RecordReader recordReader = createReader(filter);

		try {
			recordReader.initialize(split, taskAttemptContext);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		readerIterator = new RecordReaderIterator<>(recordReader);
	}

	protected RecordReader createReader(FilterPredicate filter) {
		ParquetReadSupport readSupport = new ParquetReadSupport(fieldTypes, fieldNames);
		if (filter != null) {
			return new ParquetRecordReader<>(readSupport, FilterCompat.get(filter));
		} else {
			return new ParquetRecordReader<>(readSupport);
		}
	}

	@Override
	public void close() throws IOException {
		if (readerIterator != null) {
			readerIterator.close();
		}
	}

	@Override
	public boolean reachedEnd() throws IOException {
		return !readerIterator.hasNext();
	}

	@Override
	public T nextRecord(T reuse) throws IOException {
		R next = readerIterator.next();
		return convert(next);
	}

	/**
	 * convert the parquet row to specific type T.
	 * NOTES: `current` is reused in {@link ParquetRecordConverter} to avoid creating row instance for each record,
	 * so the implementation of this method should copy the values of `current` to `reuse` instead of return
	 * `current` directly.
	 */
	protected abstract T convert(R current);

	/**
	 * Check whether Parquet schema matches the given Flink schema.
	 */
	private void checkSchema(Configuration hadoopConf, ParquetInputSplit split) throws IOException {
		ParquetMetadataConverter.MetadataFilter metadataFilter =
				ParquetMetadataConverter.range(split.getStart(), split.getEnd());
		ParquetMetadata parquetMetadata = ParquetFileReader.readFooter(hadoopConf, split.getPath(), metadataFilter);
		FileMetaData fileMetaData = parquetMetadata.getFileMetaData();
		MessageType parquetSchema = fileMetaData.getSchema();

		ParquetSchemaConverter schemaConverter = new ParquetSchemaConverter();
		Map<String, LogicalType> fieldName2TypeInfoMap =
				schemaConverter.convertToLogicalType(parquetSchema);

		for (int i = 0; i < fieldNames.length; ++i) {
			String fieldName = fieldNames[i];
			LogicalType fieldType = fieldTypes[i];
			if (!fieldName2TypeInfoMap.containsKey(fieldName)) {
				throw new IllegalArgumentException(fieldName + " can not be found in parquet schema");
			}
			LogicalType parquetFieldType = fieldName2TypeInfoMap.get(fieldName);
			if (!fieldType.equals(parquetFieldType)) {
				throw new IllegalArgumentException(parquetFieldType + " can not be convert to " + fieldType);
			}
		}
	}

	@Override
	protected boolean testForUnsplittable(FileStatus pathFile) {
		// parquet file is splittable
		return false;
	}

}
