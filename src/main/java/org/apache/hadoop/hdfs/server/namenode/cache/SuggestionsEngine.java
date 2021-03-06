/**
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

package org.apache.hadoop.hdfs.server.namenode.cache;

import com.google.common.collect.Sets;
import com.paypal.namenode.HSQLDriver;
import com.paypal.security.SecurityConfiguration;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.NNLoader;
import org.apache.hadoop.hdfs.server.namenode.QueryEngine;
import org.apache.hadoop.hdfs.server.namenode.queries.Histograms;
import org.apache.hadoop.util.VirtualINodeTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is class handles all end-user cached reports that the suggestions UI page uses. All
 * information stored here is present to "CACHE" priviledged users.
 *
 * <p>The goal of this class is to provide in-depth analysis for users and selected directories that
 * is stored in MapDB mmap'd file caches.
 *
 * <p>The main logic for this class is in the reloadSuggestions() method call which does a large
 * analysis and finally updates cache stores.
 */
public class SuggestionsEngine {

  public static final Logger LOG = LoggerFactory.getLogger(SuggestionsEngine.class.getName());

  private final CacheManager cacheManager;

  private Map<String, Long> cachedValues;
  private Map<String, Map<String, Long>> cachedMaps;
  private Map<String, Long> cachedLogins;
  private Set<String> cachedUsers;
  private Set<String> cachedDirs;
  private Map<String, Map<String, Long>> cachedUserNsQuotas;
  private Map<String, Map<String, Long>> cachedUserDsQuotas;

  private AtomicBoolean loaded;

  public SuggestionsEngine() {
    this.cacheManager = new CacheManager();
    this.loaded = new AtomicBoolean(false);
  }

  public boolean isLoaded() {
    return loaded.get();
  }

  private Map<String, Long> getCachedMap(String innerMapName) {
    return cachedMaps.getOrDefault(innerMapName, Collections.emptyMap());
  }

