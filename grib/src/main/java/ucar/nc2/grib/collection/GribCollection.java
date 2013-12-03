package ucar.nc2.grib.collection;

import net.jcip.annotations.ThreadSafe;
import thredds.featurecollection.FeatureCollectionConfig;
import thredds.filesystem.MFileOS;
import thredds.inventory.Collection;
import thredds.inventory.CollectionManager;
import thredds.inventory.MFile;
import ucar.arr.Coordinate;
import ucar.nc2.NetcdfFile;
import ucar.nc2.grib.*;
import ucar.nc2.grib.grib1.Grib1Index;
import ucar.nc2.grib.grib2.Grib2Index;
import ucar.nc2.grib.grib2.Grib2Pds;
import ucar.nc2.grib.grib2.Grib2Utils;
import ucar.nc2.iosp.IOServiceProvider;
import ucar.nc2.time.CalendarDateRange;
import ucar.nc2.util.CancelTask;
import ucar.nc2.util.DiskCache2;
import ucar.nc2.util.cache.FileCache;
import ucar.nc2.util.cache.FileCacheable;
import ucar.nc2.util.cache.FileFactory;
import ucar.unidata.geoloc.LatLonPoint;
import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.util.Parameter;
import ucar.unidata.util.StringUtil2;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

/**
 * A collection of grib files as a single logical dataset.
 * Concrete classes are for Grib1 and Grib2.
 * Note that there is no dependence on GRIB tables here.
 * Handles .ncx2 files
 *
 * @author John
 * @since 12/1/13
 */
public abstract class GribCollection implements FileCacheable, AutoCloseable {
  public static final String NCX_IDX = ".ncx2";
  public static final long MISSING_RECORD = -1;

  //////////////////////////////////////////////////////////
  // object cache for data files - these are opened only as raf, not netcdfFile
  private static FileCache dataRafCache;
  private static DiskCache2 diskCache;

  static public void initDataRafCache(int minElementsInMemory, int maxElementsInMemory, int period) {
    dataRafCache = new ucar.nc2.util.cache.FileCache("GribCollectionDataRafCache ", minElementsInMemory, maxElementsInMemory, -1, period);
  }

  static public FileCache getDataRafCache() {
    return dataRafCache;
  }

  static private final ucar.nc2.util.cache.FileFactory dataRafFactory = new FileFactory() {
    public FileCacheable open(String location, int buffer_size, CancelTask cancelTask, Object iospMessage) throws IOException {
      return new RandomAccessFile(location, "r");
    }
  };

  static public void disableDataRafCache() {
    if (null != dataRafCache) dataRafCache.disable();
    dataRafCache = null;
  }

  static public File getIndexFile(String path) {
    return getDiskCache2().getFile(path);
  }

  static public File getIndexFile(Collection dcm) {
    File idxFile = new File(new File(dcm.getRoot()), dcm.getCollectionName() + NCX_IDX);
    return getIndexFile( idxFile.getPath());
  }

  static public void setDiskCache2(DiskCache2 dc) {
    diskCache = dc;
  }

  static public DiskCache2 getDiskCache2() {
    if (diskCache == null)
      diskCache = new DiskCache2();

    return diskCache;
  }

  //////////////////////////////////////////////////////////

  // canonical order (time, ens, vert)
  static public int calcIndex(int timeIdx, int ensIdx, int vertIdx, int nens, int nverts) {
    if (nens == 0) nens = 1;
    if (nverts == 0) nverts = 1;
    return vertIdx + ensIdx * nverts + timeIdx * nverts * nens;
  }

