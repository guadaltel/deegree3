//$HeadURL$
/*----------------------------------------------------------------------------
 This file is part of deegree, http://deegree.org/
 Copyright (C) 2001-2009 by:
   Department of Geography, University of Bonn
 and
   lat/lon GmbH

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

package org.deegree.crs.components;

import static org.deegree.crs.components.Unit.ARC_SEC;
import static org.deegree.crs.components.Unit.BRITISHYARD;
import static org.deegree.crs.components.Unit.DEGREE;
import static org.deegree.crs.components.Unit.METRE;
import static org.deegree.crs.components.Unit.MILLISECOND;
import static org.deegree.crs.components.Unit.RADIAN;
import static org.deegree.crs.components.Unit.SECOND;
import static org.deegree.crs.components.Unit.USFOOT;
import static org.deegree.crs.utilities.ProjectionUtils.DTR;
import junit.framework.TestCase;

import org.deegree.crs.components.Unit;
import org.junit.Test;

/**
 * <code>UnitTest</code> tests the conversion of different units into each other.
 *
 * @author <a href="mailto:bezema@lat-lon.de">Rutger Bezema</a>
 *
 * @author last edited by: $Author$
 *
 * @version $Revision$, $Date$
 *
 */
public class UnitTest extends TestCase {

    /**
     * Tests the conversion of known units and the conversion of incompatible units.
     */
    @Test
    public void testUnitConversion() {
        assertEquals( 1.0, RADIAN.getScale() );
        assertEquals( DTR, DEGREE.getScale() );
        assertTrue( DEGREE.canConvert( RADIAN ) );
        assertTrue( RADIAN.canConvert( DEGREE ) );
        assertTrue( RADIAN.canConvert( Unit.ARC_SEC ) );
        assertTrue( DEGREE.canConvert( ARC_SEC ) );
        assertTrue( !DEGREE.canConvert( METRE ) );
        assertTrue( !METRE.canConvert( DEGREE ) );
        assertTrue( METRE.canConvert( BRITISHYARD ) );
        assertTrue( USFOOT.canConvert( BRITISHYARD ) );
        assertTrue( BRITISHYARD.canConvert( METRE ) );
        double test = 6.8;
        assertEquals( Math.toRadians( test ), DEGREE.convert( test, RADIAN ) );
        assertEquals( Math.toDegrees( test ), RADIAN.convert( test, DEGREE ) );
        assertEquals( test * 1000, SECOND.convert( test, MILLISECOND ) );
        assertEquals( test / 0.3048006096012192, METRE.convert( test, USFOOT ) );

    }
}
