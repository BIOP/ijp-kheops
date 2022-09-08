/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2022 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

package ch.epfl.biop.kheops;

import java.util.Collections;
import java.util.List;

/**
 * Defining a CZT Range with a class
 */
public class CZTRange {

	final List<Integer> rangeC;
	final List<Integer> rangeZ;
	final List<Integer> rangeT;

	/**
	 * Javadoc needed
	 * 
	 * @param rangeC just read the name
	 * @param rangeZ just read the name
	 * @param rangeT just read the name
	 */
	public CZTRange(List<Integer> rangeC, List<Integer> rangeZ,
		List<Integer> rangeT)
	{
		this.rangeC = Collections.unmodifiableList(rangeC);
		this.rangeZ = Collections.unmodifiableList(rangeZ);
		this.rangeT = Collections.unmodifiableList(rangeT);
	}

	/**
	 * @return an array containing the number of channels, slices, and timepoints
	 */
	public int[] getCZTDimensions() {
		return new int[] { rangeC.size(), rangeZ.size(), rangeT.size() };
	}

	/**
	 * @return the range in C as a List
	 */
	public List<Integer> getRangeC() {
		return rangeC;
	}

	/**
	 * @return the range in Z as a List
	 */
	public List<Integer> getRangeZ() {
		return rangeZ;
	}

	/**
	 * @return the range in T as a List
	 */
	public List<Integer> getRangeT() {
		return rangeT;
	}

	/**
	 * @return a String representation of this CZT Range
	 */
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("C:");
		rangeC.forEach(c -> builder.append(c + ","));
		builder.append(" Z:");
		rangeZ.forEach(z -> builder.append(z + ","));
		builder.append(" T:");
		rangeT.forEach(t -> builder.append(t + ","));
		return builder.toString();
	}

	/**
	 * @return the total number of planes to expect from this range
	 */
	public long getTotalPlanes() {
		return rangeC.size() * rangeZ.size() * rangeT.size();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder to build a CZTRange from a String
	 */
	public static class Builder {

		private String expressionRangeC = "";
		private String expressionRangeZ = "";
		private String expressionRangeT = "";

		/**
		 * @param exp expression
		 * @return builder
		 */
		public Builder setC(String exp) {
			expressionRangeC = exp;
			return this;
		}

		/**
		 * @param exp expression
		 * @return builder
		 */
		public Builder setZ(String exp) {
			expressionRangeZ = exp;
			return this;
		}

		/**
		 * @param exp expression
		 * @return builder
		 */
		public Builder setT(String exp) {
			expressionRangeT = exp;
			return this;
		}

		/**
		 * Construct the CZT Range
		 * 
		 * @param nC maximal number of channels
		 * @param nZ maximal number of Slices
		 * @param nT maximal number of timepoints
		 * @return CZT range
		 * @throws Exception if the String parser is bad
		 */
		public CZTRange get(int nC, int nZ, int nT) throws Exception {
			List<Integer> rangeC = new IntRangeParser(expressionRangeC).get(nC);
			List<Integer> rangeZ = new IntRangeParser(expressionRangeZ).get(nZ);
			List<Integer> rangeT = new IntRangeParser(expressionRangeT).get(nT);

			return new CZTRange(rangeC, rangeZ, rangeT);
		}
	}
}
