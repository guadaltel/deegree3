//$HeadURL$
/*----------------    FILE HEADER  ------------------------------------------
 This file is part of deegree.
 Copyright (C) 2001-2009 by:
 Department of Geography, University of Bonn
 http://www.giub.uni-bonn.de/deegree/
 lat/lon GmbH
 http://www.lat-lon.de

 This library is free software; you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public
 License as published by the Free Software Foundation; either
 version 2.1 of the License, or (at your option) any later version.
 This library is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 Lesser General Public License for more details.
 You should have received a copy of the GNU Lesser General Public
 License along with this library; if not, write to the Free Software
 Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 Contact:

 Andreas Poth
 lat/lon GmbH
 Aennchenstr. 19
 53177 Bonn
 Germany
 E-Mail: poth@lat-lon.de

 Prof. Dr. Klaus Greve
 Department of Geography
 University of Bonn
 Meckenheimer Allee 166
 53115 Bonn
 Germany
 E-Mail: greve@giub.uni-bonn.de
 ---------------------------------------------------------------------------*/

package org.deegree.coverage.raster.utils;

import static org.deegree.coverage.raster.utils.RasterFactory.loadRasterFromFile;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.deegree.commons.datasource.configuration.AbstractGeospatialDataSourceType;
import org.deegree.commons.datasource.configuration.MultiResolutionDataSource;
import org.deegree.commons.datasource.configuration.RasterDataSource;
import org.deegree.commons.datasource.configuration.RasterFileSetType;
import org.deegree.commons.datasource.configuration.RasterFileType;
import org.deegree.commons.datasource.configuration.MultiResolutionDataSource.Resolution;
import org.deegree.commons.utils.FileUtils;
import org.deegree.commons.xml.XMLAdapter;
import org.deegree.coverage.AbstractCoverage;
import org.deegree.coverage.persistence.CoverageBuilder;
import org.deegree.coverage.raster.AbstractRaster;
import org.deegree.coverage.raster.MultiResolutionRaster;
import org.deegree.coverage.raster.SimpleRaster;
import org.deegree.coverage.raster.TiledRaster;
import org.deegree.coverage.raster.container.IndexedMemoryTileContainer;
import org.deegree.coverage.raster.container.MemoryTileContainer;
import org.deegree.coverage.raster.geom.RasterGeoReference;
import org.deegree.coverage.raster.geom.RasterGeoReference.OriginLocation;
import org.deegree.coverage.raster.io.RasterIOOptions;
import org.deegree.cs.CRS;
import org.deegree.geometry.Envelope;

/**
 * The <code>RasterBuilder</code> recursively enters a given directory and creates a {@link TiledRaster} from found
 * image files.
 * 
 * @author <a href="mailto:bezema@lat-lon.de">Rutger Bezema</a>
 * @author last edited by: $Author$
 * @version $Revision$, $Date$
 * 
 */
public class RasterBuilder implements CoverageBuilder {

//    private final static String NS = "http://www.deegree.org/datasource/coverage/raster";
    private final static String NS = "http://www.deegree.org/datasource";

    private final static org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger( RasterBuilder.class );

    /**
     * Create a {@link MultiResolutionRaster} with the origin or the world coordinate of each raster file, defined by
     * the given {@link OriginLocation}
     * 
     * @param resolutionDirectory
     *            locating the different resolutions
     * @param recursive
     *            if the sub directories of the resolution directories should be scanned as well
     * @param options
     *            containing information on the loading of the raster data.
     * @return a {@link MultiResolutionRaster} filled with {@link TiledRaster}s or <code>null</code> if the
     *         resolutionDirectory is not a directory.
     */
    public MultiResolutionRaster buildMultiResolutionRaster( File resolutionDirectory, boolean recursive,
                                                             RasterIOOptions options ) {
        if ( !resolutionDirectory.isDirectory() ) {
            return null;
        }
        return buildMultiResolutionRaster( findResolutionDirs( resolutionDirectory ), recursive, options );
    }

