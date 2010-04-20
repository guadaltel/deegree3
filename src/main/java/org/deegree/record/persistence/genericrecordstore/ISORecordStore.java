//$HeadURL$
/*----------------------------------------------------------------------------
 This file is part of deegree, http://deegree.org/
 Copyright (C) 2001-2009 by:
 - Department of Geography, University of Bonn -
 and
 - lat/lon GmbH -

 This library is free software; you can redistribute it and/or modify it under
 the terms of the GNU Lesser General Public License as published by the Free
 Software Foundation; either version 2.1 of the License, or (at your option)
 any later version.
 This library is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 details.
 You should have received a copy of the GNU Lesser General Public License
 along with this library; if not, write to the Free Software Foundation, Inc.,
 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA

 Contact information:

 lat/lon GmbH
 Aennchenstr. 19, 53177 Bonn
 Germany
 http://lat-lon.de/

 Department of Geography, University of Bonn
 Prof. Dr. Klaus Greve
 Postfach 1147, 53001 Bonn
 Germany
 http://www.geographie.uni-bonn.de/deegree/

 e-mail: info@deegree.org
 ----------------------------------------------------------------------------*/
package org.deegree.record.persistence.genericrecordstore;

import static org.deegree.protocol.csw.CSWConstants.APISO_NS;
import static org.deegree.protocol.csw.CSWConstants.APISO_PREFIX;
import static org.deegree.protocol.csw.CSWConstants.CSW_202_NS;
import static org.deegree.protocol.csw.CSWConstants.CSW_202_RECORD;
import static org.deegree.protocol.csw.CSWConstants.CSW_PREFIX;
import static org.deegree.protocol.csw.CSWConstants.DC_LOCAL_PART;
import static org.deegree.protocol.csw.CSWConstants.DC_NS;
import static org.deegree.protocol.csw.CSWConstants.GMD_LOCAL_PART;
import static org.deegree.protocol.csw.CSWConstants.GMD_NS;
import static org.deegree.protocol.csw.CSWConstants.GMD_PREFIX;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.namespace.QName;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.deegree.commons.jdbc.ConnectionManager;
import org.deegree.commons.utils.Pair;
import org.deegree.commons.utils.kvp.InvalidParameterValueException;
import org.deegree.commons.utils.time.DateUtils;
import org.deegree.commons.xml.XMLAdapter;
import org.deegree.filter.FilterEvaluationException;
import org.deegree.filter.OperatorFilter;
import org.deegree.filter.expression.Literal;
import org.deegree.filter.sql.postgis.PostGISWhereBuilder;
import org.deegree.filter.sql.postgis.PropertyNameMapping;
import org.deegree.protocol.csw.CSWConstants;
import org.deegree.protocol.csw.CSWConstants.ResultType;
import org.deegree.protocol.csw.CSWConstants.SetOfReturnableElements;
import org.deegree.record.persistence.RecordStore;
import org.deegree.record.persistence.RecordStoreException;
import org.deegree.record.persistence.RecordStoreOptions;
import org.deegree.record.persistence.genericrecordstore.parsing.ISOQPParsing;
import org.deegree.record.publication.DeleteTransaction;
import org.deegree.record.publication.InsertTransaction;
import org.deegree.record.publication.RecordProperty;
import org.deegree.record.publication.TransactionOperation;
import org.deegree.record.publication.TransactionOptions;
import org.deegree.record.publication.UpdateTransaction;
import org.slf4j.Logger;

/**
 * {@link RecordStore} implementation of Dublin Core and ISO Profile.
 * 
 * @author <a href="mailto:thomas@lat-lon.de">Steffen Thomas</a>
 * @author last edited by: $Author: thomas $
 * 
 * @version $Revision: $, $Date: $
 */
public class ISORecordStore implements RecordStore {

    private static final Logger LOG = getLogger( ISORecordStore.class );

    /**
     * registers the typeNames that are applicable to this recordStore and maps a typeName to a format, if it is DC or
     * ISO
     */
    private static Map<QName, Integer> typeNames = new HashMap<QName, Integer>();

    private final String connectionId;

    // if true, use old-style for spatial predicates (intersects instead of ST_Intersecs)
    private boolean useLegacyPredicates;

    /**
     * shows the encoding of the database that is used
     */
    private String encoding;

    /**
     * maps the specific returnable element format to a concrete table in the backend<br>
     * brief, summary, full
     */
    private static final Map<SetOfReturnableElements, String> formatTypeInISORecordStore = new HashMap<SetOfReturnableElements, String>();

    static {

        formatTypeInISORecordStore.put( SetOfReturnableElements.brief, "recordbrief" );
        formatTypeInISORecordStore.put( SetOfReturnableElements.summary, "recordsummary" );
        formatTypeInISORecordStore.put( SetOfReturnableElements.full, "recordfull" );

        // typeNames.put( new QName( "", "", "" ), 1 );
        typeNames.put( new QName( CSW_202_NS, DC_LOCAL_PART, "" ), 1 );
        typeNames.put( new QName( CSW_202_NS, DC_LOCAL_PART, CSW_PREFIX ), 1 );
        typeNames.put( new QName( DC_NS, "", "dc" ), 1 );
        typeNames.put( new QName( GMD_NS, GMD_LOCAL_PART, "" ), 2 );
        typeNames.put( new QName( GMD_NS, GMD_LOCAL_PART, GMD_PREFIX ), 2 );
        typeNames.put( new QName( APISO_NS, "", APISO_PREFIX ), 2 );

    }

