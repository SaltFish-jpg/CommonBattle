package com.findpath.findPathPainter.model;

import com.findpath.jps.Grid;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;


@Getter
@Setter
public class JpsCompareGrid extends Grid implements Comparable<JpsCompareGrid> {

	private double g;
	private double f;

	public static JpsCompareGrid valueOf(int x, int y, int type) {
		JpsCompareGrid grid = new JpsCompareGrid();
		grid.setX(x);
		grid.setY(y);
		grid.setType(type);
		return grid;
	}

	@Override
	public int compareTo(JpsCompareGrid o) {
		return Double.compare(f, o.f);
	}

	@Override
	public boolean equals(Object o) {
		// 注意这个有可能有子类,所以不需要对比class,不要用自动生成的
		if (this == o) {
			return true;
		}
		if (o == null) {
			return false;
		}
		JpsCompareGrid grid = (JpsCompareGrid) o;
		return this.isSameGrid(grid);
	}

	@Override
	public int hashCode() {
		return Objects.hash(getX(), getY());
	}

}