  /**
   * Find index in partition dataset when vert and/or ens coordinates dont match with proto
   *
   * @param timeIdx time index in this partition
   * @param ensIdx  ensemble index in proto dataset
   * @param vertIdx vert index in proto dataset
   * @param flag    TimePartition.VERT_COORDS_DIFFER and/or TimePartition.ENS_COORDS_DIFFER
   * @param ec      ensemble coord in partition dataset
   * @param vc      vert coord in partition dataset
   * @param ecp     ensemble coord in proto dataset
   * @param vcp     vert coord in proto dataset
   * @return index in partition dataset
   */
  static public int calcIndex(int timeIdx, int ensIdx, int vertIdx, int flag, EnsCoord ec, VertCoord vc, EnsCoord ecp, VertCoord vcp) {
    int want_ensIdx = ensIdx;
    if ((flag & TimePartition.ENS_COORDS_DIFFER) != 0) {
      want_ensIdx = findEnsIndex(ensIdx, ec.getCoords(), ecp.getCoords());
      if (want_ensIdx == -1) return -1;
    }
    int want_vertIdx = vertIdx;
    if ((flag & TimePartition.VERT_COORDS_DIFFER) != 0) {
      want_vertIdx = findVertIndex(vertIdx, vc.getCoords(), vcp.getCoords());
      if (want_vertIdx == -1) return -1;
    }
    return calcIndex(timeIdx, want_ensIdx, want_vertIdx, (ec == null) ? 0 : ec.getSize(), (vc == null) ? 0 : vc.getSize());
  }

  static private int findEnsIndex(int indexp, List<EnsCoord.Coord> coords, List<EnsCoord.Coord> coordsp) {
    EnsCoord.Coord want = coordsp.get(indexp);
    for (int i = 0; i < coords.size(); i++) {
      EnsCoord.Coord have = coords.get(i);
      if (have.equals(want)) return i;
    }
    return -1;
  }

  static private int findVertIndex(int indexp, List<VertCoord.Level> coords, List<VertCoord.Level> coordsp) {
    VertCoord.Level want = coordsp.get(indexp);
    for (int i = 0; i < coords.size(); i++) {
      VertCoord.Level have = coords.get(i);
      if (have.equals(want)) return i;
    }
    return -1;
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Create a gbx9 and ncx index and a grib collection from a single grib1 or grib2 file.
   * Use the existing index is it already exists.
   *
   * @param isGrib1 true if grib1
   * @param dataRaf the grib file already open
   * @param config  special configuration
   * @param force  force writing index
   * @return the resulting GribCollection
   * @throws IOException on io error
   */
  public static GribCollection makeGribCollectionFromSingleFile(boolean isGrib1, RandomAccessFile dataRaf, FeatureCollectionConfig.GribConfig config,
                                                                CollectionManager.Force force, org.slf4j.Logger logger) throws IOException {

    GribIndex gribIndex = isGrib1 ? new Grib1Index() : new Grib2Index() ;

    String filename = dataRaf.getLocation();
    File dataFile = new File(filename);
    boolean readOk;
    try {
      readOk = gribIndex.readIndex(filename, dataFile.lastModified(), force); // heres where the gbx9 file date is checked against the data file
    } catch (IOException ioe) {
      readOk = false;
    }

    // make or remake the index
    if (!readOk) {
      gribIndex.makeIndex(filename, dataRaf);
      logger.debug("  Index written: {}", filename + GribIndex.GBX9_IDX);
    } else if (logger.isDebugEnabled()) {
      logger.debug("  Index read: {}", filename + GribIndex.GBX9_IDX);
    }

    // heres where the ncx file date is checked against the data file
    MFile mfile = new MFileOS(dataFile);
    //if (isGrib1)
    //  return Grib1CollectionBuilder.readOrCreateIndexFromSingleFile(mfile, force, config, logger);
    //else
      return Grib2CollectionBuilder.readOrCreateIndexFromSingleFile(mfile, force, config, logger);
  }

  /**
   * Create a GribCollection from a collection of grib files, or a TimePartition from a collection of GribCollection index files
   *
   * @param isGrib1 true if files are grib1, else grib2
   * @param dcm     the file collection : assume its been scanned
   * @param force   should index file be used or remade?
   * @return GribCollection
   * @throws IOException on io error
   */
  static public GribCollection factory(boolean isGrib1, thredds.inventory.Collection dcm, CollectionManager.Force force, org.slf4j.Logger logger) throws IOException {
    /* if (isGrib1) {
      if (dcm.isPartition())
        if (force == CollectionManager.Force.never) {  // LOOK not actually needed, as Grib2TimePartitionBuilder.factory will eventually call  Grib2TimePartitionBuilderFromIndex
          FeatureCollectionConfig.GribConfig config = (FeatureCollectionConfig.GribConfig) dcm.getAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG);
          return Grib1TimePartitionBuilderFromIndex.createTimePartitionFromIndex(dcm.getCollectionName(), new File(dcm.getRoot()), config, logger);
        } else {
          return Grib1TimePartitionBuilder.factory(dcm, force, logger);
        }
      else
      if (force == CollectionManager.Force.never) {
        FeatureCollectionConfig.GribConfig config = (FeatureCollectionConfig.GribConfig) dcm.getAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG);
        return Grib1CollectionBuilderFromIndex.createFromIndex(dcm.getCollectionName(), new File(dcm.getRoot()), config, logger);
      } else {
        return Grib1CollectionBuilder.factory(dcm, force, logger);
      }
    }  */

    // grib2
    /* if (dcm.isPartition()) {
      if (force == CollectionManager.Force.never) {  // LOOK not actually needed, as Grib2TimePartitionBuilder.factory will eventually call  Grib2TimePartitionBuilderFromIndex
        FeatureCollectionConfig.GribConfig config = (FeatureCollectionConfig.GribConfig) dcm.getAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG);
        return Grib2TimePartitionBuilderFromIndex.createTimePartitionFromIndex(dcm.getCollectionName(), new File(dcm.getRoot()), config, logger);
      } else {
        return Grib2TimePartitionBuilder.factory((PartitionManager) dcm, force, logger);
      }
    } else { */
      if (force == CollectionManager.Force.never) {
        FeatureCollectionConfig.GribConfig config = (FeatureCollectionConfig.GribConfig) dcm.getAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG);
        return Grib2CollectionBuilderFromIndex.createFromIndex(dcm.getCollectionName(), new File(dcm.getRoot()), config, logger);
      } else {
        return Grib2CollectionBuilder.factory(dcm, force, logger);
      }
    //}
  }