    /**
     * Scan the given directory for top level directories ending with a resolution.
     * 
     * @param toplevelDir
     * @return a list of directories which can be used for the building of {@link TiledRaster}s.
     */
    private final static List<File> findResolutionDirs( File toplevelDir ) {
        List<File> result = new LinkedList<File>();
        for ( File f : toplevelDir.listFiles() ) {
            if ( f.isDirectory() ) {
                double res = RasterBuilder.getPixelResolution( null, f );
                if ( !Double.isNaN( res ) ) {
                    result.add( f );
                } else {
                    LOG.info( "Directory: "
                              + f.getAbsolutePath()
                              + " can not be added to a Multiresolution raster, because it does not denote a resolution." );
                }
            }
        }
        return result;
    }

    /**
     * @param resolutionDirectories
     *            locating the different resolutions
     * @param recursive
     *            if the sub directories of the resolution directories should be scanned as well
     * @param options
     *            containing values for the loading of the raster data.
     * @return a {@link MultiResolutionRaster} filled with {@link TiledRaster}s
     */
    private MultiResolutionRaster buildMultiResolutionRaster( List<File> resolutionDirectories, boolean recursive,
                                                              RasterIOOptions options ) {
        MultiResolutionRaster mrr = new MultiResolutionRaster();
        for ( File resDir : resolutionDirectories ) {
            if ( resDir != null && resDir.isDirectory() ) {
                AbstractRaster rasterLevel = buildTiledRaster( resDir, recursive, options );
                if ( rasterLevel != null ) {
                    mrr.addRaster( rasterLevel );
                }
            }
        }
        return mrr;
    }

    /* (non-Javadoc)
     * @see org.deegree.coverage.raster.utils.CoverageBuilder#getConfigNamespace()
     */
    public String getConfigNamespace() {
        return NS;
    }

    /* (non-Javadoc)
     * @see org.deegree.coverage.raster.utils.CoverageBuilder#buildCoverage(java.net.URL)
     */
    public AbstractCoverage buildCoverage( URL configURL )
                            throws IOException {
        try {
            JAXBContext jc = JAXBContext.newInstance( "org.deegree.commons.datasource.configuration" );
            Unmarshaller u = jc.createUnmarshaller();
            Object config = ( (JAXBElement<?>) u.unmarshal( configURL ) ).getValue();

            XMLAdapter resolver = new XMLAdapter();
            resolver.setSystemId( configURL.toString() );

            if ( config instanceof MultiResolutionDataSource ) {
                return fromDatasource( (MultiResolutionDataSource) config, resolver );
            }
            if ( config instanceof RasterDataSource ) {
                return fromDatasource( (RasterDataSource) config, resolver );
            }
            LOG.warn( "An unknown object '{}' came out of JAXB parsing. This is probably a bug.", config.getClass() );
        } catch ( JAXBException e ) {
            LOG.warn( "Coverage datastore configuration from '{}' could not be read: '{}'.", configURL,
                      e.getLocalizedMessage() );
            LOG.trace( "Stack trace:", e );
        }
        return null;
    }
    
