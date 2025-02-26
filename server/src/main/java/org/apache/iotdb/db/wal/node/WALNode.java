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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.wal.node;

import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.commons.file.SystemFileFactory;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.commons.utils.TestOnly;
import org.apache.iotdb.consensus.common.request.IConsensusRequest;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.engine.flush.FlushStatus;
import org.apache.iotdb.db.engine.memtable.IMemTable;
import org.apache.iotdb.db.engine.storagegroup.DataRegion;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertMultiTabletsNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertRowNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertRowsNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertRowsOfOneDeviceNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertTabletNode;
import org.apache.iotdb.db.qp.physical.crud.DeletePlan;
import org.apache.iotdb.db.qp.physical.crud.InsertRowPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.wal.buffer.IWALBuffer;
import org.apache.iotdb.db.wal.buffer.SignalWALEntry;
import org.apache.iotdb.db.wal.buffer.WALBuffer;
import org.apache.iotdb.db.wal.buffer.WALEntry;
import org.apache.iotdb.db.wal.buffer.WALEntryType;
import org.apache.iotdb.db.wal.checkpoint.CheckpointManager;
import org.apache.iotdb.db.wal.checkpoint.MemTableInfo;
import org.apache.iotdb.db.wal.io.WALReader;
import org.apache.iotdb.db.wal.utils.WALFileUtils;
import org.apache.iotdb.db.wal.utils.listener.WALFlushListener;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.utils.TsFileUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class encapsulates {@link IWALBuffer} and {@link CheckpointManager}. If search is enabled,
 * the order of search index should be protected by the upper layer, and the value should start from
 * 1.
 */
public class WALNode implements IWALNode {
  public static final long DEFAULT_SAFELY_DELETED_SEARCH_INDEX =
      InsertNode.DEFAULT_SAFELY_DELETED_SEARCH_INDEX;

  private static final Logger logger = LoggerFactory.getLogger(WALNode.class);
  private static final IoTDBConfig config = IoTDBDescriptor.getInstance().getConfig();

  /** unique identifier of this WALNode */
  private final String identifier;
  /** directory to store this node's files */
  private final File logDirectory;
  /** wal buffer */
  private final IWALBuffer buffer;
  /** manage checkpoints */
  private final CheckpointManager checkpointManager;
  /**
   * memTable id -> memTable snapshot count, used to avoid write amplification caused by frequent
   * snapshot
   */
  private final Map<Integer, Integer> memTableSnapshotCount = new ConcurrentHashMap<>();
  /**
   * total cost of flushedMemTables. when memControl enabled, cost is memTable ram cost, otherwise
   * cost is memTable count
   */
  private final AtomicLong totalCostOfFlushedMemTables = new AtomicLong();
  /** version id -> cost sum of memTables flushed at this file version */
  private final Map<Integer, Long> walFileVersionId2MemTablesTotalCost = new ConcurrentHashMap<>();
  /** insert nodes whose search index are before this value can be deleted safely */
  private volatile long safelyDeletedSearchIndex = DEFAULT_SAFELY_DELETED_SEARCH_INDEX;

  public WALNode(String identifier, String logDirectory) throws FileNotFoundException {
    this.identifier = identifier;
    this.logDirectory = SystemFileFactory.INSTANCE.getFile(logDirectory);
    if (!this.logDirectory.exists() && this.logDirectory.mkdirs()) {
      logger.info("create folder {} for wal node-{}.", logDirectory, identifier);
    }
    this.buffer = new WALBuffer(identifier, logDirectory);
    this.checkpointManager = new CheckpointManager(identifier, logDirectory);
  }

  @Override
  public WALFlushListener log(int memTableId, InsertRowPlan insertRowPlan) {
    WALEntry walEntry = new WALEntry(memTableId, insertRowPlan);
    return log(walEntry);
  }

  @Override
  public WALFlushListener log(int memTableId, InsertRowNode insertRowNode) {
    if (insertRowNode.getSafelyDeletedSearchIndex() != DEFAULT_SAFELY_DELETED_SEARCH_INDEX) {
      safelyDeletedSearchIndex = insertRowNode.getSafelyDeletedSearchIndex();
    }
    WALEntry walEntry = new WALEntry(memTableId, insertRowNode);
    return log(walEntry);
  }