    /**
     * Creates a new {@link ISORecordStore} instance with a registered connectionId.
     * 
     * @param _connectionId
     */
    public ISORecordStore( String _connectionId ) {
        this.connectionId = _connectionId;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.deegree.record.persistence.RecordStore#describeRecord(javax.xml.stream.XMLStreamWriter)
     */
    @Override
    public void describeRecord( XMLStreamWriter writer, QName typeName ) {
        try {

            BufferedInputStream bais;
            URLConnection urlConn = null;

            /*
             * if typeName is csw:Record
             */
            if ( typeName.equals( new QName( CSW_202_NS, DC_LOCAL_PART, CSW_PREFIX ) ) ) {

                urlConn = new URL( CSW_202_RECORD ).openConnection();

            }
            /*
             * if typeName is gmd:MD_Metadata
             */
            else if ( typeName.equals( new QName( GMD_NS, GMD_LOCAL_PART, GMD_PREFIX ) ) ) {

                urlConn = new URL( "http://www.isotc211.org/2005/gmd/identification.xsd" ).openConnection();

                writer.writeAttribute( "parentSchema", "http://www.isotc211.org/2005/gmd/gmd.xsd" );

            }
            /*
             * if the typeName is no registered in this recordprofile
             */
            else {
                String errorMessage = "The typeName " + typeName + "is not supported by this profile. ";
                LOG.debug( errorMessage );
                throw new IllegalArgumentException( errorMessage );
            }

            urlConn.setDoInput( true );
            bais = new BufferedInputStream( urlConn.getInputStream() );

            Charset charset = encoding == null ? Charset.defaultCharset() : Charset.forName( encoding );
            InputStreamReader isr = new InputStreamReader( bais, charset );

            readXMLFragment( isr, writer );

        } catch ( MalformedURLException e ) {

            LOG.debug( "error: " + e.getMessage(), e );
        } catch ( IOException e ) {

            LOG.debug( "error: " + e.getMessage(), e );
        } catch ( Exception e ) {

            LOG.debug( "error: " + e.getMessage(), e );
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.deegree.record.persistence.RecordStore#destroy()
     */
    @Override
    public void destroy() {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.deegree.record.persistence.RecordStore#init()
     */
    @Override
    public void init()
                            throws RecordStoreException {

        LOG.debug( "init" );
        // lockManager = new DefaultLockManager( this, "LOCK_DB" );

        Connection conn = null;
        try {
            conn = ConnectionManager.getConnection( connectionId );

            encoding = determinePostGRESEncoding( conn );

            String version = determinePostGISVersion( conn );
            if ( version.startsWith( "0." ) || version.startsWith( "1.0" ) || version.startsWith( "1.1" )
                 || version.startsWith( "1.2" ) ) {
                LOG.debug( "PostGIS version is " + version + " -- using legacy (pre-SQL-MM) predicates." );
                useLegacyPredicates = true;
            } else {
                LOG.debug( "PostGIS version is " + version + " -- using modern (SQL-MM) predicates." );
            }
        } catch ( SQLException e ) {

            e.printStackTrace();
        }

    }

    /**
     * @param conn
     * @return the encoding of the PostGRES database.
     */
    private String determinePostGRESEncoding( Connection conn ) {
        String encodingPostGRES = "UTF-8";
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = conn.createStatement();
            rs = stmt.executeQuery( "SHOW server_encoding" );
            rs.next();
            encodingPostGRES = rs.getString( 1 );
            LOG.debug( "PostGRES encoding: " + encodingPostGRES );
            stmt.close();
            rs.close();
        } catch ( Exception e ) {
            LOG.warn( "Could not determine PostGRES encoding: " + e.getMessage() + " -- defaulting to UTF-8" );
            closeSafely( null, stmt, rs );
        }

        return encodingPostGRES;
    }

    private String determinePostGISVersion( Connection conn ) {
        String version = "1.0";
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = conn.createStatement();
            rs = stmt.executeQuery( "SELECT postgis_version()" );
            rs.next();
            String postGISVersion = rs.getString( 1 );
            version = postGISVersion.split( " " )[0];
            LOG.debug( "PostGIS version: " + version );
            stmt.close();
            rs.close();
        } catch ( Exception e ) {
            LOG.warn( "Could not determine PostGIS version: " + e.getMessage() + " -- defaulting to 1.0.0" );
            closeSafely( null, stmt, rs );
        }
        return version;
    }

    private void closeSafely( Connection conn, Statement stmt, ResultSet rs ) {
        if ( rs != null ) {
            try {
                rs.close();
            } catch ( SQLException e ) {
                LOG.warn( e.getMessage(), e );
            }
        }
        if ( stmt != null ) {
            try {
                stmt.close();
            } catch ( SQLException e ) {
                LOG.warn( e.getMessage(), e );
            }
        }
        if ( conn != null ) {
            try {
                conn.close();
            } catch ( SQLException e ) {
                LOG.warn( e.getMessage(), e );
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.deegree.record.persistence.RecordStore#getTypeNames()
     */
    @Override
    public Map<QName, Integer> getTypeNames() {

        return typeNames;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.deegree.record.persistence.RecordStore#getRecords(javax.xml.stream.XMLStreamWriter,
     * javax.xml.namespace.QName)
     */
    @Override
    public void getRecords( XMLStreamWriter writer, QName typeName, URI outputSchema,
                            RecordStoreOptions recordStoreOptions )
                            throws SQLException, XMLStreamException, IOException {

        PostGISMappingsISODC mapping = new PostGISMappingsISODC();
        PostGISWhereBuilder builder = null;

        // TODO sortProperty
        try {
            builder = new PostGISWhereBuilder( mapping, (OperatorFilter) recordStoreOptions.getFilter(), null,
                                               useLegacyPredicates );
        } catch ( FilterEvaluationException e ) {

            e.printStackTrace();
        }

        int profileFormatNumberOutputSchema = 0;
        int typeNameFormatNumber = 0;

        if ( typeNames.containsKey( typeName ) ) {
            typeNameFormatNumber = typeNames.get( typeName );
        }

        if ( !typeName.getNamespaceURI().equals( outputSchema.toString() ) ) {
            for ( QName qName : typeNames.keySet() ) {
                if ( qName.getNamespaceURI().equals( outputSchema.toString() ) ) {
                    profileFormatNumberOutputSchema = typeNames.get( qName );
                }
            }
        }

        switch ( recordStoreOptions.getResultType() ) {
        case results:

            doResultsOnGetRecord( writer, typeName, profileFormatNumberOutputSchema, recordStoreOptions, builder );
            break;
        case hits:

            doHitsOnGetRecord( writer, typeNameFormatNumber, profileFormatNumberOutputSchema, recordStoreOptions,
                               formatTypeInISORecordStore.get( recordStoreOptions.getSetOfReturnableElements() ),
                               ResultType.hits, builder, new ArrayList<Pair<StringBuilder, Collection<Object>>>( 0 ) );
            break;

        case validate:
            // TODO
        }

    }

    /**
     * The mandatory "resultType" attribute in the GetRecords operation is set to "hits".
     * 
     * @param writer
     *            - the XMLStreamWriter
     * @param typeName
     *            - the requested typeName
     * @param profileFormatNumberOutputSchema
     *            - the format number of the outputSchema
     * @param propertyAttributes
     *            - the properties that are identified by the request
     * @param con
     *            - the JDBCConnection
     * @throws SQLException
     * @throws XMLStreamException
     * @throws IOException
     */
    private void doHitsOnGetRecord( XMLStreamWriter writer, int typeNameFormatNumber,
                                    int profileFormatNumberOutputSchema, RecordStoreOptions recordStoreOptions,
                                    String formatType, ResultType resultType, PostGISWhereBuilder builder,
                                    List<Pair<StringBuilder, Collection<Object>>> wList )
                            throws SQLException, XMLStreamException, IOException {

        int countRows = 0;
        int nextRecord = 0;
        int returnedRecords = 0;
        Connection conn = ConnectionManager.getConnection( connectionId );

        PreparedStatement ps = combinePreparedStatement( wList, recordStoreOptions, conn, true, builder );

        ResultSet rs = ps.executeQuery();

        while ( rs.next() ) {
            countRows = rs.getInt( 1 );
            LOG.debug( "rs for rowCount: " + rs.getInt( 1 ) );
        }

        if ( resultType.equals( ResultType.hits ) ) {
            writer.writeAttribute( "elementSet", recordStoreOptions.getSetOfReturnableElements().name() );

            // writer.writeAttribute( "recordSchema", "");

            writer.writeAttribute( "numberOfRecordsMatched", Integer.toString( countRows ) );

            writer.writeAttribute( "numberOfRecordsReturned", Integer.toString( 0 ) );

            writer.writeAttribute( "nextRecord", Integer.toString( 1 ) );

            writer.writeAttribute( "expires", DateUtils.formatISO8601Date( new Date() ) );
        } else {

            if ( countRows > recordStoreOptions.getMaxRecords() ) {
                nextRecord = recordStoreOptions.getMaxRecords() + 1;
                returnedRecords = recordStoreOptions.getMaxRecords();
            } else {
                nextRecord = 0;
                returnedRecords = countRows - recordStoreOptions.getStartPosition() + 1;
            }

            writer.writeAttribute( "elementSet", recordStoreOptions.getSetOfReturnableElements().name() );

            // writer.writeAttribute( "recordSchema", "");

            writer.writeAttribute( "numberOfRecordsMatched", Integer.toString( countRows ) );

            writer.writeAttribute( "numberOfRecordsReturned", Integer.toString( returnedRecords ) );

            writer.writeAttribute( "nextRecord", Integer.toString( nextRecord ) );

            writer.writeAttribute( "expires", DateUtils.formatISO8601Date( new Date() ) );
        }
        ps.close();
        rs.close();
        conn.close();

    }

    /**
     * The mandatory "resultType" attribute in the GetRecords operation is set to "results".
     * 
     * @param writer
     *            - the XMLStreamWriter
     * @param typeName
     *            - the requested typeName
     * @param profileFormatNumberOutputSchema
     *            - the format number of the outputSchema
     * @param recordStoreOptions
     *            - the properties that are identified by the request
     * @param con
     *            - the JDBCConnection
     * @throws SQLException
     * @throws XMLStreamException
     * @throws IOException
     */
    private void doResultsOnGetRecord( XMLStreamWriter writer, QName typeName, int profileFormatNumberOutputSchema,
                                       RecordStoreOptions recordStoreOptions, PostGISWhereBuilder builder )
                            throws SQLException, XMLStreamException, IOException {
        int typeNameFormatNumber = 0;
        if ( typeNames.containsKey( typeName ) ) {
            typeNameFormatNumber = typeNames.get( typeName );
        }

        Connection conn = ConnectionManager.getConnection( connectionId );

        ResultSet rs = null;
        PreparedStatement preparedStatement = null;
        List<Pair<StringBuilder, Collection<Object>>> preparedStatementList = new ArrayList<Pair<StringBuilder, Collection<Object>>>();

        List<Pair<StringBuilder, Collection<Object>>> whereClauseList = new GenerateWhereClauseList(
                                                                                                     builder.getWhereClause(),
                                                                                                     builder.getWhereParams() ).generateList();

        switch ( recordStoreOptions.getSetOfReturnableElements() ) {

        case brief:
            for ( Pair<StringBuilder, Collection<Object>> whereBuilderPair : whereClauseList ) {
                Pair<StringBuilder, Collection<Object>> selectBrief = generateSELECTStatement(
                                                                                               formatTypeInISORecordStore.get( CSWConstants.SetOfReturnableElements.brief ),
                                                                                               recordStoreOptions,
                                                                                               typeNameFormatNumber,
                                                                                               profileFormatNumberOutputSchema,
                                                                                               false, builder,
                                                                                               whereBuilderPair );

                preparedStatementList.add( selectBrief );

            }
            doHitsOnGetRecord( writer, typeNameFormatNumber, profileFormatNumberOutputSchema, recordStoreOptions,
                               formatTypeInISORecordStore.get( CSWConstants.SetOfReturnableElements.brief ),
                               ResultType.results, builder, preparedStatementList );
            break;
        case summary:

            for ( Pair<StringBuilder, Collection<Object>> whereBuilderPair : whereClauseList ) {
                Pair<StringBuilder, Collection<Object>> selectSummary = generateSELECTStatement(
                                                                                                 formatTypeInISORecordStore.get( CSWConstants.SetOfReturnableElements.summary ),
                                                                                                 recordStoreOptions,
                                                                                                 typeNameFormatNumber,
                                                                                                 profileFormatNumberOutputSchema,
                                                                                                 false, builder,
                                                                                                 whereBuilderPair );

                preparedStatementList.add( selectSummary );

            }

            doHitsOnGetRecord( writer, typeNameFormatNumber, profileFormatNumberOutputSchema, recordStoreOptions,
                               formatTypeInISORecordStore.get( CSWConstants.SetOfReturnableElements.summary ),
                               ResultType.results, builder, preparedStatementList );
            break;
        case full:

            for ( Pair<StringBuilder, Collection<Object>> whereBuilderPair : whereClauseList ) {
                Pair<StringBuilder, Collection<Object>> selectFull = generateSELECTStatement(
                                                                                              formatTypeInISORecordStore.get( CSWConstants.SetOfReturnableElements.full ),
                                                                                              recordStoreOptions,
                                                                                              typeNameFormatNumber,
                                                                                              profileFormatNumberOutputSchema,
                                                                                              false, builder,
                                                                                              whereBuilderPair );

                preparedStatementList.add( selectFull );

            }

            doHitsOnGetRecord( writer, typeNameFormatNumber, profileFormatNumberOutputSchema, recordStoreOptions,
                               formatTypeInISORecordStore.get( CSWConstants.SetOfReturnableElements.full ),
                               ResultType.results, builder, preparedStatementList );
            break;
        }

        preparedStatement = combinePreparedStatement( preparedStatementList, recordStoreOptions, conn, false, builder );

        rs = preparedStatement.executeQuery();

        if ( rs != null && recordStoreOptions.getMaxRecords() != 0 ) {
            writeResultSet( rs, writer );
            rs.close();
        }

        conn.close();

    }

    /**
     * Combines the SQL-statements with a UNION operation and is responsible for counting the returned rows.
     * 
     * @param preparedStatementList
     * @param recordStoreOptions
     * @param conn
     * @param setCount
     * @param builder
     * @return
     * @throws SQLException
     */
    private PreparedStatement combinePreparedStatement(
                                                        List<Pair<StringBuilder, Collection<Object>>> preparedStatementList,
                                                        RecordStoreOptions recordStoreOptions, Connection conn,
                                                        boolean setCount, PostGISWhereBuilder builder )
                            throws SQLException {
        StringBuilder COUNT_PRE;
        StringBuilder COUNT_SUF;
        StringBuilder SET_OFFSET;
        PreparedStatement stmt = null;

        StringBuilder s = new StringBuilder();

        /*
         * precondition if there is a counting of rows needed
         */
        if ( setCount == true ) {
            COUNT_PRE = new StringBuilder().append( "SELECT COUNT(*) FROM ( " );
            COUNT_SUF = new StringBuilder().append( " ) AS rowCount" );
            SET_OFFSET = new StringBuilder();
        } else {
            COUNT_PRE = new StringBuilder();
            COUNT_SUF = new StringBuilder();
            SET_OFFSET = new StringBuilder().append( " OFFSET " ).append(
                                                                          Integer.toString( recordStoreOptions.getStartPosition() - 1 ) );
            SET_OFFSET.append( " LIMIT " ).append( recordStoreOptions.getMaxRecords() );
        }
        s.append( COUNT_PRE );
        for ( int i = 0; i < preparedStatementList.size(); i++ ) {
            if ( i == preparedStatementList.size() - 1 ) {
                s.append( preparedStatementList.get( i ).first.toString() );
                if ( recordStoreOptions.getMaxRecords() != 0 ) {
                    s.append( ' ' ).append( SET_OFFSET );
                }
            } else {
                s.append( preparedStatementList.get( i ).first.toString() );
                s.append( " UNION " );
            }

        }
        s.append( COUNT_SUF );
        LOG.info( "statement: " + s );
        stmt = conn.prepareStatement( s.toString() );

        /*
         * the parameter identified in the WHERE-builder replaces the "?" in the statement
         */
        if ( builder != null && preparedStatementList.size() > 0 ) {
            int i = 0;

            for ( Pair<StringBuilder, Collection<Object>> pair : preparedStatementList )
                for ( Object arg : (Collection<Object>) pair.second ) {
                    i++;

                    LOG.debug( "Setting argument: " + arg );
                    stmt.setObject( i, arg );

                }
        }

        LOG.info( "rs: " + stmt );

        return stmt;
    }

    /**
     * Selectstatement for the constrainted tables.
     * <p>
     * Realisation with AND
     * 
     * @param formatType
     *            - brief, summary or full
     * @param recordStoreOptions
     *            - properties that were requested
     * @param typeNameFormatNumber
     *            - the format number that is identified by the requested typeName
     * @param profileFormatNumberOutputSchema
     *            - the format number that is identified by the requested output schema
     * @param setCount
     *            - if the COUNT method should be in the statement
     * @return a Writer
     * @throws IOException
     * @throws SQLException
     */
    private Pair<StringBuilder, Collection<Object>> generateSELECTStatement(
                                                                             String formatType,
                                                                             RecordStoreOptions recordStoreOptions,
                                                                             int typeNameFormatNumber,
                                                                             int profileFormatNumberOutputSchema,
                                                                             boolean setCount,
                                                                             PostGISWhereBuilder builder,
                                                                             Pair<StringBuilder, Collection<Object>> whereBuilder )
                            throws IOException, SQLException {

        Pair<StringBuilder, Collection<Object>> pair = new Pair<StringBuilder, Collection<Object>>();
        StringBuilder s = new StringBuilder();
        StringBuilder constraintExpression = new StringBuilder();
        String constraintExpressionTemp = "";
        List<Pair<String, String>> aliasMapping = new ArrayList<Pair<String, String>>();
        String formatTypeAlias = formatType + Integer.toString( 0 );
        String datasetsAlias = "";

        StringBuilder stringINNER_FROM = new StringBuilder();

        /*
         * appends the tables identified in the WHERE-builder to the FROM clause with aliasnames
         */
        aliasMapping.add( new Pair<String, String>( formatType, formatTypeAlias ) );
        if ( builder != null && builder.getPropNameMappingList() != null ) {
            boolean containsDatasetsTable = false;
            int aliasCount = 1;
            // just look up the datasets table to build an aliasName
            for ( PropertyNameMapping propName : builder.getPropNameMappingList() ) {
                if ( propName.getTable().equals( PostGISMappingsISODC.DatabaseTables.datasets.name() ) ) {
                    datasetsAlias = PostGISMappingsISODC.DatabaseTables.datasets.name() + Integer.toString( aliasCount );
                    containsDatasetsTable = true;

                    aliasCount++;
                }
            }
            if ( containsDatasetsTable == false ) {
                datasetsAlias = PostGISMappingsISODC.DatabaseTables.datasets.name() + Integer.toString( aliasCount );
                aliasMapping.add( new Pair<String, String>( PostGISMappingsISODC.DatabaseTables.datasets.name(),
                                                            datasetsAlias ) );
                stringINNER_FROM.append( ',' ).append( PostGISMappingsISODC.DatabaseTables.datasets.name() );
                stringINNER_FROM.append( " AS " ).append( datasetsAlias );

                aliasCount++;
            }

            for ( PropertyNameMapping propName : builder.getPropNameMappingList() ) {
                if ( propName.getTable() == null ) {
                    stringINNER_FROM.append( ' ' );
                } else {
                    Pair<String, String> aliasPair = null;
                    if ( propName.getTable().equals( PostGISMappingsISODC.DatabaseTables.datasets.name() ) ) {
                        datasetsAlias = PostGISMappingsISODC.DatabaseTables.datasets.name()
                                        + Integer.toString( aliasCount );
                        aliasPair = new Pair<String, String>( PostGISMappingsISODC.DatabaseTables.datasets.name(),
                                                              datasetsAlias );
                        aliasMapping.add( aliasPair );
                        stringINNER_FROM.append( ',' ).append( PostGISMappingsISODC.DatabaseTables.datasets.name() );
                        stringINNER_FROM.append( " AS " ).append( datasetsAlias );
                        Pattern p = Pattern.compile( aliasPair.first.toString() + "[.]" );
                        Matcher m = p.matcher( (CharSequence) whereBuilder.first );

                        constraintExpressionTemp = m.replaceFirst( aliasPair.second.toString() + "." );
                        aliasCount++;
                        ( (StringBuilder) whereBuilder.first ).delete(
                                                                       0,
                                                                       ( (StringBuilder) whereBuilder.first ).capacity() );
                        ( (StringBuilder) whereBuilder.first ).append( constraintExpressionTemp );

                    } else {
                        aliasPair = new Pair<String, String>( propName.getTable(), propName.getTable()
                                                                                   + Integer.toString( aliasCount ) );

                        Pattern p = Pattern.compile( aliasPair.first.toString() + "[.]" );
                        Matcher m = p.matcher( (CharSequence) whereBuilder.first );

                        if ( m.find() ) {
                            stringINNER_FROM.append( ',' ).append( propName.getTable() ).append( " AS " ).append(
                                                                                                                  propName.getTable() );
                            stringINNER_FROM.append( Integer.toString( aliasCount ) );

                            aliasMapping.add( aliasPair );
                            constraintExpressionTemp = m.replaceFirst( aliasPair.second.toString() + "." );
                            aliasCount++;
                            ( (StringBuilder) whereBuilder.first ).delete(
                                                                           0,
                                                                           ( (StringBuilder) whereBuilder.first ).capacity() );
                            ( (StringBuilder) whereBuilder.first ).append( constraintExpressionTemp );
                        }

                    }

                }

            }

        }

        LOG.debug( "wherebuilder: " + whereBuilder );
        /*
         * building a constraint expression from the WHERE-builder
         */
        if ( ( (StringBuilder) whereBuilder.first ).length() != 0 ) {
            constraintExpression.append( " AND (" + whereBuilder.first + ") " );
        } else {
            constraintExpression.append( ' ' );
        }

        s.append( "SELECT " ).append( formatTypeAlias ).append( '.' );
        s.append( PostGISMappingsISODC.CommonColumnNames.data.name() ).append( " FROM " );
        s.append( formatType ).append( " AS " ).append( formatTypeAlias );

        s.append( stringINNER_FROM );

        s.append( " WHERE " ).append( formatTypeAlias ).append( '.' );
        s.append( PostGISMappingsISODC.CommonColumnNames.format.name() ).append( '=' );
        s.append( typeNameFormatNumber ).append( " AND " ).append( formatTypeAlias ).append( '.' );
        s.append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() ).append( '=' ).append( datasetsAlias );
        s.append( '.' ).append( PostGISMappingsISODC.CommonColumnNames.id.name() ).append( ' ' );

        /*
         * appends the tables with their columns identified in the WHERE-builder to the WHERE clause and binds it to the
         * maindatabasetable
         */
        if ( builder != null && ( (StringBuilder) whereBuilder.first ).length() != 0 ) {

            for ( Pair<String, String> pair1 : aliasMapping ) {
                if ( !pair1.first.equals( PostGISMappingsISODC.DatabaseTables.datasets.name() ) ) {
                    s.append( " AND " ).append( pair1.second ).append( '.' );
                    s.append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() ).append( '=' );
                    s.append( datasetsAlias ).append( '.' );
                    s.append( PostGISMappingsISODC.CommonColumnNames.id.name() ).append( ' ' );
                }
            }

        } else {
            s.append( ' ' );
        }

        /*
         * appends the constraint expression from the WHERE-builder and puts the Writer into a pair object
         */
        s.append( constraintExpression );
        pair.first = s;
        pair.second = (Collection<Object>) whereBuilder.second;
        return pair;

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.deegree.record.persistence.RecordStore#transaction(javax.xml.stream.XMLStreamWriter,
     * org.deegree.commons.configuration.JDBCConnections, java.util.List)
     */

    @Override
    public List<Integer> transaction( XMLStreamWriter writer, TransactionOperation operations,
                                      TransactionOptions options )
                            throws SQLException, XMLStreamException {

        List<Integer> affectedIds = new ArrayList<Integer>();
        Connection conn = ConnectionManager.getConnection( connectionId );

        switch ( operations.getType() ) {
        case INSERT:
            InsertTransaction ins = (InsertTransaction) operations;

            for ( OMElement element : ins.getElement() ) {
                QName localName = element.getQName();

                try {

                    ExecuteStatements executeStatements = new ExecuteStatements();

                    if ( localName.equals( new QName( CSW_202_NS, "Record", CSW_PREFIX ) )
                         || localName.equals( new QName( CSW_202_NS, "Record", "" ) ) ) {

                        executeStatements.executeInsertStatement( true, conn, affectedIds,
                                                                  new ISOQPParsing().parseAPDC( element ) );

                    } else {

                        executeStatements.executeInsertStatement( false, conn, affectedIds,
                                                                  new ISOQPParsing().parseAPISO( element,
                                                                                                 options.isInspire(),
                                                                                                 conn ) );

                    }

                } catch ( IOException e ) {

                    LOG.debug( "error: " + e.getMessage(), e );
                }

            }
            break;

        /*
         * There is a known BUG here. If you update one complete record, there is no problem. If you update just some
         * properties, multiple properties like "keywords" are not correctly updated. Have a look at {@link
         * #recursiveElementKnotUpdate}
         */
        case UPDATE:

            UpdateTransaction upd = (UpdateTransaction) operations;
            /**
             * if there should a complete record be updated or some properties
             */

            if ( upd.getElement() != null ) {
                try {
                    QName localName = upd.getElement().getQName();

                    ExecuteStatements executeStatements = new ExecuteStatements();

                    if ( localName.equals( new QName( CSW_202_NS, "Record", CSW_PREFIX ) )
                         || localName.equals( new QName( CSW_202_NS, "Record", "" ) ) ) {

                        executeStatements.executeUpdateStatement( conn, affectedIds,
                                                                  new ISOQPParsing().parseAPDC( upd.getElement() ) );

                    } else {
                        executeStatements.executeUpdateStatement( conn, affectedIds,
                                                                  new ISOQPParsing().parseAPISO( upd.getElement(),
                                                                                                 options.isInspire(),
                                                                                                 conn ) );

                    }

                } catch ( IOException e ) {

                    LOG.debug( "error: " + e.getMessage(), e );
                }
            } else {

                try {

                    RecordStoreOptions gdds = new RecordStoreOptions( upd.getConstraint(), ResultType.results,
                                                                      SetOfReturnableElements.full );

                    int formatNumber = 0;
                    Set<QName> qNameSet = new HashSet<QName>();

                    PostGISMappingsISODC mapping = new PostGISMappingsISODC();

                    // TODO sortProperty

                    PostGISWhereBuilder builder = new PostGISWhereBuilder( mapping,
                                                                           (OperatorFilter) upd.getConstraint(), null,
                                                                           useLegacyPredicates );

                    for ( QName propName : mapping.getPropToTableAndCol().keySet() ) {
                        String nsURI = propName.getNamespaceURI();
                        String prefix = propName.getPrefix();
                        QName analysedQName = new QName( nsURI, "", prefix );
                        qNameSet.add( analysedQName );
                    }

                    // if ( qNameSet.size() > 1 ) {
                    // String message =
                    // "There are different kinds of RecordStores affected by the request! Please decide on just one of the requested ones: ";
                    // int i = 0;
                    // for ( QName qNameError : qNameSet ) {
                    // i++;
                    // message += i + ". " + qNameError.toString() + " ";
                    // }
                    //
                    // throw new IllegalArgumentException( message );
                    // }

                    for ( QName qName : typeNames.keySet() ) {
                        if ( qName.equals( qNameSet.iterator().next() ) ) {
                            formatNumber = typeNames.get( qName );
                        }
                    }

                    PreparedStatement str = getRequestedIDStatement(
                                                                     formatTypeInISORecordStore.get( SetOfReturnableElements.full ),
                                                                     gdds, formatNumber, builder, conn );

                    ResultSet rsUpdatableDatasets = str.executeQuery();
                    List<Integer> updatableDatasets = new ArrayList<Integer>();
                    while ( rsUpdatableDatasets.next() ) {
                        updatableDatasets.add( rsUpdatableDatasets.getInt( 1 ) );

                    }
                    str.close();
                    rsUpdatableDatasets.close();

                    if ( updatableDatasets.size() != 0 ) {
                        PreparedStatement stmt = null;
                        StringBuilder stringBuilder = new StringBuilder();
                        stringBuilder.append( "SELECT " ).append(
                                                                  formatTypeInISORecordStore.get( SetOfReturnableElements.full ) );
                        stringBuilder.append( '.' ).append( PostGISMappingsISODC.CommonColumnNames.data.name() );
                        stringBuilder.append( " FROM " ).append(
                                                                 formatTypeInISORecordStore.get( SetOfReturnableElements.full ) );
                        stringBuilder.append( " WHERE " ).append(
                                                                  formatTypeInISORecordStore.get( SetOfReturnableElements.full ) );
                        stringBuilder.append( '.' ).append( PostGISMappingsISODC.CommonColumnNames.format.name() );
                        stringBuilder.append( " = 2 AND " ).append(
                                                                    formatTypeInISORecordStore.get( SetOfReturnableElements.full ) );
                        stringBuilder.append( '.' ).append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() ).append(
                                                                                                                                " = ?;" );
                        for ( int i : updatableDatasets ) {

                            stmt = conn.prepareStatement( stringBuilder.toString() );
                            stmt.setObject( 1, i );
                            ResultSet rsGetStoredFullRecordXML = stmt.executeQuery();

                            while ( rsGetStoredFullRecordXML.next() ) {
                                for ( RecordProperty recProp : upd.getRecordProperty() ) {

                                    PropertyNameMapping propMapping = mapping.getMapping( recProp.getPropertyName() );

                                    Object obje = mapping.getPostGISValue( (Literal<?>) recProp.getReplacementValue(),
                                                                           recProp.getPropertyName() );

                                    // creating an OMElement read from backend byteData
                                    InputStream in = rsGetStoredFullRecordXML.getBinaryStream( 1 );
                                    XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader( in );

                                    OMElement elementBuiltFromDB = new StAXOMBuilder( reader ).getDocument().getOMDocumentElement();

                                    OMElement omElement = recursiveElementKnotUpdate(
                                                                                      elementBuiltFromDB,
                                                                                      elementBuiltFromDB.getChildElements(),
                                                                                      propMapping.getColumn(),
                                                                                      obje.toString() );

                                    try {
                                        QName localName = omElement.getQName();

                                        ExecuteStatements executeStatements = new ExecuteStatements();

                                        if ( localName.equals( new QName( CSW_202_NS, "Record", CSW_PREFIX ) )
                                             || localName.equals( new QName( CSW_202_NS, "Record", "" ) ) ) {

                                            executeStatements.executeUpdateStatement(
                                                                                      conn,
                                                                                      affectedIds,
                                                                                      new ISOQPParsing().parseAPDC( omElement ) );

                                        } else {

                                            executeStatements.executeUpdateStatement(
                                                                                      conn,
                                                                                      affectedIds,
                                                                                      new ISOQPParsing().parseAPISO(
                                                                                                                     omElement,
                                                                                                                     options.isInspire(),
                                                                                                                     conn ) );

                                        }

                                    } catch ( IOException e ) {

                                        LOG.debug( "error: " + e.getMessage(), e );
                                    }

                                }
                            }
                            stmt.close();
                            rsGetStoredFullRecordXML.close();

                        }
                    }

                } catch ( IOException e ) {

                    LOG.debug( "error: " + e.getMessage(), e );
                } catch ( FilterEvaluationException e ) {
                    e.printStackTrace();
                } catch ( NullPointerException e ) {
                    e.printStackTrace();
                }
            }

            break;

        case DELETE:

            DeleteTransaction delete = (DeleteTransaction) operations;

            int formatNumber = 0;

            Set<QName> qNameSet = new HashSet<QName>();
            PostGISMappingsISODC mapping = new PostGISMappingsISODC();
            PostGISWhereBuilder builder = null;

            // TODO sortProperty
            try {
                builder = new PostGISWhereBuilder( mapping, (OperatorFilter) delete.getConstraint(), null,
                                                   useLegacyPredicates );

                for ( QName propName : mapping.getPropToTableAndCol().keySet() ) {
                    LOG.info( propName.toString() );
                    String nsURI = propName.getNamespaceURI();
                    String prefix = propName.getPrefix();
                    QName analysedQName = new QName( nsURI, "", prefix );
                    qNameSet.add( analysedQName );
                }
            } catch ( FilterEvaluationException e ) {

                e.printStackTrace();
            }

            // if ( qNameSet.size() > 1 ) {
            // String message =
            // "There are different kinds of RecordStores affected by the request! Please decide on just one of the requested ones: ";
            // for ( QName qNameError : qNameSet ) {
            // message += qNameError.toString();
            // }
            //
            // throw new IllegalArgumentException( message );
            // }

            Iterator<QName> delIter = qNameSet.iterator();

            for ( QName qName : typeNames.keySet() ) {
                QName qname = (QName) delIter.next();
                if ( qName.equals( qname ) ) {
                    formatNumber = typeNames.get( qName );
                }
            }

            RecordStoreOptions gdds = new RecordStoreOptions( delete.getConstraint(), ResultType.results,
                                                              SetOfReturnableElements.full );
            PreparedStatement str = null;
            ResultSet rsDeletableDatasets = null;

            try {
                str = getRequestedIDStatement( formatTypeInISORecordStore.get( SetOfReturnableElements.full ), gdds,
                                               formatNumber, builder, conn );
                rsDeletableDatasets = str.executeQuery();
                str.close();
            } catch ( IOException e ) {

                LOG.debug( "error: " + e.getMessage(), e );
            }

            List<Integer> deletableDatasets = new ArrayList<Integer>();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append( "DELETE FROM " );
            stringBuilder.append( PostGISMappingsISODC.DatabaseTables.datasets.name() );
            stringBuilder.append( " WHERE " ).append( PostGISMappingsISODC.CommonColumnNames.id.name() );
            stringBuilder.append( " = ?" );
            PreparedStatement stmt = null;
            if ( rsDeletableDatasets != null ) {
                while ( rsDeletableDatasets.next() ) {
                    deletableDatasets.add( rsDeletableDatasets.getInt( 1 ) );

                }
                rsDeletableDatasets.close();

                for ( int i : deletableDatasets ) {

                    stmt = conn.prepareStatement( stringBuilder.toString() );
                    stmt.setObject( 1, i );
                    stmt.executeUpdate();

                }
            }

            if ( stmt != null ) {
                stmt.close();
            }
            affectedIds = deletableDatasets;

            break;
        }
        conn.close();

        return affectedIds;
    }

    /**
     * This method replaces the text content of an elementknot.
     * <p>
     * TODO this is suitable for updates which affect an elementknot that has just one child. <br>
     * BUG - if there a more childs like in the "keyword"-elementknot.
     * 
     * @param element
     *            where to start in the OMTree
     * @param childElements
     *            as an Iterator above all the childElements of the element
     * @param searchForLocalName
     *            is the name that is searched for. This is the elementknot thats content should be updated.
     * @param newContent
     *            is the new content that should be updated
     * @return OMElement
     */
    private OMElement recursiveElementKnotUpdate( OMElement element, Iterator childElements, String searchForLocalName,
                                                  String newContent ) {

        Iterator it = element.getChildrenWithLocalName( searchForLocalName );

        if ( it.hasNext() ) {
            OMElement u = null;
            while ( it.hasNext() ) {
                u = (OMElement) it.next();
                LOG.debug( "rec: " + u.toString() );
                u.getFirstElement().setText( newContent );
                LOG.debug( "rec2: " + u.toString() );
            }
            return element;

        }
        while ( childElements.hasNext() ) {
            OMElement elem = (OMElement) childElements.next();

            recursiveElementKnotUpdate( elem, elem.getChildElements(), searchForLocalName, newContent );

        }

        return element;

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.deegree.record.persistence.RecordStore#getRecordsById(javax.xml.stream.XMLStreamWriter,
     * org.deegree.commons.configuration.JDBCConnections, java.util.List)
     */
    @Override
    public void getRecordById( XMLStreamWriter writer, List<String> idList, URI outputSchema,
                               SetOfReturnableElements elementSetName )
                            throws SQLException {

        Connection conn = ConnectionManager.getConnection( connectionId );
        int profileFormatNumberOutputSchema = 0;

        for ( QName qName : typeNames.keySet() ) {
            if ( qName.getNamespaceURI().equals( outputSchema.toString() ) ) {
                profileFormatNumberOutputSchema = typeNames.get( qName );
            }
        }

        StringBuilder select = new StringBuilder().append( "SELECT ?." );
        select.append( PostGISMappingsISODC.CommonColumnNames.data.name() ).append( " FROM ? AS recordAlias, " );
        select.append( PostGISMappingsISODC.DatabaseTables.datasets.name() ).append( " AS ds, " );
        select.append( PostGISMappingsISODC.DatabaseTables.qp_identifier.name() ).append( " AS i WHERE recordAlias." );
        select.append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() ).append( " = ds." );
        select.append( PostGISMappingsISODC.CommonColumnNames.id.name() ).append( " AND i." );
        select.append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() ).append( " = ds." );
        select.append( PostGISMappingsISODC.CommonColumnNames.id.name() ).append( " AND i." );
        select.append( PostGISMappingsISODC.DatabaseTables.qp_identifier.name() ).append( " = ? AND recordAlias." );
        select.append( PostGISMappingsISODC.CommonColumnNames.format.name() ).append( " = ?;" );

        ResultSet rs = null;

        for ( String identifier : idList ) {
            PreparedStatement stmt = null;
            String elementSetNameString;
            switch ( elementSetName ) {

            case brief:

                elementSetNameString = formatTypeInISORecordStore.get( SetOfReturnableElements.brief );
                stmt = conn.prepareStatement( select.toString() );
                break;
            case summary:

                elementSetNameString = formatTypeInISORecordStore.get( SetOfReturnableElements.summary );
                stmt = conn.prepareStatement( select.toString() );
                break;
            case full:

                elementSetNameString = formatTypeInISORecordStore.get( SetOfReturnableElements.full );
                stmt = conn.prepareStatement( select.toString() );
                break;
            default:

                elementSetNameString = formatTypeInISORecordStore.get( SetOfReturnableElements.brief );
                stmt = conn.prepareStatement( select.toString() );
                break;
            }
            if ( stmt != null ) {

                stmt.setObject( 1, elementSetNameString );
                stmt.setObject( 2, identifier );
                stmt.setObject( 3, profileFormatNumberOutputSchema );

                rs = stmt.executeQuery();
                writeResultSet( rs, writer );
                stmt.close();
            }
        }
        if ( rs != null ) {

            rs.close();
        }
        conn.close();

    }

