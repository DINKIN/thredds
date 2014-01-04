package ucar.nc2.grib.collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import thredds.featurecollection.FeatureCollectionConfig;
import thredds.filesystem.MFileOS;
import thredds.inventory.*;
import thredds.inventory.partition.*;
import ucar.nc2.grib.*;
import ucar.nc2.grib.grib1.Grib1Index;
import ucar.nc2.grib.grib2.Grib2Index;
import ucar.nc2.stream.NcStream;
import ucar.unidata.io.RandomAccessFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Formatter;
import java.util.List;

/**
 * Utilities for creating GRIB ncx2 files, both collections and partitions
 * GRIB2 only at the moment
 *
 * @author John
 * @since 12/5/13
 */
public class GribCdmIndex2 implements IndexReader {
  static private final Logger logger = LoggerFactory.getLogger(GribCdmIndex2.class);

  static public enum GribCollectionType {GRIB1, GRIB2, Partition1, Partition2, none}

  /**
   * Find out what kind of index this is
   *
   * @param raf open RAF
   * @return GribCollectionType
   * @throws IOException on read error
   */
  static public GribCollectionType getType(RandomAccessFile raf) throws IOException {
    String magic;

    raf.seek(0);
    byte[] b = new byte[Grib2CollectionBuilder.MAGIC_START.getBytes().length];   // they are all the same
    raf.read(b);
    magic = new String(b);

    switch (magic) {
      case Grib2CollectionBuilder.MAGIC_START:
        return GribCollectionType.GRIB2;

      //case Grib1CollectionBuilder.MAGIC_START:
      //  return GribCollectionType.GRIB1;

      case Grib2PartitionBuilder.MAGIC_START:
        return GribCollectionType.Partition2;

      //case Grib1TimePartitionBuilder.MAGIC_START:
      //  return GribCollectionType.Partition1;

    }
    return GribCollectionType.none;
  }

    // open GribCollection. caller must close
  static public GribCollection openCdmIndex(String indexFile, FeatureCollectionConfig.GribConfig config, Logger logger) throws IOException {
    RandomAccessFile raf = new RandomAccessFile(indexFile, "r");

    File f = new File(indexFile);
    int pos = f.getName().lastIndexOf(".");
    String name = (pos > 0) ? f.getName().substring(0, pos) : f.getName(); // remove ".ncx2"


    try {
      GribCollectionType type = getType(raf);

      switch (type) {
        case GRIB2:
          return Grib2CollectionBuilderFromIndex.readFromIndex(name, f.getParentFile(), raf, config, logger);
        case Partition2:
          return Grib2PartitionBuilderFromIndex.createTimePartitionFromIndex(name, f.getParentFile(), raf, config, logger);
      }

      return null;

    } catch (Throwable t) {
      raf.close();
      throw t;
    }
  }


  /**
   * Rewrite all the collection indices for all the directories in a directory partition recursively
   *
   * @param config  FeatureCollectionConfig
   * @param dirPath directory path
   * @throws IOException
   */
  static public void rewriteIndexesPartitionAll(FeatureCollectionConfig config, Path dirPath) throws IOException {
    GribCdmIndex indexReader = new GribCdmIndex();
    DirectoryPartition dpart = new DirectoryPartition(config, dirPath, indexReader, logger);
    rewriteIndexesPartitionRecurse(dpart, config);
  }

  static private void rewriteIndexesPartitionRecurse(DirectoryPartition dpart, FeatureCollectionConfig config) throws IOException {
    dpart.putAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG, config.gribConfig);

    // do its children
    for (MCollection part : dpart.makePartitions()) {
      if (part.isPartition()) {
        rewriteIndexesPartitionRecurse((DirectoryPartition) part, config);

      } else {
        Path partPath = Paths.get(part.getRoot());
        rewriteIndexesFilesAndCollection(config, partPath);
      }
    }