  static public GribCollection createFromIndex(boolean isGrib1, String name, File directory, RandomAccessFile raf, FeatureCollectionConfig.GribConfig config, org.slf4j.Logger logger) throws IOException {
    //if (isGrib1) return Grib1CollectionBuilderFromIndex.createFromIndex(name, directory, raf, config, logger);
    return Grib2CollectionBuilderFromIndex.createFromIndex(name, directory, raf, config, logger);
  }

  static public boolean update(boolean isGrib1, CollectionManager dcm, org.slf4j.Logger logger) throws IOException {
    //if (isGrib1) return Grib1CollectionBuilder.update(dcm, logger);
    return Grib2CollectionBuilder.update(dcm, logger);
  }

  ////////////////////////////////////////////////////////////////
  protected final String name;
  protected File directory;
  protected final FeatureCollectionConfig.GribConfig gribConfig;
  protected final boolean isGrib1;

  protected FileCache objCache = null;  // optional object cache - used in the TDS
  public int version; // the ncx version

  // set by the builder
  public int center, subcenter, master, local;  // GRIB 1 uses "local" for table version
  public int genProcessType, genProcessId, backProcessId;
  public List<GroupHcs> groups; // must be kept in order unmodifiableList
  public List<Parameter> params;  // used ??

  private List<MFile> files;  // must be kept in order
  private Map<String, MFile> fileMap;
  private File indexFile;

  // synchronize any access to indexRaf
  protected RandomAccessFile indexRaf; // this is the raf of the index (ncx) file

  public String getName() {
    return name;
  }

  public List<MFile> getFiles() {
    return files;
  }

  public FeatureCollectionConfig.GribConfig getGribConfig() {
    return gribConfig;
  }

  public List<String> getFilenames() {
    List<String> result = new ArrayList<String>();
    for (MFile file : files)
      result.add(file.getPath());
    Collections.sort(result);
    return result;
  }


  public MFile findMFileByName(String filename) {
    if (fileMap == null) {
      fileMap = new HashMap<String, MFile>(files.size()*2);
      for (MFile file : files)
        fileMap.put(file.getName(), file);
    }
    return fileMap.get(filename);
  }

  public void setFiles(List<MFile> files) {
    this.files = Collections.unmodifiableList(files);
    this.fileMap = null;
  }