    /**
     * Prepares the statement to get all the central recordIDs for a statement.
     * 
     * @param formatType
     * @param constraint
     * @param formatNumber
     * @return
     * @throws IOException
     * @throws SQLException
     */
    private PreparedStatement getRequestedIDStatement( String formatType, RecordStoreOptions constraint,
                                                       int formatNumber, PostGISWhereBuilder builder, Connection conn )
                            throws IOException, SQLException {

        StringBuilder s = new StringBuilder();
        PreparedStatement stmt = null;
        StringBuilder constraintExpression = new StringBuilder();

        StringBuilder stringBuilder = builder.getWhereClause();

        if ( stringBuilder.length() != 0 ) {
            constraintExpression.append( " AND (" ).append( builder.getWhereClause() ).append( ')' );
        } else {
            constraintExpression.append( ' ' );
        }

        s.append( "SELECT " ).append( formatType ).append( '.' );
        s.append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() ).append( " FROM " );
        s.append( PostGISMappingsISODC.DatabaseTables.datasets.name() ).append( ',' ).append( formatType );

        for ( PropertyNameMapping propName : builder.getPropNameMappingList() ) {
            if ( propName.getTable() == null ) {
                s.append( ' ' );
            } else {
                s.append( ',' ).append( propName.getTable() ).append( ' ' );
            }
        }