  /**
   * This method should only be called after NNLoader has finished loading the FSImage.
   *
   * <p>Calling this method will issue many queries in the background and update the various MapDB
   * cached objects.
   *
   * @param nnLoader The main NNLoader and in-memory metadata set.
   */
  public void reloadSuggestions(NNLoader nnLoader) {
    long s1 = System.currentTimeMillis();
    Collection<INode> files = nnLoader.getINodeSet("files");
    Collection<INode> dirs = nnLoader.getINodeSet("dirs");

    long numFiles = files.size();
    long numDirs = dirs.size();
    long capacity = 0L;

    try {
      FileSystem fs = nnLoader.getFileSystem();
      capacity = fs.getStatus().getCapacity();
    } catch (IOException e) {
      e.printStackTrace();
    }

    QueryEngine queryEngine = nnLoader.getQueryEngine();
    final Map<String, Long> modTimeCount =
        queryEngine.modTimeHistogram(files, "count", null, "monthly");
    final Map<String, Long> modTimeDiskspace =
        queryEngine.modTimeHistogram(files, "diskspaceConsumed", null, "monthly");

    final Set<String> fileUsers =
        files.parallelStream().map(INode::getUserName).distinct().collect(Collectors.toSet());
    final Set<String> dirUsers =
        dirs.parallelStream().map(INode::getUserName).distinct().collect(Collectors.toSet());
    final Set<String> users = Sets.union(fileUsers, dirUsers);

    final long diskspace = queryEngine.sum(files, "diskspaceConsumed");
    final Collection<INode> files24h =
        queryEngine.combinedFilter(files, new String[] {"modTime"}, new String[] {"hoursAgo:24"});
    final long numFiles24h = files24h.size();
    final long diskspace24h = queryEngine.sum(files24h, "diskspaceConsumed");
    final Map<String, Long> numFiles24hUsers = queryEngine.byUserHistogramCpu(files24h, "count");
    final Map<String, Long> diskspace24hUsers =
        queryEngine.byUserHistogramCpu(files24h, "diskspaceConsumed");
    final Map<String, Long> diskspaceUsers =
        queryEngine.byUserHistogramCpu(files, "diskspaceConsumed");

    final Collection<INode> oldFiles1yr =
        queryEngine.combinedFilter(
            files, new String[] {"accessTime"}, new String[] {"olderThanYears:1"});
    final Map<String, Long> oldFiles1yrCountUsers =
        queryEngine.byUserHistogramCpu(oldFiles1yr, "count");
    final Map<String, Long> oldFiles1yrDsUsers =
        queryEngine.byUserHistogramCpu(oldFiles1yr, "diskspaceConsumed");
    final Collection<INode> oldFiles2yr =
        queryEngine.combinedFilter(
            files, new String[] {"accessTime"}, new String[] {"olderThanYears:2"});
    final Map<String, Long> oldFiles2yrCountUsers =
        queryEngine.byUserHistogramCpu(oldFiles2yr, "count");
    final Map<String, Long> oldFiles2yrDsUsers =
        queryEngine.byUserHistogramCpu(oldFiles2yr, "diskspaceConsumed");

    final Collection<INode> emptyFiles =
        queryEngine.combinedFilter(files, new String[] {"fileSize"}, new String[] {"eq:0"});
    final Collection<INode> emptyDirs =
        queryEngine.combinedFilter(dirs, new String[] {"dirNumChildren"}, new String[] {"eq:0"});
    final Collection<INode> tinyFiles =
        queryEngine.combinedFilter(
            files, new String[] {"fileSize", "fileSize"}, new String[] {"lte:1024", "gt:0"});
    final Collection<INode> smallFiles =
        queryEngine.combinedFilter(
            files, new String[] {"fileSize", "fileSize"}, new String[] {"lte:1048576", "gt:1024"});
    final Collection<INode> mediumFiles =
        queryEngine.combinedFilter(
            files,
            new String[] {"fileSize", "fileSize"},
            new String[] {"lte:134217728", "gt:1048576"});

    final Collection<INode> emptyFiles24h =
        queryEngine.combinedFilter(
            emptyFiles, new String[] {"modTime"}, new String[] {"hoursAgo:24"});
    final Collection<INode> emptyDirs24h =
        queryEngine.combinedFilter(
            emptyDirs, new String[] {"modTime"}, new String[] {"hoursAgo:24"});
    final Collection<INode> tinyFiles24h =
        queryEngine.combinedFilter(
            tinyFiles, new String[] {"modTime"}, new String[] {"hoursAgo:24"});
    final Collection<INode> smallFiles24h =
        queryEngine.combinedFilter(
            smallFiles, new String[] {"modTime"}, new String[] {"hoursAgo:24"});

    final Collection<INode> emptyFiles1yr =
        queryEngine.combinedFilter(
            emptyFiles, new String[] {"accessTime"}, new String[] {"olderThanYears:1"});
    final Collection<INode> emptyDirs1yr =
        queryEngine.combinedFilter(
            emptyDirs, new String[] {"modTime"}, new String[] {"olderThanYears:1"});
    final Collection<INode> tinyFiles1yr =
        queryEngine.combinedFilter(
            tinyFiles, new String[] {"accessTime"}, new String[] {"olderThanYears:1"});
    final Collection<INode> smallFiles1yr =
        queryEngine.combinedFilter(
            smallFiles, new String[] {"accessTime"}, new String[] {"olderThanYears:1"});

    final long emptyFilesCount = emptyFiles.size();
    final long emptyDirsCount = emptyDirs.size();
    final long emptyFilesMem = queryEngine.sum(emptyFiles, "memoryConsumed");
    final long emptyDirsMem = queryEngine.sum(emptyDirs, "memoryConsumed");
    final long tinyFilesCount = tinyFiles.size();
    final long smallFilesCount = smallFiles.size();
    final long mediumFilesCount = mediumFiles.size();
    final long largeFilesCount =
        numFiles - emptyFilesCount - tinyFilesCount - smallFilesCount - mediumFilesCount;
    final long tinyFilesMem = queryEngine.sum(tinyFiles, "memoryConsumed");
    final long smallFilesMem = queryEngine.sum(smallFiles, "memoryConsumed");
    final long tinyFilesDs = queryEngine.sum(tinyFiles, "diskspaceConsumed");
    final long smallFilesDs = queryEngine.sum(smallFiles, "diskspaceConsumed");

    final long emptyFiles24hCount = emptyFiles24h.size();
    final long emptyDirs24hCount = emptyDirs24h.size();
    final long emptyFiles24hMem = queryEngine.sum(emptyFiles24h, "memoryConsumed");
    final long emptyDirs24hMem = queryEngine.sum(emptyDirs24h, "memoryConsumed");
    final long tinyFiles24hCount = tinyFiles24h.size();
    final long smallFiles24hCount = smallFiles24h.size();
    final long tinyFiles24hMem = queryEngine.sum(tinyFiles24h, "memoryConsumed");
    final long smallFiles24hMem = queryEngine.sum(smallFiles24h, "memoryConsumed");
    final long tinyFiles24hDs = queryEngine.sum(tinyFiles24h, "diskspaceConsumed");
    final long smallFiles24hDs = queryEngine.sum(smallFiles24h, "diskspaceConsumed");

    final long emptyFiles1yrCount = emptyFiles1yr.size();
    final long emptyDirs1yrCount = emptyDirs1yr.size();
    final long tinyFiles1yrCount = tinyFiles1yr.size();
    final long smallFiles1yrCount = smallFiles1yr.size();

    final long oldFiles1yrCount = oldFiles1yr.size();
    final long oldFiles2yrCount = oldFiles2yr.size();
    final long oldFiles1yrDs = queryEngine.sum(oldFiles1yr, "diskspaceConsumed");
    final long oldFiles2yrDs = queryEngine.sum(oldFiles2yr, "diskspaceConsumed");

    final Map<String, Long> filesUsers = queryEngine.byUserHistogram(files, "count", null);
    final Map<String, Long> dirsUsers = queryEngine.byUserHistogramCpu(dirs, "count");

    final Map<String, Long> emptyFilesUsers = queryEngine.byUserHistogramCpu(emptyFiles, "count");
    final Map<String, Long> emptyDirsUsers = queryEngine.byUserHistogramCpu(emptyDirs, "count");
    final Map<String, Long> tinyFilesUsers = queryEngine.byUserHistogramCpu(tinyFiles, "count");
    final Map<String, Long> smallFilesUsers = queryEngine.byUserHistogramCpu(smallFiles, "count");
    final Map<String, Long> mediumFilesUsers = queryEngine.byUserHistogramCpu(mediumFiles, "count");
    final Map<String, Long> largeFilesUsers = new HashMap<>(users.size());
    users.forEach(
        u -> {
          long largeFiles =
              filesUsers.getOrDefault(u, 0L)
                  - emptyFilesUsers.getOrDefault(u, 0L)
                  - tinyFilesUsers.getOrDefault(u, 0L)
                  - smallFilesUsers.getOrDefault(u, 0L)
                  - mediumFilesUsers.getOrDefault(u, 0L);
          largeFilesUsers.put(u, largeFiles);
        });

    final Map<String, Long> emptyFiles24hUsers =
        queryEngine.byUserHistogramCpu(emptyFiles24h, "count");
    final Map<String, Long> emptyDirs24hUsers =
        queryEngine.byUserHistogramCpu(emptyDirs24h, "count");
    final Map<String, Long> tinyFiles24hUsers =
        queryEngine.byUserHistogramCpu(tinyFiles24h, "count");
    final Map<String, Long> smallFiles24hUsers =
        queryEngine.byUserHistogramCpu(smallFiles24h, "count");
    final Map<String, Long> emptyFiles1yrUsers =
        queryEngine.byUserHistogramCpu(emptyFiles1yr, "count");
    final Map<String, Long> emptyDirs1yrUsers =
        queryEngine.byUserHistogramCpu(emptyDirs1yr, "count");
    final Map<String, Long> tinyFiles1yrUsers =
        queryEngine.byUserHistogramCpu(tinyFiles1yr, "count");
    final Map<String, Long> smallFiles1yrUsers =
        queryEngine.byUserHistogramCpu(smallFiles1yr, "count");
    final Map<String, Long> emptyFilesMemUsers =
        queryEngine.byUserHistogramCpu(emptyFiles, "memoryConsumed");
    final Map<String, Long> emptyDirsMemUsers =
        queryEngine.byUserHistogramCpu(emptyDirs, "memoryConsumed");
    final Map<String, Long> tinyFilesMemUsers =
        queryEngine.byUserHistogramCpu(tinyFiles, "memoryConsumed");
    final Map<String, Long> smallFilesMemUsers =
        queryEngine.byUserHistogramCpu(smallFiles, "memoryConsumed");
    final Map<String, Long> tinyFilesDsUsers =
        queryEngine.byUserHistogramCpu(tinyFiles, "diskspaceConsumed");
    final Map<String, Long> smallFilesDsUsers =
        queryEngine.byUserHistogramCpu(smallFiles, "diskspaceConsumed");
    final Map<String, Long> emptyFiles24hMemUsers =
        queryEngine.byUserHistogramCpu(emptyFiles24h, "memoryConsumed");
    final Map<String, Long> emptyDirs24hMemUsers =
        queryEngine.byUserHistogramCpu(emptyDirs24h, "memoryConsumed");
    final Map<String, Long> tinyFiles24hMemUsers =
        queryEngine.byUserHistogramCpu(tinyFiles24h, "memoryConsumed");
    final Map<String, Long> smallFiles24hMemUsers =
        queryEngine.byUserHistogramCpu(smallFiles24h, "memoryConsumed");
    final Map<String, Long> tinyFiles24hDsUsers =
        queryEngine.byUserHistogramCpu(tinyFiles24h, "diskspaceConsumed");
    final Map<String, Long> smallFiles24hDsUsers =
        queryEngine.byUserHistogramCpu(smallFiles24h, "diskspaceConsumed");

    Map<String, Long> dirCount = queryEngine.parentDirHistogramCpu(files, 3, "count");
    Map<String, Long> dirDs = queryEngine.parentDirHistogramCpu(files, 3, "diskspaceConsumed");
    dirCount = Histograms.sliceToTop(dirCount, 1000);
    dirDs = Histograms.sliceToTop(dirDs, 1000);

    VirtualINodeTree tree = new VirtualINodeTree();
    cachedDirs.forEach(tree::addElement);
    List<String> commonRoots = tree.getCommonAncestorsAsStrings();

    for (String commonRoot : commonRoots) {
      Collection<INode> commonINodes =
          queryEngine.combinedFilter(
              files, new String[] {"path"}, new String[] {"startsWith:" + commonRoot});

      for (String cachedDir : cachedDirs) {
        if (!cachedDir.startsWith(commonRoot)) {
          continue;
        }
        Collection<INode> inodes;
        if (cachedDir.equals(commonRoot)) {
          inodes = commonINodes;
        } else {
          inodes =
              queryEngine.combinedFilter(
                  commonINodes, new String[] {"path"}, new String[] {"startsWith:" + cachedDir});
        }
        long count = inodes.size();
        long diskspaceConsumed = queryEngine.sum(inodes, "diskspaceConsumed");
        dirCount.put(cachedDir, count);
        dirDs.put(cachedDir, diskspaceConsumed);
      }
    }

    Map<String, Long> dirCount24h = queryEngine.parentDirHistogramCpu(files24h, 3, "count");
    dirCount24h = Histograms.sliceToTop(dirCount24h, 1000);
    Map<String, Long> dirDs24h =
        queryEngine.parentDirHistogramCpu(files24h, 3, "diskspaceConsumed");
    dirDs24h = Histograms.sliceToTop(dirDs24h, 1000);
    for (String dir : cachedDirs) {
      Collection<INode> inodes =
          queryEngine.combinedFilter(
              files24h, new String[] {"path"}, new String[] {"startsWith:" + dir});
      long count = inodes.size();
      long diskspaceConsumed = queryEngine.sum(inodes, "diskspaceConsumed");
      dirCount24h.put(dir, count);
      dirDs24h.put(dir, diskspaceConsumed);
    }

    long nsQuotaCount = 0;
    long dsQuotaCount = 0;
    long nsQuotaThreshCount = 0;
    long dsQuotaThreshCount = 0;
    final Map<String, Long> nsQuotaThreshCountsUsers = new HashMap<>();
    final Map<String, Long> dsQuotaThreshCountsUsers = new HashMap<>();
    final Map<String, Long> nsQuotaCountsUsers = new HashMap<>();
    final Map<String, Long> dsQuotaCountsUsers = new HashMap<>();
    for (String user : users) {
      Collection<INode> quotaDirs =
          queryEngine.combinedFilter(
              dirs, new String[] {"user", "hasQuota"}, new String[] {"eq:" + user, "eq:true"});
      Map<String, Long> nsQuotaRatio =
          queryEngine.dirQuotaHistogramCpu(quotaDirs, "nsQuotaRatioUsed");
      Map<String, Long> dsQuotaRatio =
          queryEngine.dirQuotaHistogramCpu(quotaDirs, "dsQuotaRatioUsed");
      long nsThreshExceeded = nsQuotaRatio.values().parallelStream().filter(v -> v > 85L).count();
      long dsThreshExceeded = dsQuotaRatio.values().parallelStream().filter(v -> v > 85L).count();
      cachedUserNsQuotas.put(user, nsQuotaRatio);
      cachedUserDsQuotas.put(user, dsQuotaRatio);
      nsQuotaThreshCountsUsers.put(user, nsThreshExceeded);
      dsQuotaThreshCountsUsers.put(user, dsThreshExceeded);
      nsQuotaCount += nsQuotaRatio.size();
      dsQuotaCount += dsQuotaRatio.size();
      nsQuotaThreshCount += nsThreshExceeded;
      dsQuotaThreshCount += dsThreshExceeded;
      nsQuotaCountsUsers.put(user, (long) nsQuotaRatio.size());
      dsQuotaCountsUsers.put(user, (long) dsQuotaRatio.size());
    }

    long e1 = System.currentTimeMillis();
    long timeTaken = (e1 - s1);

    long s2 = System.currentTimeMillis();

    cachedLogins.putAll(nnLoader.getTokenExtractor().getTokenLastLogins());
    cachedUsers.clear();
    cachedUsers.addAll(users);
    cachedValues.put("timeTaken", timeTaken);
    cachedValues.put("reportTime", e1);
    cachedValues.put("capacity", capacity);
    cachedValues.put("diskspace", diskspace);
    cachedValues.put("diskspace24h", diskspace24h);
    cachedValues.put("numFiles", numFiles);
    cachedValues.put("numFiles24h", numFiles24h);
    cachedValues.put("numDirs", numDirs);
    cachedValues.put("totalFiles", numFiles);
    cachedValues.put("totalDirs", numDirs);
    cachedValues.put("emptyFiles", emptyFilesCount);
    cachedValues.put("emptyDirs", emptyDirsCount);
    cachedValues.put("tinyFiles", tinyFilesCount);
    cachedValues.put("smallFiles", smallFilesCount);
    cachedValues.put("emptyFiles24h", emptyFiles24hCount);
    cachedValues.put("emptyDirs24h", emptyDirs24hCount);
    cachedValues.put("tinyFiles24h", tinyFiles24hCount);
    cachedValues.put("smallFiles24h", smallFiles24hCount);
    cachedValues.put("emptyFiles1yr", emptyFiles1yrCount);
    cachedValues.put("emptyDirs1yr", emptyDirs1yrCount);
    cachedValues.put("tinyFiles1yr", tinyFiles1yrCount);
    cachedValues.put("smallFiles1yr", smallFiles1yrCount);
    cachedValues.put("mediumFiles", mediumFilesCount);
    cachedValues.put("largeFiles", largeFilesCount);
    cachedValues.put("emptyFilesMem", emptyFilesMem);
    cachedValues.put("emptyDirsMem", emptyDirsMem);
    cachedValues.put("tinyFilesMem", tinyFilesMem);
    cachedValues.put("tinyFilesDs", tinyFilesDs);
    cachedValues.put("smallFilesMem", smallFilesMem);
    cachedValues.put("smallFilesDs", smallFilesDs);
    cachedValues.put("emptyFiles24hMem", emptyFiles24hMem);
    cachedValues.put("emptyDirs24hMem", emptyDirs24hMem);
    cachedValues.put("tinyFiles24hMem", tinyFiles24hMem);
    cachedValues.put("smallFiles24hMem", smallFiles24hMem);
    cachedValues.put("tinyFiles24hDs", tinyFiles24hDs);
    cachedValues.put("smallFiles24hDs", smallFiles24hDs);
    cachedValues.put("oldFiles1yr", oldFiles1yrCount);
    cachedValues.put("oldFiles1yrDs", oldFiles1yrDs);
    cachedValues.put("oldFiles2yr", oldFiles2yrCount);
    cachedValues.put("oldFiles2yrDs", oldFiles2yrDs);
    cachedValues.put("nsQuotaCount", nsQuotaCount);
    cachedValues.put("dsQuotaCount", dsQuotaCount);
    cachedValues.put("nsQuotaThreshCount", nsQuotaThreshCount);
    cachedValues.put("dsQuotaThreshCount", dsQuotaThreshCount);
    cachedMaps.put("diskspaceUsers", diskspaceUsers);
    cachedMaps.put("numFilesUsers", filesUsers);
    cachedMaps.put("numDirsUsers", dirsUsers);
    cachedMaps.put("emptyFilesUsers", emptyFilesUsers);
    cachedMaps.put("emptyDirsUsers", emptyDirsUsers);
    cachedMaps.put("emptyFilesMemUsers", emptyFilesMemUsers);
    cachedMaps.put("emptyDirsMemUsers", emptyDirsMemUsers);
    cachedMaps.put("tinyFilesUsers", tinyFilesUsers);
    cachedMaps.put("smallFilesUsers", smallFilesUsers);
    cachedMaps.put("tinyFilesMemUsers", tinyFilesMemUsers);
    cachedMaps.put("smallFilesMemUsers", smallFilesMemUsers);
    cachedMaps.put("tinyFilesDsUsers", tinyFilesDsUsers);
    cachedMaps.put("smallFilesDsUsers", smallFilesDsUsers);
    cachedMaps.put("diskspace24hUsers", diskspace24hUsers);
    cachedMaps.put("numFiles24hUsers", numFiles24hUsers);
    cachedMaps.put("emptyFiles24hUsers", emptyFiles24hUsers);
    cachedMaps.put("emptyDirs24hUsers", emptyDirs24hUsers);
    cachedMaps.put("emptyFiles24hMemUsers", emptyFiles24hMemUsers);
    cachedMaps.put("emptyDirs24hMemUsers", emptyDirs24hMemUsers);
    cachedMaps.put("tinyFiles24hUsers", tinyFiles24hUsers);
    cachedMaps.put("smallFiles24hUsers", smallFiles24hUsers);
    cachedMaps.put("tinyFiles24hMemUsers", tinyFiles24hMemUsers);
    cachedMaps.put("smallFiles24hMemUsers", smallFiles24hMemUsers);
    cachedMaps.put("tinyFiles24hDsUsers", tinyFiles24hDsUsers);
    cachedMaps.put("smallFiles24hDsUsers", smallFiles24hDsUsers);
    cachedMaps.put("emptyFiles1yrUsers", emptyFiles1yrUsers);
    cachedMaps.put("emptyDirs1yrUsers", emptyDirs1yrUsers);
    cachedMaps.put("tinyFiles1yrUsers", tinyFiles1yrUsers);
    cachedMaps.put("smallFiles1yrUsers", smallFiles1yrUsers);
    cachedMaps.put("mediumFilesUsers", mediumFilesUsers);
    cachedMaps.put("largeFilesUsers", largeFilesUsers);
    cachedMaps.put("oldFiles1yrUsers", oldFiles1yrCountUsers);
    cachedMaps.put("oldFiles1yrDsUsers", oldFiles1yrDsUsers);
    cachedMaps.put("oldFiles2yrUsers", oldFiles2yrCountUsers);
    cachedMaps.put("oldFiles2yrDsUsers", oldFiles2yrDsUsers);
    cachedMaps.put("dirCount", dirCount);
    cachedMaps.put("dirDs", dirDs);
    cachedMaps.put("dirCount24h", dirCount24h);
    cachedMaps.put("dirDs24h", dirDs24h);
    cachedMaps.put("modTimeCount", modTimeCount);
    cachedMaps.put("modTimeDiskspace", modTimeDiskspace);
    cachedMaps.put("nsQuotaCountsUsers", nsQuotaCountsUsers);
    cachedMaps.put("dsQuotaCountsUsers", dsQuotaCountsUsers);
    cachedMaps.put("nsQuotaThreshCountsUsers", nsQuotaThreshCountsUsers);
    cachedMaps.put("dsQuotaThreshCountsUsers", dsQuotaThreshCountsUsers);

    long e2 = System.currentTimeMillis();
    LOG.info("Sync-switch of suggestions took: {} ms.", (e2 - s2));
    LOG.info("Reloading suggestions matrices took: {} ms.", timeTaken);
    loaded.set(true);

    HSQLDriver historyDbDriver = nnLoader.getEmbeddedHistoryDatabaseDriver();
    if (historyDbDriver != null && nnLoader.isInit() && nnLoader.isHistorical()) {
      long s3 = System.currentTimeMillis();
      try {
        historyDbDriver.logHistoryPerUser(cachedValues, cachedMaps, cachedUsers);
      } catch (SQLException e) {
        LOG.info("Failed to write historical data due to: {}", e);
      }
      long e3 = System.currentTimeMillis();
      LOG.info("Writing to embedded SQL DB took: {} ms.", (e3 - s3));
    } else {
      LOG.info("No historical data written as it is disabled.");
    }

    long s4 = System.currentTimeMillis();
    try {
      cacheManager.commit();
    } catch (Exception e) {
      LOG.info("Failed to write cache data due to: {}", e);
    }
    long e4 = System.currentTimeMillis();
    LOG.info("Writing to embedded MapDB took: {} ms.", (e4 - s4));
  }