  /**
   * public by accident, do not use
   * @param indexRaf the open raf of the index file
   */
  public void setIndexRaf(RandomAccessFile indexRaf) {
    this.indexRaf = indexRaf;
    if (indexRaf != null) {
      if (indexFile == null) {
        indexFile = new File(indexRaf.getLocation());
      } /* else if (debug) {
        File f = new File(indexRaf.getLocation());
        if (!f.getCanonicalPath().equals().indexFile.getCanonicalPath())
          logger.warn("indexRaf {} not equal indexFile {}", f.getCanonicalPath(), indexFile.getCanonicalPath());
      }  */
    }
  }

  /**
   * get index file; may not exist
   *
   * @return File, but may not exist
   */
  public File getIndexFile() {
    if (indexFile == null) {
      String nameNoBlanks = StringUtil2.replace(name, ' ', "_");
      File f = new File(directory, nameNoBlanks + NCX_IDX);
      indexFile = getDiskCache2().getFile(f.getPath()); // diskCcahe manages where the index file lives
    }
    return indexFile;
  }

  static public File getIndexFile(String collectionName, File directory) {
    String nameNoBlanks = StringUtil2.replace(collectionName, ' ', "_");
    File f = new File(directory, nameNoBlanks + NCX_IDX);
    return getDiskCache2().getFile(f.getPath()); // diskCache manages where the index file lives
  }

  public File makeNewIndexFile(org.slf4j.Logger logger) {
    if (indexFile != null && indexFile.exists()) {
      if (!indexFile.delete())
        logger.warn("Failed to delete {}", indexFile.getPath());
    }
    indexFile = null;
    return getIndexFile();
  }

  public List<GroupHcs> getGroups() {
    return groups;
  }

  public List<Parameter> getParams() {
    return params;
  }

  public int getCenter() {
    return center;
  }

  public int getSubcenter() {
    return subcenter;
  }

  public int getMaster() {
    return master;
  }

  public int getLocal() {
    return local;
  }

  public int getGenProcessType() {
    return genProcessType;
  }

  public int getGenProcessId() {
    return genProcessId;
  }

  public int getBackProcessId() {
    return backProcessId;
  }

  public boolean isGrib1() {
    return isGrib1;
  }

  public File getDirectory() {
    return directory;
  }

  public void setDirectory(File directory) {
    this.directory = directory;
  }

  protected GribCollection(String name, File directory, FeatureCollectionConfig.GribConfig config, boolean isGrib1) {
    this.name = name;
    this.directory = directory;
    this.gribConfig = config;
    this.isGrib1 = isGrib1;
  }

  /////////////////////////////////////////////

  // stuff for InvDatasetFcGrib
  public abstract ucar.nc2.dataset.NetcdfDataset getNetcdfDataset(String groupName, String filename, FeatureCollectionConfig.GribConfig gribConfig, org.slf4j.Logger logger) throws IOException;

  public abstract ucar.nc2.dt.grid.GridDataset getGridDataset(String groupName, String filename, FeatureCollectionConfig.GribConfig gribConfig, org.slf4j.Logger logger) throws IOException;

  public GroupHcs getGroup(int index) {
    return groups.get(index);
  }

  public GroupHcs findGroupById(String id) {
    for (GroupHcs g : getGroups()) {
      if (g.getId().equals(id))
        return g;
    }
    return null;
  }