  @Override
  public WALFlushListener log(
      int memTableId, InsertTabletPlan insertTabletPlan, int start, int end) {
    WALEntry walEntry = new WALEntry(memTableId, insertTabletPlan, start, end);
    return log(walEntry);
  }

  @Override
  public WALFlushListener log(
      int memTableId, InsertTabletNode insertTabletNode, int start, int end) {
    if (insertTabletNode.getSafelyDeletedSearchIndex() != DEFAULT_SAFELY_DELETED_SEARCH_INDEX) {
      safelyDeletedSearchIndex = insertTabletNode.getSafelyDeletedSearchIndex();
    }
    WALEntry walEntry = new WALEntry(memTableId, insertTabletNode, start, end);
    return log(walEntry);
  }

  @Override
  public WALFlushListener log(int memTableId, DeletePlan deletePlan) {
    WALEntry walEntry = new WALEntry(memTableId, deletePlan);
    return log(walEntry);
  }

  private WALFlushListener log(WALEntry walEntry) {
    buffer.write(walEntry);
    return walEntry.getWalFlushListener();
  }

  @Override
  public void onMemTableFlushStarted(IMemTable memTable) {
    // do nothing
  }

  @Override
  public void onMemTableFlushed(IMemTable memTable) {
    if (memTable.isSignalMemTable()) {
      return;
    }
    checkpointManager.makeFlushMemTableCP(memTable.getMemTableId());
    // remove snapshot info
    memTableSnapshotCount.remove(memTable.getMemTableId());
    // update cost info
    long cost = config.isEnableMemControl() ? memTable.getTVListsRamCost() : 1;
    int currentWALFileVersion = buffer.getCurrentWALFileVersion();
    walFileVersionId2MemTablesTotalCost.compute(
        currentWALFileVersion, (k, v) -> v == null ? cost : v + cost);
    totalCostOfFlushedMemTables.addAndGet(cost);
  }

  @Override
  public void onMemTableCreated(IMemTable memTable, String targetTsFile) {
    if (memTable.isSignalMemTable()) {
      return;
    }
    // use current log version id as first file version id
    int firstFileVersionId = buffer.getCurrentWALFileVersion();
    MemTableInfo memTableInfo = new MemTableInfo(memTable, targetTsFile, firstFileVersionId);
    checkpointManager.makeCreateMemTableCP(memTableInfo);
  }

  // region Task to delete outdated .wal files
  /** Delete outdated .wal files */
  public void deleteOutdatedFiles() {
    try {
      new DeleteOutdatedFileTask().run();
    } catch (Exception e) {
      logger.error("Fail to delete wal node-{}'s outdated files.", identifier, e);
    }
  }

  private class DeleteOutdatedFileTask implements Runnable {
    /** .wal files whose version ids are less than first valid version id should be deleted */
    private int firstValidVersionId;

    @Override
    public void run() {
      // init firstValidVersionId
      firstValidVersionId = checkpointManager.getFirstValidWALVersionId();
      if (firstValidVersionId == Integer.MIN_VALUE) {
        // roll wal log writer to delete current wal file
        rollWALFile();
        // update firstValidVersionId
        firstValidVersionId = checkpointManager.getFirstValidWALVersionId();
        if (firstValidVersionId == Integer.MIN_VALUE) {
          firstValidVersionId = buffer.getCurrentWALFileVersion();
        }
      }

      // delete outdated files
      deleteOutdatedFiles();

      // wal is used to search, cannot optimize files deletion
      if (safelyDeletedSearchIndex != DEFAULT_SAFELY_DELETED_SEARCH_INDEX) {
        return;
      }

      // calculate effective information ratio
      long costOfActiveMemTables = checkpointManager.getTotalCostOfActiveMemTables();
      long costOfFlushedMemTables = totalCostOfFlushedMemTables.get();
      double effectiveInfoRatio =
          (double) costOfActiveMemTables / (costOfActiveMemTables + costOfFlushedMemTables);
      logger.debug(
          "Effective information ratio is {}, active memTables cost is {}, flushed memTables cost is {}",
          effectiveInfoRatio,
          costOfActiveMemTables,
          costOfFlushedMemTables);
      // effective information ratio is too small
      // update first valid version id by snapshotting or flushing memTable,
      // then delete old .wal files again
      if (effectiveInfoRatio < config.getWalMinEffectiveInfoRatio()) {
        logger.info(
            "Effective information ratio {} of wal node-{} is below wal min effective info ratio {}, some mamTables will be snapshot or flushed.",
            effectiveInfoRatio,
            identifier,
            config.getWalMinEffectiveInfoRatio());
        snapshotOrFlushMemTable();
        run();
      }
    }