  public String getTokens() {
    return Histograms.toJson(Histograms.sortByValue(cachedLogins, true));
  }

  public void addDirectoryToAnalysis(String directory) throws IOException {
    if (directory == null || directory.isEmpty()) {
      throw new IllegalArgumentException("Directory parameter 'dir' not defined.");
    }
    if (directory.endsWith("/")) {
      directory = directory.substring(0, directory.length() - 1);
    }
    boolean existed = cachedDirs.add(directory);
    if (existed) {
      throw new IOException(directory + " already set for analysis.");
    }
  }

  public void removeDirectoryFromAnalysis(String directory) throws IOException {
    if (directory == null || directory.isEmpty()) {
      throw new IllegalArgumentException("Directory parameter 'dir' not defined.");
    }
    if (directory.endsWith("/")) {
      directory = directory.substring(0, directory.length() - 1);
    }
    boolean removed = cachedDirs.remove(directory);
    if (!removed) {
      throw new IOException(directory + " was not scheduled for analysis.");
    }
  }

  public Set<String> getDirectoriesForAnalysis() {
    return cachedDirs;
  }

  public String getQuotaAsJson(String user, String sum) {
    if (sum == null || sum.length() == 0) {
      throw new IllegalArgumentException(
          "Please define a sum of either diskspaceConsumed or count for Quotas.");
    }
    if (user != null && user.length() > 0) {
      switch (sum) {
        case "dsQuotaRatioUsed":
          return Histograms.toJson(Histograms.sortByValue(cachedUserDsQuotas.get(user), false));
        case "nsQuotaRatioUsed":
          return Histograms.toJson(Histograms.sortByValue(cachedUserNsQuotas.get(user), false));
        default:
          throw new IllegalArgumentException(
              "Please choose between diskspaceConsumed or count for Quotas.");
      }
    } else {
      switch (sum) {
        case "dsQuotaRatioUsed":
          return Histograms.toJson(cachedUserDsQuotas);
        case "nsQuotaRatioUsed":
          return Histograms.toJson(cachedUserNsQuotas);
        default:
          throw new IllegalArgumentException(
              "Please choose between diskspaceConsumed or count for Quotas.");
      }
    }
  }