  public int findGroupIdxById(String id) {
    for (int i = 0; i < groups.size(); i++) {
      GroupHcs g = groups.get(i);
      if (g.getId().equals(id)) return i;
    }
    return -1;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // stuff for Iosp

  public RandomAccessFile getDataRaf(int fileno) throws IOException {
    // absolute location
    MFile mfile = files.get(fileno);
    String filename = mfile.getPath();
    File dataFile = new File(filename);

    // check reletive location - eg may be /upc/share instead of Q:
    if (!dataFile.exists()) {
      if (files.size() == 1) {
        dataFile = new File(directory, name); // single file case
      } else {
        dataFile = new File(directory, dataFile.getName()); // must be in same directory as the ncx file
      }
    }


    // data file not here
    if (!dataFile.exists()) {
      throw new FileNotFoundException("data file not found = " + filename);
    }

    RandomAccessFile want = getDataRaf(dataFile.getPath());
    want.order(RandomAccessFile.BIG_ENDIAN);
    return want;
  }

  private RandomAccessFile getDataRaf(String location) throws IOException {
    if (dataRafCache != null) {
      return (RandomAccessFile) dataRafCache.acquire(dataRafFactory, location, null);
    } else {
      return new RandomAccessFile(location, "r");
    }
  }

  /* public void close() throws java.io.IOException {
    if (indexRaf != null) {
          indexRaf.close();
          indexRaf = null;
        }
      }  */

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // stuff for FileCacheable

  public void close() throws java.io.IOException {
    if (objCache != null) {
      objCache.release(this);
    } else if (indexRaf != null) {
      indexRaf.close();
      indexRaf = null;
    }
  }
  @Override
  public String getLocation() {
    if (indexRaf != null) return indexRaf.getLocation();
    return getIndexFile().getPath();
  }

  @Override
  public long getLastModified() {
    if (indexFile != null) {
      return indexFile.lastModified();
    }
    return 0;
  }

  @Override
  public void setFileCache(FileCache fileCache) {
    this.objCache = fileCache;
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////

  // these objects are created from the ncx index.
  private Set<String> groupNames = new HashSet<String>(5);

  // this class needs to be immutable
  public class GroupHcs implements Comparable<GroupHcs> {
    public GdsHorizCoordSys hcs;
    public byte[] rawGds;
    public int gdsHash;

    private String id, description;
    public List<VariableIndex> varIndex;   // GribCollection.VariableIndex
    public List<Coordinate> coords;
    public List<TimeCoord> timeCoords;
    public List<VertCoord> vertCoords;
    public List<EnsCoord> ensCoords;
    public int[] filenose;
    public List<TimeCoordUnion> timeCoordPartitions; // used only for time partitions - DO NOT USE

    public void setHorizCoordSystem(GdsHorizCoordSys hcs, byte[] rawGds, int gdsHash) {
      this.hcs = hcs;
      this.rawGds = rawGds;
      this.gdsHash = gdsHash;
    }

    private String makeId() {
      // check for user defined group names
      String result = null;
      /* if (gribConfig != null && gribConfig.gdsNamer != null)
        result = gribConfig.gdsNamer.get(gdsHash);
      if (result != null) {
        StringBuilder sb = new StringBuilder(result);
        StringUtil2.replace(sb, ". :", "p--");
        return sb.toString();
      }  */
      /* if (gribConfig != null && gribConfig.groupNamer != null) { LOOK not implemented
        MFile mfile = files.get(filenose[0]);
        //File firstFile = new File(mfile.getPath());
        LatLonPoint centerPoint = hcs.getCenterLatLon();
        StringBuilder sb = new StringBuilder(firstFile.getName().substring(15, 26) + "-" + centerPoint.toString());
        StringUtil2.replace(sb, ". :", "p--");
        return sb.toString();
      } */

      // default id
      String base = hcs.makeId();
      // ensure uniqueness
      String tryit = base;
      int count = 1;
      while (groupNames.contains(tryit)) {
        count++;
        tryit = base + "-" + count;
      }
      groupNames.add(tryit);
      return tryit;
    }

    private String makeDescription() {
      // check for user defined group names
      String result = null;
      if (gribConfig != null && gribConfig.gdsNamer != null)
        result = gribConfig.gdsNamer.get(gdsHash);
      if (result != null) return result;
      if (gribConfig != null && gribConfig.groupNamer != null) {
        MFile mfile = files.get(filenose[0]);
        File firstFile = new File(mfile.getPath()); //  NAM_Firewxnest_20111215_0600.grib2
        LatLonPoint centerPoint = hcs.getCenterLatLon();
        return "First Run " + firstFile.getName().substring(15, 26) + ", Center " + centerPoint;
      }

      return hcs.makeDescription(); // default desc
    }

    public GribCollection getGribCollection() {
      return GribCollection.this;
    }

    // unique name for Group
    public String getId() {
      if (id == null) id = makeId();
      return id;
    }

    // human readable
    public String getDescription() {
      if (description == null)
        description = makeDescription();

      return description;
    }

    // must have thread safety for vc.setName()
    private boolean vertNamesAssigned = false;
    synchronized public void assignVertNames( GribTables cust) {
      if (vertNamesAssigned) return;
      VertCoord.assignVertNames(vertCoords, cust);
      vertNamesAssigned = true;
    }


    @Override
    public int compareTo(GroupHcs o) {
      return getDescription().compareTo(o.getDescription());
    }

    public List<MFile> getFiles() {
      List<MFile> result = new ArrayList<>();
      for (int fileno : filenose)
        result.add(files.get(fileno));
      Collections.sort(result);
      return result;
    }

    public List<String> getFilenames() {
      List<String> result = new ArrayList<String>();
      for (int fileno : filenose)
        result.add(files.get(fileno).getPath());
      Collections.sort(result);
      return result;
    }

    /* public GribCollection.VariableIndex findAnyVariableWithTime(int usesTimeIndex) {
      for (VariableIndex vi : varIndex)
        if (vi.timeIdx == usesTimeIndex) return vi;
      return null;
    } */

    public GribCollection.VariableIndex findVariableByHash(int cdmHash) {
      for (VariableIndex vi : varIndex)                // look might want to hash to avoid linear lookup cost
        if (vi.cdmHash == cdmHash) return vi;
      return null;
    }

    public CalendarDateRange getTimeCoverage() {
      TimeCoord useTc = null;
      for (TimeCoord tc : timeCoords) {
        if (useTc == null || useTc.getSize() < tc.getSize())  // use time coordinate with most values
          useTc = tc;
      }
      return (useTc == null) ? null : useTc.getCalendarRange();
    }

  }

  public GribCollection.VariableIndex makeVariableIndex(GroupHcs g, int cdmHash, int discipline,
              Grib2Pds pds, List<Integer> index, long recordsPos, int recordsLen) {

    int category = pds.getParameterCategory();
    int parameter = pds.getParameterNumber();
    int levelType = pds.getLevelType1();
    boolean isLayer = Grib2Utils.isLayer(pds);
    int intvType = pds.getStatisticalProcessType();

    String intvName = null;
    //if (pds.isTimeInterval())
    //   intvName = rect.getTimeIntervalName(vb.timeCoordIndex);

    int ensDerivedType = -1;
     if (pds.isEnsembleDerived()) {
       Grib2Pds.PdsEnsembleDerived pdsDerived = (Grib2Pds.PdsEnsembleDerived) pds;
       ensDerivedType = pdsDerived.getDerivedForecastType(); // derived type (table 4.7)
     }

    int probType = -1;
    String probName = null;
     if (pds.isProbability()) {
       Grib2Pds.PdsProbability pdsProb = (Grib2Pds.PdsProbability) pds;
       probName = pdsProb.getProbabilityName();
       probType = pdsProb.getProbabilityType();
     }

     int genType = pds.getGenProcessType(); // LOOK why gc genProcessType  ?

    return new VariableIndex(g, -1, discipline, category, parameter, levelType, isLayer,
            intvType, intvName, ensDerivedType, probType, probName, genProcessType, cdmHash,
            index, recordsPos, recordsLen);
 }


  public class VariableIndex implements Comparable<VariableIndex> {
    public final int tableVersion; // grib1 : can vary by variable
    public final int discipline, category, parameter, levelType, intvType, ensDerivedType, probType;  // uniquely identifies the variable
    public final String intvName;                                                                     // uniquely identifies the variable
    public final String probabilityName;                                                              // uniquely identifies the variable
    public final boolean isLayer;                                                                     // uniquely identifies the variable
    public final int genProcessType;
    public final int cdmHash;                  // unique hashCode - from Grib2Record, but works here also

    public List<Integer> index;      // which time, vert and ens coordinates to use (in group)
    public final long recordsPos;              // where the records array is stored in the index. 0 means no records
    public final int recordsLen;
    public final GroupHcs group;               // belongs to this group

    public int ntimes, nverts, nens;           // time, vert and ens coordinate lengths
    public Record[] records;                   // Record[ntimes*nverts*nens] - lazy init

    public int partTimeCoordIdx; // partition time coordinate index

    public VariableIndex(GroupHcs g, int tableVersion,
                         int discipline, int category, int parameter, int levelType, boolean isLayer,
                         int intvType, String intvName, int ensDerivedType, int probType, String probabilityName,
                         int genProcessType,
                         int cdmHash, List<Integer> index, long recordsPos, int recordsLen) {
      this.group = g;
      this.tableVersion = tableVersion;
      this.discipline = discipline;
      this.category = category;
      this.parameter = parameter;
      this.levelType = levelType;
      this.isLayer = isLayer;
      this.intvType = intvType;
      this.intvName = intvName;
      this.ensDerivedType = ensDerivedType;
      this.probabilityName = probabilityName;
      this.probType = probType;
      this.genProcessType = genProcessType;
      this.cdmHash = cdmHash;
      this.index = index;
      this.recordsPos = recordsPos;
      this.recordsLen = recordsLen;
    }

    public TimeCoord getTimeCoord() {
      return null; // timeIdx < 0 ? null : group.timeCoords.get(timeIdx);
    }

    public VertCoord getVertCoord() {
      return null; // vertIdx < 0 ? null : group.vertCoords.get(vertIdx);
    }

    public EnsCoord getEnsCoord() {
      return null; // ensIdx < 0 ? null : group.ensCoords.get(ensIdx);
    }

    public boolean hasEns() {
      return false; // LOOK
    }

    public boolean hasVert() {
      return false; // vertIdx >= 0;
    }

    public String id() {
      return discipline + "-" + category + "-" + parameter;
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder();
      sb.append("VariableIndex");
      sb.append("{tableVersion=").append(tableVersion);
      sb.append(", discipline=").append(discipline);
      sb.append(", category=").append(category);
      sb.append(", parameter=").append(parameter);
      sb.append(", levelType=").append(levelType);
      sb.append(", intvType=").append(intvType);
      sb.append(", ensDerivedType=").append(ensDerivedType);
      sb.append(", probType=").append(probType);
      sb.append(", intvName='").append(intvName).append('\'');
      sb.append(", probabilityName='").append(probabilityName).append('\'');
      sb.append(", isLayer=").append(isLayer);
      sb.append(", genProcessType=").append(genProcessType);
      sb.append(", cdmHash=").append(cdmHash);
      //sb.append(", timeIdx=").append(timeIdx);
      //sb.append(", vertIdx=").append(vertIdx);
      //sb.append(", ensIdx=").append(ensIdx);
      sb.append(", ntimes=").append(ntimes);
      sb.append(", nverts=").append(nverts);
      sb.append(", nens=").append(nens);
      sb.append(", partTimeCoordIdx=").append(partTimeCoordIdx);
      sb.append('}');
      return sb.toString();
    }

    public String toStringComplete() {
      final StringBuilder sb = new StringBuilder();
      sb.append("VariableIndex");
      sb.append("{tableVersion=").append(tableVersion);
      sb.append(", discipline=").append(discipline);
      sb.append(", category=").append(category);
      sb.append(", parameter=").append(parameter);
      sb.append(", levelType=").append(levelType);
      sb.append(", intvType=").append(intvType);
      sb.append(", ensDerivedType=").append(ensDerivedType);
      sb.append(", probType=").append(probType);
      sb.append(", intvName='").append(intvName).append('\'');
      sb.append(", probabilityName='").append(probabilityName).append('\'');
      sb.append(", isLayer=").append(isLayer);
      sb.append(", cdmHash=").append(cdmHash);
      //sb.append(", timeIdx=").append(timeIdx);
      //sb.append(", vertIdx=").append(vertIdx);
      //sb.append(", ensIdx=").append(ensIdx);
      sb.append(", recordsPos=").append(recordsPos);
      sb.append(", recordsLen=").append(recordsLen);
      sb.append(", group=").append(group.getId());
      sb.append(", ntimes=").append(ntimes);
      sb.append(", nverts=").append(nverts);
      sb.append(", nens=").append(nens);
      //sb.append(", records=").append(records == null ? "null" : Arrays.asList(records).toString());
      sb.append(", partTimeCoordIdx=").append(partTimeCoordIdx);
      sb.append('}');
      return sb.toString();
    }

    public String toStringShort() {
      Formatter sb = new Formatter();
      sb.format("Variable {%d-%d-%d", discipline, category, parameter);
      // if (vertIdx>=0) sb.format(" level=%d", vertIdx);
      if (intvName != null && intvName.length() > 0) sb.format(" intv=%s", intvName);
      if (probabilityName != null && probabilityName.length() > 0) sb.format(" prob=%s", probabilityName);
      sb.format(" cdmHash=%d}", cdmHash);
      return sb.toString();
    }


    public Record[] getRecords() throws IOException {
      readRecords();
      return records;
    }

    // LOOK : use ehcache here ??
    public void readRecords() throws IOException {
      if (records != null) return;

      byte[] b = new byte[recordsLen];
      if (recordsLen == 0) return;

      // synchronize to protect the raf, and records[]
      synchronized (indexRaf) {
        indexRaf.seek(recordsPos);
        indexRaf.readFully(b);
      }

      // synchronize to protect records[]
      synchronized (this) {
        GribCollectionProto.SparseArray proto = GribCollectionProto.SparseArray.parseFrom(b);
        int cdmHash = proto.getCdmHash();
        if (cdmHash != this.cdmHash)
          throw new IllegalStateException("Corrupted index");
        int n = proto.getRecordsCount();
        Record[] recordsTemp = new Record[n];
        for (int i = 0; i < n; i++) {
          GribCollectionProto.Record pr = proto.getRecords(i);
          recordsTemp[i] = new Record(pr.getFileno(), pr.getPos(), pr.getBmsPos());
        }
        records = recordsTemp; // switch all at once - worse case is it gets read more than once
      }
    }

    @Override
    public int compareTo(VariableIndex o) {
      int r = discipline - o.discipline;  // LOOK add center, subcenter, version?
      if (r != 0) return r;
      r = category - o.category;
      if (r != 0) return r;
      r = parameter - o.parameter;
      if (r != 0) return r;
      r = levelType - o.levelType;
      if (r != 0) return r;
      r = intvType - o.intvType;
      return r;
    }

  }

  public static class Record {
    public int fileno; // which file
    public long pos;   // offset on file where data starts
    public long bmsPos;   // if non-zero, offset where bms starts

    public Record(int fileno, long pos, long bmsPos) {
      this.fileno = fileno;
      this.pos = pos;
      this.bmsPos = bmsPos;
    }
  }

  public void showIndex(Formatter f) {
    f.format("%s%n%n", toString());
    f.format("Class (%s)%n", getClass().getName());
    if (files == null) {
      f.format("Files empty%n");
    } else {
      f.format("Files (%d)%n", files.size());
      for (MFile file : files)
        f.format("  %s%n", file);
      f.format("%n");
    }

    for (GroupHcs g : groups) {
      f.format("Hcs = %s%n", g.hcs);

      f.format("%nVarIndex (%d)%n", g.varIndex.size());
      for (VariableIndex v : g.varIndex)
        f.format("  %s%n", v.toStringComplete());

      f.format("%nTimeCoords (%d)%n", g.timeCoords.size());
      for (int i = 0; i < g.timeCoords.size(); i++) {
        TimeCoord tc = g.timeCoords.get(i);
        f.format(" %d: %s%n", i, tc);
      }
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("GribCollection");
    sb.append("{\n name='").append(name).append('\'');
    sb.append("\n directory=").append(directory);
    sb.append("\n isGrib1=").append(isGrib1);
    sb.append("\n center=").append(center);
    sb.append("\n subcenter=").append(subcenter);
    sb.append("\n master=").append(master);
    sb.append("\n local=").append(local);
    sb.append("\n genProcessType=").append(genProcessType);
    sb.append("\n genProcessId=").append(genProcessId);
    sb.append("\n backProcessId=").append(backProcessId);
    sb.append("\n indexFile=").append(indexFile);
    sb.append("\n version=").append(version);
    sb.append('}');
    return sb.toString();
  }

  public GribCollection.GroupHcs makeGroup() {
    return new GribCollection.GroupHcs();
  }

  // must override NetcdfFile to pass in the iosp
  // used by the subclasses of GribCollection
  static protected class GcNetcdfFile extends NetcdfFile {
    public GcNetcdfFile(IOServiceProvider spi, RandomAccessFile raf, String location, CancelTask cancelTask) throws IOException {
      super(spi, raf, location, cancelTask);
    }
  }

}