    private void deleteOutdatedFiles() {
      File[] filesToDelete = logDirectory.listFiles(this::filterFilesToDelete);
      if (filesToDelete != null) {
        for (File file : filesToDelete) {
          if (!file.delete()) {
            logger.info("Fail to delete outdated wal file {} of wal node-{}.", file, identifier);
          }
          // update totalRamCostOfFlushedMemTables
          int versionId = WALFileUtils.parseVersionId(file.getName());
          Long memTableRamCostSum = walFileVersionId2MemTablesTotalCost.remove(versionId);
          if (memTableRamCostSum != null) {
            totalCostOfFlushedMemTables.addAndGet(-memTableRamCostSum);
          }
        }
      }
    }

    private boolean filterFilesToDelete(File dir, String name) {
      Pattern pattern = WALFileUtils.WAL_FILE_NAME_PATTERN;
      Matcher matcher = pattern.matcher(name);
      boolean toDelete = false;
      if (matcher.find()) {
        int versionId = Integer.parseInt(matcher.group(IoTDBConstant.WAL_VERSION_ID));
        long startSearchIndex = Long.parseLong(matcher.group(IoTDBConstant.WAL_START_SEARCH_INDEX));
        toDelete = versionId < firstValidVersionId && startSearchIndex < safelyDeletedSearchIndex;
      }
      return toDelete;
    }

    private void snapshotOrFlushMemTable() {
      // find oldest memTable
      MemTableInfo oldestMemTableInfo = checkpointManager.getOldestMemTableInfo();
      if (oldestMemTableInfo == null) {
        return;
      }
      IMemTable oldestMemTable = oldestMemTableInfo.getMemTable();

      // get memTable's virtual storage group processor
      File oldestTsFile =
          FSFactoryProducer.getFSFactory().getFile(oldestMemTableInfo.getTsFilePath());
      DataRegion dataRegion;
      try {
        dataRegion =
            StorageEngine.getInstance()
                .getProcessorByDataRegionId(
                    new PartialPath(TsFileUtils.getStorageGroup(oldestTsFile)),
                    TsFileUtils.getDataRegionId(oldestTsFile));
      } catch (IllegalPathException | StorageEngineException e) {
        logger.error("Fail to get virtual storage group processor for {}", oldestTsFile, e);
        return;
      }

      // snapshot or flush memTable
      int snapshotCount = memTableSnapshotCount.getOrDefault(oldestMemTable.getMemTableId(), 0);
      if (snapshotCount >= config.getMaxWalMemTableSnapshotNum()
          || oldestMemTable.getTVListsRamCost() > config.getWalMemTableSnapshotThreshold()) {
        flushMemTable(dataRegion, oldestTsFile, oldestMemTable);
      } else {
        snapshotMemTable(dataRegion, oldestTsFile, oldestMemTableInfo);
      }
    }

    private void flushMemTable(DataRegion dataRegion, File tsFile, IMemTable memTable) {
      boolean shouldWait = true;
      if (memTable.getFlushStatus() == FlushStatus.WORKING) {
        shouldWait =
            dataRegion.submitAFlushTask(
                TsFileUtils.getTimePartition(tsFile), TsFileUtils.isSequence(tsFile));
        logger.info(
            "WAL node-{} flushes memTable-{} to TsFile {}, memTable size is {}.",
            identifier,
            memTable.getMemTableId(),
            tsFile,
            memTable.getTVListsRamCost());
      }

      // it's fine to wait until memTable has been flushed, because deleting files is not urgent.
      if (shouldWait) {
        long sleepTime = 0;
        while (memTable.getFlushStatus() != FlushStatus.FLUSHED) {
          try {
            Thread.sleep(1_000);
            sleepTime += 1_000;
            if (sleepTime > 10_000) {
              logger.warn("Waiting too long for memTable flush to be done.");
              break;
            }
          } catch (InterruptedException e) {
            logger.warn("Interrupted when waiting for memTable flush to be done.");
            Thread.currentThread().interrupt();
          }
        }
      }
    }

