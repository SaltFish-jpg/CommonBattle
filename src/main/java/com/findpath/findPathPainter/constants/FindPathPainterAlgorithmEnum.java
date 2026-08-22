package com.findpath.findPathPainter.constants;

import com.findpath.findPathPainter.model.PathPainter;
import com.findpath.findPathPainter.painter.AStarGridPainter;
import com.findpath.findPathPainter.painter.JPSGridPainter;
import com.findpath.findPathPainter.painter.WAWGridPainter;
import com.findpath.findPathPainter.ui.FindPathPanel;
import com.findpath.jps.Grid;
import com.findpath.jps.GridQuery;
import com.findpath.jps.Terrain2D;

import java.util.List;


public enum FindPathPainterAlgorithmEnum {
    /**
     * A星算法
     */
    AStar("A*") {
        @Override
		public List<Grid> findPath(Grid start, Grid end, GridQuery query, PathPainter painter, FindPathPanel panel) {
			return AStarGridPainter.findPath(query, start, end, painter);
        }
    },
    /**
     * 沿着墙走的算法
     */
    WAW("WAW") {
        @Override
		public List<Grid> findPath(Grid start, Grid end, GridQuery query, PathPainter painter, FindPathPanel panel) {
			return WAWGridPainter.findPath(query, start, end, painter, true);
        }
    },
    /**
     * 跳点算法
     */
    JPSCornerCutting("JPS可切角版") {
        @Override
        public List<Grid> findPath(Grid start, Grid end, GridQuery query, PathPainter painter, FindPathPanel panel) {
            return new JPSGridPainter(true).findPath(query, start, end, painter);
        }
    },
    /**
     * 跳点算法
     */
    JPSNoCornerCutting("JPS不切角版") {
        @Override
        public List<Grid> findPath(Grid start, Grid end, GridQuery query, PathPainter painter, FindPathPanel panel) {
            return new JPSGridPainter(false).findPath(query, start, end, painter);
        }
	},

    WAW_NO_MERGE("WAW曲线") {
        @Override
		public List<Grid> findPath(Grid start, Grid end, GridQuery query, PathPainter painter, FindPathPanel panel) {
			return WAWGridPainter.findPath(query, start, end, painter, false);
		}
	},
    ///
    ;

    private final String name;

    FindPathPainterAlgorithmEnum(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

	public GridQuery createQuery(Terrain2D terrain2D) {
		return new GridQuery(terrain2D);
	}

	public abstract List<Grid> findPath(Grid start, Grid end, GridQuery query, PathPainter pathPainter,
	        FindPathPanel panel);
}