  public String getFileAgeAsJson(String sum) {
    if (sum == null || sum.length() == 0) {
      throw new IllegalArgumentException(
          "Please define a sum of either diskspaceConsumed or count for File ages.");
    }
    switch (sum) {
      case "diskspaceConsumed":
        return Histograms.toJson(getCachedMap("modTimeDiskspace"));
      case "count":
        return Histograms.toJson(getCachedMap("modTimeCount"));
      default:
        throw new IllegalArgumentException(
            "Please choose between diskspaceConsumed or count for File ages.");
    }
  }

  public String getUsersAsJson(String suggestion) {
    if (suggestion == null || suggestion.isEmpty()) {
      return Histograms.toJson(cachedUsers);
    } else {
      Map<String, Long> userSuggestions = cachedMaps.get(suggestion);
      if (userSuggestions == null) {
        throw new IllegalArgumentException(suggestion + " is not a valid suggestion query.");
      }
      return Histograms.toJson(userSuggestions);
    }
  }

  public String getSuggestionsAsJson(String user) {
    if (user == null || user.isEmpty()) {
      return Histograms.toJson(cachedValues);
    } else {
      Map<String, Long> userMap = new HashMap<>(cachedValues);
      userMap.put("diskspace", getCachedMap("diskspaceUsers").getOrDefault(user, 0L));
      userMap.put("diskspace24h", getCachedMap("diskspace24hUsers").getOrDefault(user, 0L));
      userMap.put("numFiles", getCachedMap("numFilesUsers").getOrDefault(user, 0L));
      userMap.put("numFiles24h", getCachedMap("numFiles24hUsers").getOrDefault(user, 0L));
      userMap.put("numDirs", getCachedMap("numDirsUsers").getOrDefault(user, 0L));
      userMap.put("emptyFiles", getCachedMap("emptyFilesUsers").getOrDefault(user, 0L));
      userMap.put("emptyFiles24h", getCachedMap("emptyFiles24hUsers").getOrDefault(user, 0L));
      userMap.put("emptyFiles1yr", getCachedMap("emptyFiles1yrUsers").getOrDefault(user, 0L));
      userMap.put("emptyFilesMem", getCachedMap("emptyFilesMemUsers").getOrDefault(user, 0L));
      userMap.put("emptyFiles24hMem", getCachedMap("emptyFiles24hMemUsers").getOrDefault(user, 0L));
      userMap.put("emptyDirs", getCachedMap("emptyDirsUsers").getOrDefault(user, 0L));
      userMap.put("emptyDirs24h", getCachedMap("emptyDirs24hUsers").getOrDefault(user, 0L));
      userMap.put("emptyDirs1yr", getCachedMap("emptyDirs1yrUsers").getOrDefault(user, 0L));
      userMap.put("emptyDirsMem", getCachedMap("emptyDirsMemUsers").getOrDefault(user, 0L));
      userMap.put("emptyDirs24hMem", getCachedMap("emptyDirs24hMemUsers").getOrDefault(user, 0L));
      userMap.put("tinyFiles", getCachedMap("tinyFilesUsers").getOrDefault(user, 0L));
      userMap.put("tinyFiles24h", getCachedMap("tinyFiles24hUsers").getOrDefault(user, 0L));
      userMap.put("tinyFiles1yr", getCachedMap("tinyFiles1yrUsers").getOrDefault(user, 0L));
      userMap.put("tinyFilesMem", getCachedMap("tinyFilesMemUsers").getOrDefault(user, 0L));
      userMap.put("tinyFiles24hMem", getCachedMap("tinyFiles24hMemUsers").getOrDefault(user, 0L));
      userMap.put("tinyFilesDs", getCachedMap("tinyFilesDsUsers").getOrDefault(user, 0L));
      userMap.put("tinyFiles24hDs", getCachedMap("tinyFiles24hDsUsers").getOrDefault(user, 0L));
      userMap.put("smallFiles", getCachedMap("smallFilesUsers").getOrDefault(user, 0L));
      userMap.put("smallFiles24h", getCachedMap("smallFiles24hUsers").getOrDefault(user, 0L));
      userMap.put("smallFiles1yr", getCachedMap("smallFiles1yrUsers").getOrDefault(user, 0L));
      userMap.put("smallFilesMem", getCachedMap("smallFilesMemUsers").getOrDefault(user, 0L));
      userMap.put("smallFiles24hMem", getCachedMap("smallFiles24hMemUsers").getOrDefault(user, 0L));
      userMap.put("smallFilesDs", getCachedMap("smallFilesDsUsers").getOrDefault(user, 0L));
      userMap.put("smallFiles24hDs", getCachedMap("smallFiles24hDsUsers").getOrDefault(user, 0L));
      userMap.put("mediumFiles", getCachedMap("mediumFilesUsers").getOrDefault(user, 0L));
      userMap.put("largeFiles", getCachedMap("largeFilesUsers").getOrDefault(user, 0L));
      userMap.put("oldFiles1yr", getCachedMap("oldFiles1yrUsers").getOrDefault(user, 0L));
      userMap.put("oldFiles1yrDs", getCachedMap("oldFiles1yrDsUsers").getOrDefault(user, 0L));
      userMap.put("oldFiles2yr", getCachedMap("oldFiles2yrUsers").getOrDefault(user, 0L));
      userMap.put("oldFiles2yrDs", getCachedMap("oldFiles2yrDsUsers").getOrDefault(user, 0L));
      userMap.put("nsQuotaCount", getCachedMap("nsQuotaCountsUsers").getOrDefault(user, 0L));
      userMap.put("dsQuotaCount", getCachedMap("dsQuotaCountsUsers").getOrDefault(user, 0L));
      userMap.put(
          "nsQuotaThreshCount", getCachedMap("nsQuotaThreshCountsUsers").getOrDefault(user, 0L));
      userMap.put(
          "dsQuotaThreshCount", getCachedMap("dsQuotaThreshCountsUsers").getOrDefault(user, 0L));
      userMap.put("lastLogin", cachedLogins.getOrDefault(user, 0L));
      return Histograms.toJson(userMap);
    }
  }

