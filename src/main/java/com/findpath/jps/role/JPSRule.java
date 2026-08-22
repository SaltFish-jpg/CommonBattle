package com.findpath.jps.role;


import com.findpath.jps.Grid;
import com.findpath.jps.GridQuery;

import java.util.Collection;
import java.util.Map;

public interface JPSRule {

    boolean canDiagonal(GridQuery query, Grid parent, Grid current, int dx, int dy);

    boolean hasForcedNeighbour(GridQuery query, Grid current, int dx, int dy);

    Collection<Grid> findNeighbours(GridQuery query, Grid current, Map<Grid, Grid> parentMap);
}
