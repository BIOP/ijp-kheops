/*-
 * #%L
 * Commands and function for opening, conversion and easy use of bioformats format into BigDataViewer
 * %%
 * Copyright (C) 2019 - 2022 Nicolas Chiaruttini, BIOP, EPFL
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the BIOP nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
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