    private void snapshotMemTable(DataRegion dataRegion, File tsFile, MemTableInfo memTableInfo) {
      IMemTable memTable = memTableInfo.getMemTable();
      if (memTable.getFlushStatus() != FlushStatus.WORKING) {
        return;
      }

      // update snapshot count
      memTableSnapshotCount.compute(memTable.getMemTableId(), (k, v) -> v == null ? 1 : v + 1);
      // roll wal log writer to make sure first version id will be updated
      WALEntry rollWALFileSignal =
          new SignalWALEntry(SignalWALEntry.SignalType.ROLL_WAL_LOG_WRITER_SIGNAL, true);
      WALFlushListener fileRolledListener = log(rollWALFileSignal);
      if (fileRolledListener.waitForResult() == WALFlushListener.Status.FAILURE) {
        logger.error("Fail to roll wal log writer.", fileRolledListener.getCause());
        return;
      }

      // update first version id first to make sure snapshot is in the files ≥ current log
      // version
      memTableInfo.setFirstFileVersionId(buffer.getCurrentWALFileVersion());

      // get dataRegion write lock to make sure no more writes to the memTable
      dataRegion.writeLock(
          "CheckpointManager$DeleteOutdatedFileTask.snapshotOrFlushOldestMemTable");
      try {
        // log snapshot in a new .wal file
        WALEntry walEntry = new WALEntry(memTable.getMemTableId(), memTable, true);
        WALFlushListener flushListener = log(walEntry);

        // wait until getting the result
        // it's low-risk to block writes awhile because this memTable accumulates slowly
        if (flushListener.waitForResult() == WALFlushListener.Status.FAILURE) {
          logger.error("Fail to snapshot memTable of {}", tsFile, flushListener.getCause());
        }
        logger.info(
            "WAL node-{} snapshots memTable-{} to wal files, memTable size is {}.",
            identifier,
            memTable.getMemTableId(),
            memTable.getTVListsRamCost());
      } finally {
        dataRegion.writeUnlock();
      }
    }
  }
  // endregion

  // region Search interfaces for consensus group
  public void setSafelyDeletedSearchIndex(long safelyDeletedSearchIndex) {
    this.safelyDeletedSearchIndex = safelyDeletedSearchIndex;
  }

  /**
   * Merge insert nodes sharing same search index ( e.g. tablet-100, tablet-100, tablet-100 will be
   * merged to one multi-tablet). <br>
   * Notice: the continuity of insert nodes sharing same search index should be protected by the
   * upper layer.
   */
  private static InsertNode mergeInsertNodes(List<InsertNode> insertNodes) {
    int size = insertNodes.size();
    if (size == 0) {
      return null;
    }
    if (size == 1) {
      return insertNodes.get(0);
    }

    InsertNode result;
    if (insertNodes.get(0) instanceof InsertTabletNode) { // merge to InsertMultiTabletsNode
      List<Integer> index = new ArrayList<>(size);
      List<InsertTabletNode> insertTabletNodes = new ArrayList<>(size);
      int i = 0;
      for (InsertNode insertNode : insertNodes) {
        insertTabletNodes.add((InsertTabletNode) insertNode);
        index.add(i);
        i++;
      }
      result = new InsertMultiTabletsNode(new PlanNodeId(""), index, insertTabletNodes);
    } else { // merge to InsertRowsNode or InsertRowsOfOneDeviceNode
      boolean sameDevice = true;
      PartialPath device = insertNodes.get(0).getDevicePath();
      List<Integer> index = new ArrayList<>(size);
      List<InsertRowNode> insertRowNodes = new ArrayList<>(size);
      int i = 0;
      for (InsertNode insertNode : insertNodes) {
        if (sameDevice && !insertNode.getDevicePath().equals(device)) {
          sameDevice = false;
        }
        insertRowNodes.add((InsertRowNode) insertNode);
        index.add(i);
        i++;
      }
      result =
          sameDevice
              ? new InsertRowsOfOneDeviceNode(new PlanNodeId(""), index, insertRowNodes)
              : new InsertRowsNode(new PlanNodeId(""), index, insertRowNodes);
    }
    result.setSearchIndex(insertNodes.get(0).getSearchIndex());

    return result;
  }