    /**
     * @param datasource
     * @param adapter
     * @return a corresponding raster
     */
    private MultiResolutionRaster fromDatasource( MultiResolutionDataSource datasource, XMLAdapter adapter ) {
        if ( datasource != null ) {
            String defCRS = datasource.getCrs();
            CRS crs = null;
            if ( defCRS != null ) {
                crs = new CRS( defCRS );
            }
            MultiResolutionRaster mrr = new MultiResolutionRaster();
            mrr.setCoordinateSystem( crs );
            for ( Resolution resolution : datasource.getResolution() ) {
                JAXBElement<? extends AbstractGeospatialDataSourceType> dsElement = resolution.getAbstractGeospatialDataSource();
                RasterDataSource ds = (RasterDataSource) dsElement.getValue();
                RasterFileSetType directory = ds.getRasterDirectory();
                File resolutionDir;
                try {
                    resolutionDir = new File( adapter.resolve( directory.getValue() ).getFile() );
                    RasterIOOptions options = new RasterIOOptions();
                    String fp = directory.getFilePattern();
                    if ( fp == null ) {
                        fp = "*";
                    }
                    if ( datasource.getOriginLocation() != null ) {
                        options.add( RasterIOOptions.GEO_ORIGIN_LOCATION,
                                     datasource.getOriginLocation().toString().toUpperCase() );
                    }
                    options.add( RasterIOOptions.OPT_FORMAT, fp );
                    if ( crs != null ) {
                        options.add( RasterIOOptions.CRS, crs.getName() );
                    }
                    AbstractRaster rasterLevel = buildTiledRaster( resolutionDir, directory.isRecursive(), options );
                    // double res = RasterBuilder.getPixelResolution( resolution.getRes(), resolutionDir );
                    mrr.addRaster( rasterLevel );
                } catch ( MalformedURLException e ) {
                    LOG.warn( "Could not resolve the file {}, corresponding data will not be available.",
                              directory.getValue() );
                }
            }
            return mrr;
        }
        throw new NullPointerException( "The configured multi resolution datasource may not be null." );

    }

    /**
     * @param datasource
     * @param adapter
     * @return a corresponding raster, null if files could not be fund
     */
    private AbstractRaster fromDatasource( RasterDataSource datasource, XMLAdapter adapter ) {
        if ( datasource != null ) {
            String defCRS = datasource.getCrs();
            CRS crs = null;
            if ( defCRS != null ) {
                crs = new CRS( defCRS );
            }
            RasterFileSetType directory = datasource.getRasterDirectory();
            RasterFileType file = datasource.getRasterFile();
            try {
                RasterIOOptions options = new RasterIOOptions();
                if ( datasource.getOriginLocation() != null ) {
                    options.add( RasterIOOptions.GEO_ORIGIN_LOCATION,
                                 datasource.getOriginLocation().toString().toUpperCase() );
                }
                if ( directory != null ) {
                    File rasterFiles = new File( adapter.resolve( directory.getValue() ).getFile() );
                    boolean recursive = directory.isRecursive() == null ? false : directory.isRecursive();
                    String fp = directory.getFilePattern();
                    if ( fp == null ) {
                        fp = "*";
                    }
                    options.add( RasterIOOptions.OPT_FORMAT, fp );
                    if ( crs != null ) {
                        options.add( RasterIOOptions.CRS, crs.getName() );
                    }
                    return buildTiledRaster( rasterFiles, recursive, options );
                }
                if ( file != null ) {
                    final File loc = new File( adapter.resolve( file.getValue() ).getFile() );
                    options.add( RasterIOOptions.OPT_FORMAT, file.getFileType() );
                    AbstractRaster raster = loadRasterFromFile( loc, options );
                    raster.setCoordinateSystem( crs );
                    return raster;
                }
            } catch ( MalformedURLException e ) {
                if ( directory != null ) {
                    LOG.warn( "Could not resolve the file {}, corresponding data will not be available.",
                              directory.getValue() );
                } else {
                    LOG.warn( "Could not resolve the file {}, corresponding data will not be available.",
                              file.getValue() );
                }
            } catch ( IOException e ) {
                LOG.warn( "Could not load the file {}, corresponding data will not be available.", file.getValue() );
            }
        }
        throw new NullPointerException( "The configured raster datasource may not be null." );
    }

