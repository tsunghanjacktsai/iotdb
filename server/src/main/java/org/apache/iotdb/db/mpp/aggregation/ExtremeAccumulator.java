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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.mpp.aggregation;

import org.apache.iotdb.tsfile.exception.write.UnSupportedDataTypeException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.statistics.Statistics;
import org.apache.iotdb.tsfile.read.common.TimeRange;
import org.apache.iotdb.tsfile.read.common.block.column.Column;
import org.apache.iotdb.tsfile.read.common.block.column.ColumnBuilder;
import org.apache.iotdb.tsfile.read.common.block.column.TimeColumn;
import org.apache.iotdb.tsfile.utils.TsPrimitiveType;

import static com.google.common.base.Preconditions.checkArgument;

public class ExtremeAccumulator implements Accumulator {

  private final TSDataType seriesDataType;
  private TsPrimitiveType extremeResult;
  private boolean initResult;

  public ExtremeAccumulator(TSDataType seriesDataType) {
    this.seriesDataType = seriesDataType;
    this.extremeResult = TsPrimitiveType.getByType(seriesDataType);
  }

  @Override
  public void addInput(Column[] column, TimeRange timeRange) {
    switch (seriesDataType) {
      case INT32:
        addIntInput(column, timeRange);
        break;
      case INT64:
        addLongInput(column, timeRange);
        break;
      case FLOAT:
        addFloatInput(column, timeRange);
        break;
      case DOUBLE:
        addDoubleInput(column, timeRange);
        break;
      case TEXT:
      case BOOLEAN:
      default:
        throw new UnSupportedDataTypeException(
            String.format("Unsupported data type in Extreme: %s", seriesDataType));
    }
  }

  // partialResult should be like: | PartialExtremeValue |
  @Override
  public void addIntermediate(Column[] partialResult) {
    checkArgument(partialResult.length == 1, "partialResult of ExtremeValue should be 1");
    if (partialResult[0].isNull(0)) {
      return;
    }
    switch (seriesDataType) {
      case INT32:
        updateIntResult(partialResult[0].getInt(0));
        break;
      case INT64:
        updateLongResult(partialResult[0].getLong(0));
        break;
      case FLOAT:
        updateFloatResult(partialResult[0].getFloat(0));
        break;
      case DOUBLE:
        updateDoubleResult(partialResult[0].getDouble(0));
        break;
      case TEXT:
      case BOOLEAN:
      default:
        throw new UnSupportedDataTypeException(
            String.format("Unsupported data type in Extreme: %s", seriesDataType));
    }
  }

  @Override
  public void addStatistics(Statistics statistics) {
    switch (seriesDataType) {
      case INT32:
        updateIntResult((int) statistics.getMaxValue());
        updateIntResult((int) statistics.getMinValue());
        break;
      case INT64:
        updateLongResult((long) statistics.getMaxValue());
        updateLongResult((long) statistics.getMinValue());
        break;
      case FLOAT:
        updateFloatResult((float) statistics.getMaxValue());
        updateFloatResult((float) statistics.getMinValue());
        break;
      case DOUBLE:
        updateDoubleResult((double) statistics.getMaxValue());
        updateDoubleResult((double) statistics.getMinValue());
        break;
      case TEXT:
      case BOOLEAN:
      default:
        throw new UnSupportedDataTypeException(
            String.format("Unsupported data type in Extreme: %s", seriesDataType));
    }
  }

  @Override
  public void setFinal(Column finalResult) {
    if (finalResult.isNull(0)) {
      return;
    }
    initResult = true;
    extremeResult.setObject(finalResult.getObject(0));
  }

  // columnBuilder should be single in ExtremeAccumulator
  @Override
  public void outputIntermediate(ColumnBuilder[] columnBuilders) {
    checkArgument(columnBuilders.length == 1, "partialResult of ExtremeValue should be 1");
    if (!initResult) {
      columnBuilders[0].appendNull();
      return;
    }
    switch (seriesDataType) {
      case INT32:
        columnBuilders[0].writeInt(extremeResult.getInt());
        break;
      case INT64:
        columnBuilders[0].writeLong(extremeResult.getLong());
        break;
      case FLOAT:
        columnBuilders[0].writeFloat(extremeResult.getFloat());
        break;
      case DOUBLE:
        columnBuilders[0].writeDouble(extremeResult.getDouble());
        break;
      case TEXT:
      case BOOLEAN:
      default:
        throw new UnSupportedDataTypeException(
            String.format("Unsupported data type in Extreme: %s", seriesDataType));
    }
  }