  @Override
  public IConsensusRequest getReq(long index) {
    // find file
    File[] currentFiles = WALFileUtils.listAllWALFiles(logDirectory);
    WALFileUtils.ascSortByVersionId(currentFiles);
    int fileIndex = WALFileUtils.binarySearchFileBySearchIndex(currentFiles, index);
    if (fileIndex < 0) {
      return null;
    }
    // find log
    List<InsertNode> tmpNodes = new ArrayList<>();
    for (int i = fileIndex; i < currentFiles.length; i++) {
      // cannot find anymore
      if (index < WALFileUtils.parseStartSearchIndex(currentFiles[i].getName())) {
        if (!tmpNodes.isEmpty()) {
          return mergeInsertNodes(tmpNodes);
        } else {
          break;
        }
      }

      try (WALReader walReader = new WALReader(currentFiles[i])) {
        while (walReader.hasNext()) {
          WALEntry walEntry = walReader.next();
          if (walEntry.getType() == WALEntryType.INSERT_TABLET_NODE
              || walEntry.getType() == WALEntryType.INSERT_ROW_NODE) {
            InsertNode insertNode = (InsertNode) walEntry.getValue();
            if (insertNode.getSearchIndex() == index) {
              tmpNodes.add(insertNode);
            } else if (!tmpNodes.isEmpty()) { // find all slices of insert plan
              return mergeInsertNodes(tmpNodes);
            }
          } else if (!tmpNodes.isEmpty()) { // find all slices of insert plan
            return mergeInsertNodes(tmpNodes);
          }
        }
      } catch (Exception e) {
        logger.error("Fail to read wal from wal file {}", currentFiles[i], e);
      }
    }
    // not find or not complete
    return null;
  }

  @Override
  public List<IConsensusRequest> getReqs(long startIndex, int num) {
    List<IConsensusRequest> result = new ArrayList<>(num);
    // find file
    File[] currentFiles = WALFileUtils.listAllWALFiles(logDirectory);
    WALFileUtils.ascSortByVersionId(currentFiles);
    int fileIndex = WALFileUtils.binarySearchFileBySearchIndex(currentFiles, startIndex);
    if (fileIndex < 0) {
      return result;
    }
    // find logs
    long endIndex = startIndex + num - 1;
    long targetIndex = startIndex;
    List<InsertNode> tmpNodes = new ArrayList<>();
    for (int i = fileIndex; i < currentFiles.length; i++) {
      // cannot find anymore
      if (endIndex < WALFileUtils.parseStartSearchIndex(currentFiles[i].getName())) {
        if (!tmpNodes.isEmpty()) {
          result.add(mergeInsertNodes(tmpNodes));
        } else {
          break;
        }
      }

      try (WALReader walReader = new WALReader(currentFiles[i])) {
        while (walReader.hasNext()) {
          WALEntry walEntry = walReader.next();
          if (walEntry.getType() == WALEntryType.INSERT_TABLET_NODE
              || walEntry.getType() == WALEntryType.INSERT_ROW_NODE) {
            InsertNode insertNode = (InsertNode) walEntry.getValue();
            if (insertNode.getSearchIndex() == targetIndex) {
              tmpNodes.add(insertNode);
            } else if (!tmpNodes.isEmpty()) { // find all slices of insert plan
              result.add(mergeInsertNodes(tmpNodes));
              if (result.size() == num) {
                return result;
              }
              targetIndex++;
              tmpNodes = new ArrayList<>();
              // remember to add current insert node
              if (insertNode.getSearchIndex() == targetIndex) {
                tmpNodes.add(insertNode);
              }
            }
          } else if (!tmpNodes.isEmpty()) { // find all slices of insert plan
            result.add(mergeInsertNodes(tmpNodes));
            if (result.size() == num) {
              return result;
            }
            targetIndex++;
            tmpNodes = new ArrayList<>();
          }
        }
      } catch (Exception e) {
        logger.error("Fail to read wal from wal file {}", currentFiles[i], e);
      }
    }

    return result;
  }