        s.append( " WHERE " ).append( formatType ).append( '.' );
        s.append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() ).append( '=' );
        s.append( PostGISMappingsISODC.DatabaseTables.datasets.name() ).append( '.' );
        s.append( PostGISMappingsISODC.CommonColumnNames.id.name() ).append( " AND " );
        s.append( formatType ).append( '.' ).append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() );
        s.append( " >= " ).append( constraint.getStartPosition() );
        s.append( " AND " ).append( formatType ).append( '.' );
        s.append( PostGISMappingsISODC.CommonColumnNames.format.name() ).append( '=' ).append( formatNumber );

        for ( PropertyNameMapping propName : builder.getPropNameMappingList() ) {
            if ( propName.getTable() == null ) {
                s.append( ' ' );
            } else {
                s.append( " AND " ).append( propName.getTable() ).append( '.' );
                s.append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() ).append( '=' );
                s.append( PostGISMappingsISODC.DatabaseTables.datasets.name() ).append( '.' );
                s.append( PostGISMappingsISODC.CommonColumnNames.id.name() );
            }
        }

        s.append( constraintExpression );

        stmt = conn.prepareStatement( s.toString() );

        if ( builder.getWhereClause().length() > 0 ) {
            for ( Object arg : builder.getWhereParams() ) {
                LOG.info( "Setting argument: " + arg );
                stmt.setObject( 1, arg );

            }
        }

        LOG.debug( "resultSet:" + stmt );

        return stmt;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.deegree.record.persistence.RecordStore#getRecordsForTransactionInsertStatement(javax.xml.stream.XMLStreamWriter
     * )
     */
    @Override
    public void getRecordsForTransactionInsertStatement( XMLStreamWriter writer, List<Integer> transactionIds )
                            throws SQLException, IOException {
        Connection conn = ConnectionManager.getConnection( connectionId );
        ResultSet rsInsertedDatasets = null;

        StringBuilder s = new StringBuilder().append( " SELECT " );
        s.append( formatTypeInISORecordStore.get( SetOfReturnableElements.brief ) );
        s.append( PostGISMappingsISODC.CommonColumnNames.data.name() );
        s.append( " FROM " ).append( PostGISMappingsISODC.DatabaseTables.datasets.name() );
        s.append( ',' ).append( formatTypeInISORecordStore.get( SetOfReturnableElements.brief ) );
        s.append( " WHERE " ).append( formatTypeInISORecordStore.get( SetOfReturnableElements.brief ) );
        s.append( '.' ).append( PostGISMappingsISODC.CommonColumnNames.fk_datasets.name() );
        s.append( '=' ).append( PostGISMappingsISODC.DatabaseTables.datasets.name() );
        s.append( '.' ).append( PostGISMappingsISODC.CommonColumnNames.id.name() );
        s.append( " AND " ).append( formatTypeInISORecordStore.get( SetOfReturnableElements.brief ) );
        s.append( '.' ).append( PostGISMappingsISODC.CommonColumnNames.id.name() ).append( " = ?" );

        for ( int i : transactionIds ) {
            PreparedStatement stmt = conn.prepareStatement( s.toString() );
            stmt.setObject( 1, i );
            rsInsertedDatasets = stmt.executeQuery();
            writeResultSet( rsInsertedDatasets, writer );
            stmt.close();

        }

        if ( rsInsertedDatasets != null ) {
            rsInsertedDatasets.close();
        }
        conn.close();

    }

    /**
     * This method writes the resultSet from the database with the writer to an XML-output.
     * 
     * @param resultSet
     *            that should search the backend
     * @param writer
     *            that writes the data to the output
     * @throws SQLException
     */
    private void writeResultSet( ResultSet resultSet, XMLStreamWriter writer )
                            throws SQLException {
        boolean idIsMatching = false;
        InputStreamReader isr = null;
        Charset charset = encoding == null ? Charset.defaultCharset() : Charset.forName( encoding );
        while ( resultSet.next() ) {
            idIsMatching = true;
            BufferedInputStream bais = new BufferedInputStream( resultSet.getBinaryStream( 1 ) );

            try {
                isr = new InputStreamReader( bais, charset );
            } catch ( Exception e ) {

                LOG.debug( "error: " + e.getMessage(), e );
            }

            readXMLFragment( isr, writer );

        }

        if ( idIsMatching == false ) {

            throw new InvalidParameterValueException();
        }

    }

    /**
     * Reads a valid XML fragment
     * 
     * @param isr
     * @param xmlWriter
     */
    private void readXMLFragment( InputStreamReader isr, XMLStreamWriter xmlWriter ) {

        // XMLStreamReader xmlReaderOut;

        XMLStreamReader xmlReader;
        try {
            // FileOutputStream fout = new FileOutputStream("/home/thomas/Desktop/test.xml");
            // XMLStreamWriter out = XMLOutputFactory.newInstance().createXMLStreamWriter( fout );

            xmlReader = XMLInputFactory.newInstance().createXMLStreamReader( isr );

            // skip START_DOCUMENT
            xmlReader.nextTag();

            // XMLAdapter.writeElement( out, xmlReader );

            XMLAdapter.writeElement( xmlWriter, xmlReader );
            // fout.close();
            xmlReader.close();

        } catch ( XMLStreamException e ) {
            LOG.debug( "error: " + e.getMessage(), e );
        } catch ( FactoryConfigurationError e ) {
            LOG.debug( "error: " + e.getMessage(), e );
        }
        // catch ( FileNotFoundException e ) {
        // // TODO Auto-generated catch block
        // LOG.debug( "error: " + e.getMessage(), e );
        // } catch ( IOException e ) {
        // // TODO Auto-generated catch block
        // LOG.debug( "error: " + e.getMessage(), e );
        // }

    }

}