  @Override
  public void outputFinal(ColumnBuilder columnBuilder) {
    if (!initResult) {
      columnBuilder.appendNull();
      return;
    }
    switch (seriesDataType) {
      case INT32:
        columnBuilder.writeInt(extremeResult.getInt());
        break;
      case INT64:
        columnBuilder.writeLong(extremeResult.getLong());
        break;
      case FLOAT:
        columnBuilder.writeFloat(extremeResult.getFloat());
        break;
      case DOUBLE:
        columnBuilder.writeDouble(extremeResult.getDouble());
        break;
      case TEXT:
      case BOOLEAN:
      default:
        throw new UnSupportedDataTypeException(
            String.format("Unsupported data type in Extreme: %s", seriesDataType));
    }
  }

  @Override
  public void reset() {
    initResult = false;
    extremeResult.reset();
  }

  @Override
  public boolean hasFinalResult() {
    return false;
  }

  @Override
  public TSDataType[] getIntermediateType() {
    return new TSDataType[] {extremeResult.getDataType()};
  }

  @Override
  public TSDataType getFinalType() {
    return extremeResult.getDataType();
  }

  private void addIntInput(Column[] column, TimeRange timeRange) {
    TimeColumn timeColumn = (TimeColumn) column[0];
    for (int i = 0; i < timeColumn.getPositionCount(); i++) {
      long curTime = timeColumn.getLong(i);
      if (curTime > timeRange.getMax() || curTime < timeRange.getMin()) {
        break;
      }
      if (!column[1].isNull(i)) {
        updateIntResult(column[1].getInt(i));
      }
    }
  }

  private void updateIntResult(int extVal) {
    int absExtVal = Math.abs(extVal);
    int candidateResult = extremeResult.getInt();
    int absCandidateResult = Math.abs(extremeResult.getInt());

    if (!initResult
        || (absExtVal > absCandidateResult)
        || (absExtVal == absCandidateResult) && extVal > candidateResult) {
      initResult = true;
      extremeResult.setInt(extVal);
    }
  }

  private void addLongInput(Column[] column, TimeRange timeRange) {
    TimeColumn timeColumn = (TimeColumn) column[0];
    for (int i = 0; i < timeColumn.getPositionCount(); i++) {
      long curTime = timeColumn.getLong(i);
      if (curTime > timeRange.getMax() || curTime < timeRange.getMin()) {
        break;
      }
      if (!column[1].isNull(i)) {
        updateLongResult(column[1].getLong(i));
      }
    }
  }

  private void updateLongResult(long extVal) {
    long absExtVal = Math.abs(extVal);
    long candidateResult = extremeResult.getLong();
    long absCandidateResult = Math.abs(extremeResult.getLong());

    if (!initResult
        || (absExtVal > absCandidateResult)
        || (absExtVal == absCandidateResult) && extVal > candidateResult) {
      initResult = true;
      extremeResult.setLong(extVal);
    }
  }

  private void addFloatInput(Column[] column, TimeRange timeRange) {
    TimeColumn timeColumn = (TimeColumn) column[0];
    for (int i = 0; i < timeColumn.getPositionCount(); i++) {
      long curTime = timeColumn.getLong(i);
      if (curTime > timeRange.getMax() || curTime < timeRange.getMin()) {
        break;
      }
      if (!column[1].isNull(i)) {
        updateFloatResult(column[1].getFloat(i));
      }
    }
  }

  private void updateFloatResult(float extVal) {
    float absExtVal = Math.abs(extVal);
    float candidateResult = extremeResult.getFloat();
    float absCandidateResult = Math.abs(extremeResult.getFloat());

    if (!initResult
        || (absExtVal > absCandidateResult)
        || (absExtVal == absCandidateResult) && extVal > candidateResult) {
      initResult = true;
      extremeResult.setFloat(extVal);
    }
  }

  private void addDoubleInput(Column[] column, TimeRange timeRange) {
    TimeColumn timeColumn = (TimeColumn) column[0];
    for (int i = 0; i < timeColumn.getPositionCount(); i++) {
      long curTime = timeColumn.getLong(i);
      if (curTime > timeRange.getMax() || curTime < timeRange.getMin()) {
        break;
      }
      if (!column[1].isNull(i)) {
        updateDoubleResult(column[1].getDouble(i));
      }
    }
  }

  private void updateDoubleResult(double extVal) {
    double absExtVal = Math.abs(extVal);
    double candidateResult = extremeResult.getDouble();
    double absCandidateResult = Math.abs(extremeResult.getDouble());

    if (!initResult
        || (absExtVal > absCandidateResult)
        || (absExtVal == absCandidateResult) && extVal > candidateResult) {
      initResult = true;
      extremeResult.setDouble(extVal);
    }
  }
}