    // do this partition
    try (Grib2Partition tp = Grib2PartitionBuilder.factory(dpart, CollectionUpdateType.always, CollectionUpdateType.never, null, logger)) {
    }
  }

  /**
   * Rewrite all the grib indices in a directory, and the collection index for that directory
   *
   * @param config  FeatureCollectionConfig
   * @param dirPath directory path
   * @throws IOException
   */
  static public void rewriteIndexesFilesAndCollection(final FeatureCollectionConfig config, Path dirPath) throws IOException {
    long start = System.currentTimeMillis();
    String what;
    final Formatter errlog = new Formatter();

    String collectionName = DirectoryCollection.makeCollectionName(config.name, dirPath);
    Path idxFile = DirectoryCollection.makeCollectionIndexPath(config.name, dirPath);
    if (Files.exists(idxFile)) {
      what = "IndexRead";
      // read collection index
      try (GribCollection gc = Grib2CollectionBuilderFromIndex.readFromIndex(collectionName, dirPath.toFile(), config.gribConfig, logger)) {
        for (MFile mfile : gc.getFiles()) {
          try (GribCollection gcNested =
                       Grib2CollectionBuilder.readOrCreateIndexFromSingleFile(mfile, CollectionUpdateType.always, config.gribConfig, errlog, logger)) {
          }
        }
      }

    } else {
      what = "DirectoryScan";

      // collection index doesnt exists, so we have to scan
      // this idiom keeps the iterator from escaping, so that we can use try-with-resource, and ensure it closes. like++
      // i wonder what this looks like in Java 8 closures ??
      DirectoryCollection collection = new DirectoryCollection(config.name, dirPath, logger);
      collection.iterateOverMFileCollection(new DirectoryCollection.Visitor() {
        public void consume(MFile mfile) {
          try (GribCollection gcNested =
                       Grib2CollectionBuilder.readOrCreateIndexFromSingleFile(mfile, CollectionUpdateType.always, config.gribConfig, errlog, logger)) {
          } catch (IOException e) {
            logger.error("rewriteIndexesFilesAndCollection", e);
          }
        }
      });
    }

    // redo collection index
    DirectoryCollection dpart = new DirectoryCollection(config.name, dirPath, logger);
    dpart.putAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG, config.gribConfig);
    try (GribCollection gcNew = makeGribCollectionFromMCollection(false, dpart, CollectionUpdateType.always, errlog, logger)) {
    }

    long took = System.currentTimeMillis() - start;
    System.out.printf("%s %s took %s msecs%n%s%n", collectionName, what, took, errlog);
  }

  /**
   * File Partition: each File is a GribCollection, and the collection of all files in the directory is a PartitionCollection.
   * Rewrite the PartitionCollection and optionally its children
   *
   * @param config                FeatureCollectionConfig
   * @param forceCollection       always, test, nocheck, never
   * @param forceChildren         always, test, nocheck, never
   * @return true if partition was rewritten
   * @throws IOException
   */
  static public boolean rewriteFilePartition(final FeatureCollectionConfig config,
                                            final CollectionUpdateType forceCollection,
                                            final CollectionUpdateType forceChildren,
                                            final Logger logger) throws IOException {
    long start = System.currentTimeMillis();

    final Formatter errlog = new Formatter();
    CollectionSpecParser specp = new CollectionSpecParser(config.spec, errlog);
    Path rootPath = Paths.get(specp.getRootDir());

    FilePartition partition = new FilePartition(config.name, rootPath, logger);
    partition.putAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG, config.gribConfig);

    // redo the child collection here; could also do inside Grib2PartitionBuilder, not sure if advantage
    if (forceChildren != CollectionUpdateType.never) {
      partition.iterateOverMFileCollection(new DirectoryCollection.Visitor() {
        public void consume(MFile mfile) {
          try (GribCollection gcNested =
                       Grib2CollectionBuilder.readOrCreateIndexFromSingleFile(mfile, forceChildren, config.gribConfig, errlog, logger)) {
          } catch (IOException e) {
            logger.error("rewriteIndexesFilesAndCollection", e);
          }
        }
      });
    }

    // redo partition index if needed
    boolean recreated = Grib2PartitionBuilder.recreateIfNeeded(partition, forceCollection, CollectionUpdateType.never, errlog, logger);

    long took = System.currentTimeMillis() - start;
    String collectionName = partition.getCollectionName();
    if (recreated) logger.info("RewriteFilePartition {} took {} msecs \n errs={}", collectionName, took, errlog);

    return recreated;
  }

  /**
   * Directory Collection: the collection of all files in the directory is a DirectoryCollection.
   * Rewrite the DirectoryCollection
   *
   * @param config       FeatureCollectionConfig
   * @param forceCollection       always, test, nocheck, never
   * @param forceChildren         always, test, nocheck, never
   * @throws IOException
   */
  static public boolean rewriteDirectoryCollection(final FeatureCollectionConfig config,
                                              final CollectionUpdateType forceCollection,
                                              final CollectionUpdateType forceChildren,
                                              final Logger logger) throws IOException {
    long start = System.currentTimeMillis();
    final Formatter errlog = new Formatter();

    int pos = config.spec.lastIndexOf("/");
    Path dirPath = Paths.get(config.spec.substring(0,pos));

    DirectoryCollection dirCollection = new DirectoryCollection(config.name, dirPath, logger);
    String collectionName = DirectoryCollection.makeCollectionName(config.name, dirPath);

    if (forceChildren != CollectionUpdateType.never) {
      dirCollection.iterateOverMFileCollection(new DirectoryCollection.Visitor() {
        public void consume(MFile mfile) {
          try (GribCollection gcNested =
                       Grib2CollectionBuilder.readOrCreateIndexFromSingleFile(mfile, forceChildren, config.gribConfig, errlog, logger)) {
          } catch (IOException e) {
            logger.error("rewriteIndexesFilesAndCollection", e);
          }
        }
      });
    }

    // redo partition index
    dirCollection.putAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG, config.gribConfig);
    try (GribCollection gcNew = makeGribCollectionFromMCollection(false, dirCollection, forceCollection, errlog, logger)) {
    }

    long took = System.currentTimeMillis() - start;
    System.out.printf("rewriteDirectoryCollection %s took %s msecs%n%s%n", collectionName, took, errlog);
    return true;
  }

  ////////////////////////////////////////////////////////////////////////////////////

  /**
   * Create a grib collection from a single grib1 or grib2 file.
   * Use the existing index if it already exists.
   * Create the gbx9 and ncx2 files if needed.
   *
   * @param isGrib1 true if grib1
   * @param dataRaf the data file already open
   * @param config  special configuration
   * @param force   force writing index
   * @return the resulting GribCollection
   * @throws IOException on io error
   */
  public static GribCollection makeGribCollectionFromDataFile(boolean isGrib1, RandomAccessFile dataRaf, FeatureCollectionConfig.GribConfig config,
            CollectionUpdateType force, Formatter errlog, org.slf4j.Logger logger) throws IOException {

    String filename = dataRaf.getLocation();
    File dataFile = new File(filename);

    // LOOK not needed: Grib2CollectionBuilder.readOrCreateIndexFromSingleFile does all this ??
    GribIndex gribIndex = isGrib1 ? new Grib1Index() : new Grib2Index();
    boolean readOk;
    try {
      // see if gbx9 file exists or is out of date date is checked against the data file
      readOk = gribIndex.readIndex(filename, dataFile.lastModified(), force);
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

    MFile mfile = new MFileOS(dataFile);

    //if (isGrib1)
    //  return Grib1CollectionBuilder.readOrCreateIndexFromSingleFile(mfile, force, config, logger);
    //else
    return Grib2CollectionBuilder.readOrCreateIndexFromSingleFile(mfile, force, config, errlog, logger);
  }

  /**
   * Create a grib collection / partition collection from an existing ncx2 file.
   *
   * @param indexRaf the ncx2 file already open
   * @param config  special configuration
   * @param force   force writing index
   * @return the resulting GribCollection
   * @throws IOException on io error
   */
  public static GribCollection makeGribCollectionFromIndexFile(RandomAccessFile indexRaf, FeatureCollectionConfig.GribConfig config,
            CollectionUpdateType force, Formatter errlog, org.slf4j.Logger logger) throws IOException {

    GribCollectionType type = getType(indexRaf);

    String location = indexRaf.getLocation();
    File f = new File(location);
    int pos = f.getName().lastIndexOf(".");
    String name = (pos > 0) ? f.getName().substring(0, pos) : f.getName(); // remove ".ncx2"

    if (type == GribCollectionType.Partition2) {
      return Grib2PartitionBuilderFromIndex.createTimePartitionFromIndex(name, f.getParentFile(), indexRaf, config, logger);
    } else if (type == GribCollectionType.GRIB2) {
      return Grib2CollectionBuilderFromIndex.readFromIndex(name, f.getParentFile(), indexRaf, config, logger);
    }

    return null;
  }

  /**
   * Create a GribCollection from a collection of grib files, or a TimePartition from a collection of GribCollection index files
   *
   * @param isGrib1 true if files are grib1, else grib2
   * @param dcm     the MCollection : files or other collections
   * @param force   should index file be used or remade?
   * @return GribCollection
   * @throws IOException on io error
   */
  static public GribCollection makeGribCollectionFromMCollection(boolean isGrib1, MCollection dcm, CollectionUpdateType force,
                                       Formatter errlog, org.slf4j.Logger logger) throws IOException {
    /* if (isGrib1) {
      if (dcm.isPartition())
        if (force == CollectionUpdateType.never) {  // not actually needed, as Grib2TimePartitionBuilder.factory will eventually call  Grib2TimePartitionBuilderFromIndex
          FeatureCollectionConfig.GribConfig config = (FeatureCollectionConfig.GribConfig) dcm.getAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG);
          return Grib1TimePartitionBuilderFromIndex.createTimePartitionFromIndex(dcm.getCollectionName(), new File(dcm.getRoot()), config, logger);
        } else {
          return Grib1TimePartitionBuilder.factory(dcm, force, logger);
        }
      else
      if (force == CollectionUpdateType.never) {
        FeatureCollectionConfig.GribConfig config = (FeatureCollectionConfig.GribConfig) dcm.getAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG);
        return Grib1CollectionBuilderFromIndex.createFromIndex(dcm.getCollectionName(), new File(dcm.getRoot()), config, logger);
      } else {
        return Grib1CollectionBuilder.factory(dcm, force, logger);
      }
    }  */

    // grib2
    if (dcm.isPartition()) {
      if (force == CollectionUpdateType.never) {  // not actually needed, as Grib2TimePartitionBuilder.factory will eventually call  Grib2TimePartitionBuilderFromIndex
        FeatureCollectionConfig.GribConfig config = (FeatureCollectionConfig.GribConfig) dcm.getAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG);
        return Grib2PartitionBuilderFromIndex.createTimePartitionFromIndex(dcm.getCollectionName(), new File(dcm.getRoot()), config, logger);
      } else {                             //PartitionManager tpc, CollectionUpdateType forcePartition, CollectionUpdateType forceChildren,
                                           //  Formatter errlog, org.slf4j.Logger logger
        return Grib2PartitionBuilder.factory( (PartitionManager) dcm, force, force, errlog, logger);
      }
    } else {
      if (force == CollectionUpdateType.never) {
        FeatureCollectionConfig.GribConfig config = (FeatureCollectionConfig.GribConfig) dcm.getAuxInfo(FeatureCollectionConfig.AUX_GRIB_CONFIG);
        return Grib2CollectionBuilderFromIndex.readFromIndex(dcm.getCollectionName(), new File(dcm.getRoot()), config, logger);
      } else {
        return Grib2CollectionBuilder.factory(dcm, force, errlog, logger);
      }
    }
  }

 /* static public boolean update(boolean isGrib1, CollectionManager dcm, Formatter errlog, org.slf4j.Logger logger) throws IOException {
    //if (isGrib1) return Grib1CollectionBuilder.update(dcm, logger);
    return Grib2CollectionBuilder.update(dcm, errlog, logger);
  }  */


  /////////////////////////////////////////////////////////////////////////////////////
  // manipulate the ncx without building a gc
  private static final boolean debug = true;
  private byte[] magic;
  private int version;
  private GribCollectionProto.GribCollection gribCollectionIndex;

  /// IndexReader interface
  @Override
  public boolean readChildren(Path indexFile, AddChildCallback callback) throws IOException {
    if (debug) System.out.printf("GribCdmIndex.readChildren %s%n", indexFile);
    try (RandomAccessFile raf = new RandomAccessFile(indexFile.toString(), "r")) {
      GribCollectionType type = getType(raf);
      if (type == GribCollectionType.Partition1 || type == GribCollectionType.Partition2) {
        if (openIndex(raf, logger)) {
          String dirName = gribCollectionIndex.getTopDir();
          int n = gribCollectionIndex.getMfilesCount(); // partition index files stored in MFiles
          for (int i = 0; i < n; i++) {
            GribCollectionProto.MFile mfilep = gribCollectionIndex.getMfiles(i);
            callback.addChild(dirName, mfilep.getFilename(), mfilep.getLastModified());
          }
          return true;
        }
      }
      return false;
    }
  }

  @Override
  public boolean isPartition(Path indexFile) throws IOException {
    if (debug) System.out.printf("GribCdmIndex.isPartition %s%n", indexFile);
    try (RandomAccessFile raf = new RandomAccessFile(indexFile.toString(), "r")) {
      GribCollectionType type = getType(raf);
      return (type == GribCollectionType.Partition1) || (type == GribCollectionType.Partition2);
    }
  }

  @Override
  public boolean readMFiles(Path indexFile, List<MFile> result) throws IOException {
    if (debug) System.out.printf("GribCdmIndex.readMFiles %s%n", indexFile);
    try (RandomAccessFile raf = new RandomAccessFile(indexFile.toString(), "r")) {
      GribCollectionType type = getType(raf);
      if (type == GribCollectionType.GRIB1 || type == GribCollectionType.GRIB2) {
        if (openIndex(raf, logger)) {
          File protoDir = new File(gribCollectionIndex.getTopDir());
          int n = gribCollectionIndex.getMfilesCount();
          for (int i = 0; i < n; i++) {
            GribCollectionProto.MFile mfilep = gribCollectionIndex.getMfiles(i);
            result.add(new GribCollectionBuilder.GcMFile(protoDir, mfilep.getFilename(), mfilep.getLastModified()));
          }
        }
        return true;
      }
    }
    return false;
  }

  private boolean openIndex(RandomAccessFile indexRaf, Logger logger) {
    try {
      indexRaf.order(RandomAccessFile.BIG_ENDIAN);
      indexRaf.seek(0);

      //// header message
      magic = new byte[Grib2CollectionBuilder.MAGIC_START.getBytes().length];   // they are all the same
      indexRaf.read(magic);

      version = indexRaf.readInt();

      long recordLength = indexRaf.readLong();
      if (recordLength > Integer.MAX_VALUE) {
        logger.error("Grib2Collection {}: invalid recordLength size {}", indexRaf.getLocation(), recordLength);
        return false;
      }
      indexRaf.skipBytes(recordLength);

      int size = NcStream.readVInt(indexRaf);
      if ((size < 0) || (size > 100 * 1000 * 1000)) {
        logger.warn("Grib2Collection {}: invalid index size {}", indexRaf.getLocation(), size);
        return false;
      }

      byte[] m = new byte[size];
      indexRaf.readFully(m);
      gribCollectionIndex = GribCollectionProto.GribCollection.parseFrom(m);
      return true;

    } catch (Throwable t) {
      logger.error("Error reading index " + indexRaf.getLocation(), t);
      return false;
    }
  }

}