    /**
     * Creates a coverage from the given raster location. Supported are loading from:
     * <ul>
     * <li>a raster file</li>
     * <li>a raster directory containing a tiled raster</li>
     * <li>a directory containing several sub directories named with doubles, containing different resolutions (a
     * multiresolution raster tree).</li>
     * </ul>
     * 
     * @param rasterLocation
     *            may be a raster file or a raster directory containing a tiled raster or several sub directories named
     *            with doubles, containing different resolutions (a multiresolution raster tree).
     * @param recursive
     *            if the directory should be searched recursively.
     * @param options
     *            containing configured values for the loading of the coverage.
     * @return an AbstractCoverage created from the given raster location. Result can be a {@link SimpleRaster} a
     *         {@link TiledRaster} or a {@link MultiResolutionRaster}.
     * @throws IOException
     *             if the raster location could not be read.
     */
    public AbstractCoverage buildCoverage( File rasterLocation, boolean recursive, RasterIOOptions options )
                            throws IOException {
        if ( rasterLocation == null ) {
            throw new IOException( "Raster location may not be null." );
        }
        if ( !rasterLocation.exists() ) {
            throw new IOException( "Raster location (" + rasterLocation + ") does not exist." );
        }
        if ( rasterLocation.isFile() ) {
            return RasterFactory.loadRasterFromFile( rasterLocation, options );
        }
        List<File> resolutions = findResolutionDirs( rasterLocation );
        if ( resolutions.isEmpty() ) {
            return buildTiledRaster( rasterLocation, recursive, options );
        }
        return buildMultiResolutionRaster( resolutions, recursive, options );

    }

    /**
     * Get the resolution from the resolution or if no value was configured try to get it from the name of the
     * directory.
     * 
     * @param resolution
     * @param resolutionDir
     * 
     * @return the resolution from the configuration if missing from the directory name, if not parse-able return NaN
     */
    private static double getPixelResolution( Double resolution, File resolutionDir ) {
        Double result = resolution;
        if ( result == null || result.isNaN() ) {
            File rasterDirectory = resolutionDir;
            String dirRes = FileUtils.getFilename( rasterDirectory );
            try {
                result = Double.parseDouble( dirRes );
            } catch ( NumberFormatException e ) {
                LOG.debug( "No resolution found in raster datasource defintion, nor in the directory name: " + dirRes
                           + " returning 0" );
                result = Double.NaN;
            }
        }
        return result;
    }