  /** This iterator is not concurrency-safe */
  @Override
  public ReqIterator getReqIterator(long startIndex) {
    return new PlanNodeIterator(startIndex);
  }

  private class PlanNodeIterator implements ReqIterator {
    /** search index of next element */
    private long nextSearchIndex;
    /** files to search */
    private File[] filesToSearch = null;
    /** index of current searching file in the filesToSearch */
    private int currentFileIndex = -1;
    /** true means filesToSearch and currentFileIndex are outdated, call updateFilesToSearch */
    private boolean needUpdatingFilesToSearch = true;
    /**
     * files whose version id before this value have already been searched, avoid storing too many
     * files in filesToSearch
     */
    private int searchedFilesVersionId = 0;
    /** batch store insert nodes */
    private final List<InsertNode> insertNodes = new LinkedList<>();
    /** iterator of insertNodes */
    private Iterator<InsertNode> itr = null;

    public PlanNodeIterator(long startIndex) {
      this.nextSearchIndex = startIndex;
    }

    @Override
    public boolean hasNext() {
      if (itr != null && itr.hasNext()) {
        return true;
      }

      insertNodes.clear();
      itr = null;

      if (needUpdatingFilesToSearch || filesToSearch == null) {
        updateFilesToSearch();
        if (needUpdatingFilesToSearch) {
          return false;
        }
      }

      // find all insert plan of current wal file
      List<InsertNode> tmpNodes = new ArrayList<>();
      long targetIndex = nextSearchIndex;
      try (WALReader walReader = new WALReader(filesToSearch[currentFileIndex])) {
        while (walReader.hasNext()) {
          WALEntry walEntry = walReader.next();
          if (walEntry.getType() == WALEntryType.INSERT_TABLET_NODE
              || walEntry.getType() == WALEntryType.INSERT_ROW_NODE) {
            InsertNode insertNode = (InsertNode) walEntry.getValue();
            if (insertNode.getSearchIndex() == targetIndex) {
              tmpNodes.add(insertNode);
            } else if (!tmpNodes.isEmpty()) { // find all slices of insert plan
              insertNodes.add(mergeInsertNodes(tmpNodes));
              targetIndex++;
              tmpNodes = new ArrayList<>();
              // remember to add current insert node
              if (insertNode.getSearchIndex() == targetIndex) {
                tmpNodes.add(insertNode);
              }
            }
          } else if (!tmpNodes.isEmpty()) { // find all slices of insert plan
            insertNodes.add(mergeInsertNodes(tmpNodes));
            targetIndex++;
            tmpNodes = new ArrayList<>();
          }
        }
      } catch (Exception e) {
        logger.error("Fail to read wal from wal file {}", filesToSearch[currentFileIndex], e);
      }

      // find remaining slices of last insert plan of targetIndex
      if (tmpNodes.isEmpty()) { // all insert plans scanned
        currentFileIndex++;
      } else {
        int fileIndex = currentFileIndex + 1;
        while (!tmpNodes.isEmpty() && fileIndex < filesToSearch.length) {
          try (WALReader walReader = new WALReader(filesToSearch[fileIndex])) {
            while (walReader.hasNext()) {
              WALEntry walEntry = walReader.next();
              if (walEntry.getType() == WALEntryType.INSERT_TABLET_NODE
                  || walEntry.getType() == WALEntryType.INSERT_ROW_NODE) {
                InsertNode insertNode = (InsertNode) walEntry.getValue();
                if (insertNode.getSearchIndex() == targetIndex) {
                  tmpNodes.add(insertNode);
                } else if (!tmpNodes.isEmpty()) { // find all slices of insert plan
                  insertNodes.add(mergeInsertNodes(tmpNodes));
                  tmpNodes = Collections.emptyList();
                  break;
                }
              } else if (!tmpNodes.isEmpty()) { // find all slices of insert plan
                insertNodes.add(mergeInsertNodes(tmpNodes));
                tmpNodes = Collections.emptyList();
                break;
              }
            }
          } catch (Exception e) {
            logger.error("Fail to read wal from wal file {}", filesToSearch[currentFileIndex], e);
          }
          if (!tmpNodes.isEmpty()) {
            fileIndex++;
          }
        }

        if (tmpNodes.isEmpty()) { // all insert plans scanned
          currentFileIndex = fileIndex;
        } else {
          needUpdatingFilesToSearch = true;
        }
      }

      // update file index and version id
      if (currentFileIndex >= filesToSearch.length) {
        needUpdatingFilesToSearch = true;
      } else {
        searchedFilesVersionId =
            WALFileUtils.parseVersionId(filesToSearch[currentFileIndex].getName());
      }

      // update iterator
      if (insertNodes.size() != 0) {
        itr = insertNodes.iterator();
        return true;
      }
      return false;
    }