  public String getDirectoriesAsJson(String directory, String sum) {
    Map<String, Long> dirMap;
    switch (sum) {
      case "count":
        dirMap = getCachedMap("dirCount");
        break;
      case "diskspaceConsumed":
        dirMap = getCachedMap("dirDs");
        break;
      default:
        throw new IllegalArgumentException("Invalid sum type: " + sum);
    }
    if (directory != null && !directory.isEmpty()) {
      dirMap = Collections.singletonMap(directory, dirMap.get(directory));
    }
    return Histograms.toJson(dirMap);
  }

  public String getIssuesAsJson(Integer limit, boolean ascending) {
    Map<String, Map<String, Long>> issuesMap = new LinkedHashMap<>();
    Map<String, Long> topEmptyFileUsers =
        Histograms.sortByValue(getCachedMap("emptyFilesUsers"), ascending);
    Map<String, Long> topEmptyDirUsers =
        Histograms.sortByValue(getCachedMap("emptyDirsUsers"), ascending);
    Map<String, Long> topTinyFilesUsers =
        Histograms.sortByValue(getCachedMap("tinyFilesUsers"), ascending);
    Map<String, Long> topSmallFilesUsers =
        Histograms.sortByValue(getCachedMap("smallFilesUsers"), ascending);
    Map<String, Long> topEmptyFile24hUsers =
        Histograms.sortByValue(getCachedMap("emptyFiles24hUsers"), ascending);
    Map<String, Long> topEmptyDir24hUsers =
        Histograms.sortByValue(getCachedMap("emptyDirs24hUsers"), ascending);
    Map<String, Long> topTinyFiles24hUsers =
        Histograms.sortByValue(getCachedMap("tinyFiles24hUsers"), ascending);
    Map<String, Long> topSmallFiles24hUsers =
        Histograms.sortByValue(getCachedMap("smallFiles24hUsers"), ascending);
    Map<String, Long> topOldFiles1yrUsers =
        Histograms.sortByValue(getCachedMap("oldFiles1yrUsers"), ascending);
    Map<String, Long> topDirCount = Histograms.sortByValue(getCachedMap("dirCount"), ascending);
    Map<String, Long> topDirDiskspace = Histograms.sortByValue(getCachedMap("dirDs"), ascending);
    Map<String, Long> topDirCount24h =
        Histograms.sortByValue(getCachedMap("dirCount24h"), ascending);
    Map<String, Long> topDirDiskspace24h =
        Histograms.sortByValue(getCachedMap("dirDs24h"), ascending);
    Function<Map<String, Long>, Map<String, Long>> sliceFunc =
        (histogramMap) ->
            (ascending
                ? Histograms.sliceToBottom(histogramMap, limit)
                : Histograms.sliceToTop(histogramMap, limit));
    issuesMap.put("emptyFiles", sliceFunc.apply(topEmptyFileUsers));
    issuesMap.put("emptyDirs", sliceFunc.apply(topEmptyDirUsers));
    issuesMap.put("tinyFiles", sliceFunc.apply(topTinyFilesUsers));
    issuesMap.put("smallFiles", sliceFunc.apply(topSmallFilesUsers));
    issuesMap.put("emptyFiles24h", sliceFunc.apply(topEmptyFile24hUsers));
    issuesMap.put("emptyDirs24h", sliceFunc.apply(topEmptyDir24hUsers));
    issuesMap.put("tinyFiles24h", sliceFunc.apply(topTinyFiles24hUsers));
    issuesMap.put("smallFiles24h", sliceFunc.apply(topSmallFiles24hUsers));
    issuesMap.put("oldFiles1yr", sliceFunc.apply(topOldFiles1yrUsers));
    issuesMap.put("dirCount", sliceFunc.apply(topDirCount));
    issuesMap.put("dirDiskspace", sliceFunc.apply(topDirDiskspace));
    issuesMap.put("dirCount24h", sliceFunc.apply(topDirCount24h));
    issuesMap.put("dirDiskspace24h", sliceFunc.apply(topDirDiskspace24h));
    return Histograms.toJson(issuesMap);
  }

  public void stop() {
    cacheManager.stop();
  }

  public void start(SecurityConfiguration conf) throws IOException {
    cacheManager.start(conf);
    this.cachedDirs = Collections.synchronizedSet(cacheManager.getCachedSet("cachedDirs"));
    this.cachedUsers = Collections.synchronizedSet(cacheManager.getCachedSet("cachedUsers"));
    this.cachedValues = Collections.synchronizedMap(cacheManager.getCachedMap("cachedValues"));
    this.cachedLogins = Collections.synchronizedMap(cacheManager.getCachedMap("cachedLogins"));
    this.cachedMaps = Collections.synchronizedMap(cacheManager.getCachedMapToMap("cachedMaps"));
    this.cachedUserNsQuotas =
        Collections.synchronizedMap(cacheManager.getCachedMapToMap("cachedUserNsQuotas"));
    this.cachedUserDsQuotas =
        Collections.synchronizedMap(cacheManager.getCachedMapToMap("cachedUserDsQuotas"));
  }
}