    /**
     * Scan the given directory (recursively) for files with given extension and create a tiled raster from them. The
     * tile raster will use an {@link IndexedMemoryTileContainer}. The options should define an
     * {@link RasterIOOptions#OPT_FORMAT} to be used as file extension which will be case insensitive extension of the
     * files to to scan for
     * 
     * 
     * @param directory
     * @param recursive
     *            if true sub directories will be scanned as well.
     * @param options
     *            containing information on the data
     * 
     * @return a new {@link TiledRaster} or <code>null</code> if no raster files were found at the given location, with
     *         the given extension.
     */
    private AbstractRaster buildTiledRaster( File directory, boolean recursive, RasterIOOptions options ) {
        LOG.info( "Scanning for files in directory: {}", directory.getAbsolutePath() );
        String extension = options.get( RasterIOOptions.OPT_FORMAT );
        List<File> coverageFiles = FileUtils.findFilesForExtensions( directory, recursive, extension );
        TiledRaster raster = null;
        if ( !coverageFiles.isEmpty() ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Found following files: \n{}", coverageFiles.toString() );
            }
            List<AbstractRaster> rasters = new ArrayList<AbstractRaster>( coverageFiles.size() );
            RasterIOOptions opts = new RasterIOOptions();
            opts.copyOf( options );
            String cacheDir = opts.get( RasterIOOptions.LOCAL_RASTER_CACHE_DIR );
            if ( cacheDir == null ) {
                String dir = directory.getName();
                if ( directory.getParentFile() != null ) {
                    dir = directory.getParentFile().getName() + "_" + directory.getName();
                }
                opts.add( RasterIOOptions.LOCAL_RASTER_CACHE_DIR, dir );
            }
            if ( opts.get( RasterIOOptions.CREATE_RASTER_MISSING_CACHE_DIR ) == null ) {
                opts.add( RasterIOOptions.CREATE_RASTER_MISSING_CACHE_DIR, "yes" );
            }
            QTreeInfo inf = buildTiledRaster( coverageFiles, rasters, opts );
            Envelope domain = inf.envelope;
            // RasterGeoReference rasterDomain = inf.rasterGeoReference;
            // IndexedMemoryTileContainer container = new IndexedMemoryTileContainer( domain, rasterDomain,
            // inf.numberOfObjects );
            MemoryTileContainer container = new MemoryTileContainer( rasters );
            raster = new TiledRaster( container );
            raster.setCoordinateSystem( domain.getCoordinateSystem() );
            // container.addRasterTiles( rasters );
        } else {
            LOG.warn( "No raster files with extension: {}, found in directory {}", extension,
                      directory.getAbsolutePath() );
        }
        return raster;
    }

    /**
     * 
     * @param coverageFiles
     *            to read
     * @param result
     *            will hold the resulting coverages.
     * @param options
     * @return the total envelope of the given coverages
     */
    private final static QTreeInfo buildTiledRaster( List<File> coverageFiles, List<AbstractRaster> result,
                                                     RasterIOOptions options ) {
        Envelope resultEnvelope = null;
        RasterGeoReference rasterReference = null;

        CRS crs = options == null ? null : options.getCRS();
        if ( crs == null ) {
            LOG.warn( "Configured crs is null, maybe the rasterfiles define one." );
        }
        CRS defaultCRS = crs;
        Envelope rasterEnvelope = null;
        for ( File filename : coverageFiles ) {
            try {
                LOG.info( "Creating raster from file: {}", filename );
                RasterIOOptions newOpts = RasterIOOptions.forFile( filename );
                newOpts.copyOf( options );
                AbstractRaster raster = RasterFactory.loadRasterFromFile( filename, newOpts );
                CRS rasterCRS = raster.getCoordinateSystem();
                if ( defaultCRS == null ) {
                    defaultCRS = rasterCRS;
                } else {
                    if ( rasterCRS != null ) {
                        if ( !rasterCRS.equals( defaultCRS ) ) {
                            LOG.warn( "Configured CRS was not compatible with CRS in files, replacing it." );
                            defaultCRS = rasterCRS;
                        }
                    }
                }
                if ( rasterEnvelope == null ) {
                    rasterEnvelope = raster.getEnvelope();
                }
                if ( defaultCRS != null && raster.getCoordinateSystem() == null ) {
                    raster.setCoordinateSystem( defaultCRS );
                }
                if ( resultEnvelope == null ) {
                    resultEnvelope = raster.getEnvelope();
                } else {
                    resultEnvelope = resultEnvelope.merge( raster.getEnvelope() );
                }
                if ( rasterReference == null ) {
                    rasterReference = raster.getRasterReference();
                } else {
                    rasterReference = RasterGeoReference.merger( rasterReference, raster.getRasterReference() );
                }
                result.add( raster );
            } catch ( IOException e ) {
                LOG.error( "unable to load raster, ignoring file ({}): {}", filename, e.getMessage() );
            }
        }
        int leafObjects = calcBalancedLeafObjectSize( rasterEnvelope, resultEnvelope, 4 );
        return new QTreeInfo( resultEnvelope, rasterReference, leafObjects );
    }

    /**
     * Calculate the approximate objects in a leaf node.
     * 
     * @param rasterEnvelope
     * @param resultEnvelope
     * @param size
     * @return
     */
    private static int calcBalancedLeafObjectSize( Envelope rasterEnvelope, Envelope resultEnvelope, int treeDepth ) {
        double tw = resultEnvelope.getSpan0();

        double rw = rasterEnvelope.getSpan0();

        double widthScale = Math.pow( 0.5, treeDepth-- );

        double leafSize = tw * widthScale;
        while ( leafSize < ( 5 * rw ) ) {
            widthScale = Math.pow( 0.5, treeDepth-- );
            leafSize = tw * widthScale;
        }

        return Math.max( 3, (int) Math.ceil( leafSize / rw ) );
    }

    private static class QTreeInfo {
        Envelope envelope;

        RasterGeoReference rasterGeoReference;

        int numberOfObjects;

        /**
         * @param envelope
         * @param rasterGeoReference
         * @param numberOfObjects
         */
        public QTreeInfo( Envelope envelope, RasterGeoReference rasterGeoReference, int numberOfObjects ) {
            this.envelope = envelope;
            this.rasterGeoReference = rasterGeoReference;
            this.numberOfObjects = numberOfObjects;
        }

    }

}