    @Override
    public IConsensusRequest next() {
      if (itr == null && !hasNext()) {
        throw new NoSuchElementException();
      }

      InsertNode insertNode = itr.next();
      if (insertNode.getSearchIndex() != nextSearchIndex) {
        logger.warn(
            "Search index of wal node-{} are not continuously, skip from {} to {}.",
            identifier,
            nextSearchIndex,
            insertNode.getSearchIndex());
      }
      nextSearchIndex = insertNode.getSearchIndex() + 1;

      return insertNode;
    }

    @Override
    public void waitForNextReady() throws InterruptedException {
      while (!hasNext()) {
        buffer.waitForFlush();
      }
    }

    @Override
    public void waitForNextReady(long time, TimeUnit unit)
        throws InterruptedException, TimeoutException {
      if (!hasNext()) {
        boolean timeout = !buffer.waitForFlush(time, unit);
        if (timeout || !hasNext()) {
          throw new TimeoutException();
        }
      }
    }

    @Override
    public void skipTo(long targetIndex) {
      if (targetIndex < nextSearchIndex) {
        logger.warn(
            "Skip from {} to {}, it's a dangerous operation because insert plan {} may have been lost.",
            nextSearchIndex,
            targetIndex,
            targetIndex);
        searchedFilesVersionId = -1;
        insertNodes.clear();
        itr = null;
      }
      nextSearchIndex = targetIndex;
      this.filesToSearch = null;
      this.currentFileIndex = -1;
      needUpdatingFilesToSearch = true;
    }

    private void updateFilesToSearch() {
      File[] filesToSearch = logDirectory.listFiles(this::filterFilesToSearch);
      WALFileUtils.ascSortByVersionId(filesToSearch);
      int fileIndex = WALFileUtils.binarySearchFileBySearchIndex(filesToSearch, nextSearchIndex);
      if (filesToSearch != null && fileIndex >= 0) { // possible to find next
        this.filesToSearch = filesToSearch;
        this.currentFileIndex = fileIndex;
        this.searchedFilesVersionId =
            WALFileUtils.parseVersionId(this.filesToSearch[currentFileIndex].getName());
        this.needUpdatingFilesToSearch = false;
      } else { // impossible to find next
        this.filesToSearch = null;
        this.currentFileIndex = -1;
        this.needUpdatingFilesToSearch = true;
      }
    }

    private boolean filterFilesToSearch(File dir, String name) {
      Pattern pattern = WALFileUtils.WAL_FILE_NAME_PATTERN;
      Matcher matcher = pattern.matcher(name);
      boolean toSearch = false;
      if (matcher.find()) {
        int versionId = Integer.parseInt(matcher.group(IoTDBConstant.WAL_VERSION_ID));
        toSearch = versionId >= searchedFilesVersionId;
      }
      return toSearch;
    }
  }
  // endregion

  @Override
  public void close() {
    buffer.close();
    checkpointManager.close();
  }

  @TestOnly
  boolean isAllWALEntriesConsumed() {
    return buffer.isAllWALEntriesConsumed();
  }

  @TestOnly
  int getCurrentLogVersion() {
    return buffer.getCurrentWALFileVersion();
  }

  @TestOnly
  public void rollWALFile() {
    WALEntry rollWALFileSignal =
        new SignalWALEntry(SignalWALEntry.SignalType.ROLL_WAL_LOG_WRITER_SIGNAL, true);
    WALFlushListener walFlushListener = log(rollWALFileSignal);
    if (walFlushListener.waitForResult() == WALFlushListener.Status.FAILURE) {
      logger.error(
          "Fail to trigger rolling wal node-{}'s wal file log writer.",
          identifier,
          walFlushListener.getCause());
    }
  }
}
