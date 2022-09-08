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

import java.text.ParseException;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * TODO : all of this below is a wish... For each dimension, a String specifies
 * what indexes are selected. Indexes are 0-based The following syntax is
 * supported: - a comma "," separates independent blocks - a semi colon ":"
 * indicates a range, with bounds included, 0:4 will select 0, 1, 2, 3 and 4 -
 * two semi colons "::" can serve to indicate a step size. For instance "0:2:4"
 * will select 0,2 and 4 the step is added to the left argument. So "0:2:5" will
 * contain 0,2,4 and not 1,3,5 - 'end' indicates the index of the last element
 * of the array some arithmetic can be performed on the indexes ( + - / * ) We
 * do not allow for element removal, for instance 1:10 \ 5:6 to indicate
 * 1,2,3,4,7,8,9,10 (from 1 to 10 minus 5 and 6) a potential issue is: should we
 * remove all elements 5 and 6 ? or just the last ones ? this could be ambiguous
 */
public class IntRangeParser {

	final String expression;

	public IntRangeParser(String expression) {
		this.expression = expression;
	}

	/**
	 * @param length the length in maximum of the total list
	 * @return gnagna
	 * @throws Exception parsing error usually
	 */
	public List<Integer> get(int length) throws Exception {
		if (expression == null || expression.trim().equals("")) {
			return IntStream.range(0, length).boxed().collect(Collectors.toList());
		}

		String[] blocks = expression.split(",");
		LinkedList<Integer> list = new LinkedList<>();

		int location = 0;
		for (String block : blocks) {
			location += block.length();
			String[] params = block.split(":");
			if (params.length == 1) {
				list.add((int) cvt(block, length));
			}
			else if (params.length == 2) {
				// range with min and max (inclusive)
				int startInclusive = (int) cvt(params[0], length); // bounds are int
				int endInclusive = (int) cvt(params[1], length);
				if (endInclusive < startInclusive) {
					throw new Exception("Wrong range : max (" + endInclusive +
						") is inferior to min (" + startInclusive + ")");
				}
				list.addAll(IntStream.range(startInclusive, endInclusive + 1).boxed()
					.collect(Collectors.toList()));
			}
			else if (params.length == 3) {
				// range with min and max (inclusive)
				int startInclusive = (int) cvt(params[0], length); // bounds are int
				int endInclusive = (int) cvt(params[2], length);
				double step = cvtDouble(params[1], length);
				if (step == 0) {
					// if (startInclusive==endInclusive) {
					// list.add(startInclusive); // Weird edge case
					// } else {
					throw new Exception("Error step size is zero.");
					// }
				}
				else {
					if ((step > 0) && (endInclusive < startInclusive)) {
						throw new Exception("Wrong range : max (" + endInclusive +
							") is inferior to min (" + startInclusive +
							") with a positive step (" + step + ").");
					}
					if ((step < 0) && (endInclusive > startInclusive)) {
						throw new Exception("Wrong range : max (" + endInclusive +
							") is superior to min (" + startInclusive +
							") with a negative step (" + step + ").");
					}
					if (step > 0) {
						for (double index = startInclusive; index <= endInclusive; index +=
							step)
						{
							// System.out.println(index);
							list.add((int) index);
						}
					}
					else {
						for (double index = startInclusive; index >= endInclusive; index +=
							step)
						{
							// System.out.println(index);
							list.add((int) index);
						}
					}
				}
			}
			else {
				throw new ParseException(
					"Wrong number of arguments in index expression", location);
			}

		}

		// Range check
		for (Integer i : list) {
			if (i < 0) throw new Exception("Invalid negative index (" + i + ")"); // this
																																						// should
																																						// never
																																						// happen
			if (i >= length) throw new Exception("Out of bounds index (" + i +
				") found in expression");
		}

		return list;
	}

	// convert argument to value : if the value is negative, then subtract to the
	// end

	static double cvt(String arg, int length) throws Exception {
		double value = Double.valueOf(arg);
		if (value < 0) {
			value = length + value;
		}
		return value;
	}

	// convert argument to value : let it be negative for step size
	static double cvtDouble(String arg, int length) throws Exception {
		return Double.valueOf(arg);
	}

	/**
	 * @return as usual, a nicer string representation of this object, in this
	 *         case the expression given
	 */
	@Override
	public String toString() {
		return expression;
	}

	public static void main(String... args) throws Exception {
		// A few examples
		int maxDisplay = 200;

		TestExpression("", 0, maxDisplay); // Throw error : out of bounds
		TestExpression("", 1, maxDisplay); // Throw error : out of bounds
		TestExpression("", 10, maxDisplay); // Throw error : out of bounds

		TestExpression(null, 0, maxDisplay); // Throw error : out of bounds
		TestExpression(null, 1, maxDisplay); // Throw error : out of bounds
		TestExpression(null, 10, maxDisplay); // Throw error : out of bounds

		TestExpression("0", 0, maxDisplay); // Throw error : out of bounds
		TestExpression("0", 1, maxDisplay); // Throw error : out of bounds
		TestExpression("0:0", 0, maxDisplay); // Throw error : out of bounds
		TestExpression("0:1", 1, maxDisplay); // Throw error : out of bounds
		TestExpression("0:0", 1, maxDisplay); // Throw error : out of bounds
		TestExpression("0:-1", 1, maxDisplay); // Throw error : out of bounds
		TestExpression("0:-1", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("0:9", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("0:2:-1", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("0:2:9", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("-1:-2:0", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("9:-2:0", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("-1:-1:0", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("9:-1:0", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("0:0.5:-1", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("0:0.5:9", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("0:0.5:9,9", 10, maxDisplay); // Throw error : to duplicate
																									// last element
		TestExpression("-1:-0.5:0", 10, maxDisplay); // Throw error : out of bounds
		TestExpression("9:-0.5:0", 10, maxDisplay); // Throw error : out of bounds

		TestExpression("0:0.5:-1,0:0.5:9", 10, maxDisplay); // Throw error : out of
																												// bounds
		TestExpression("-1:-0.5:0,9:-0.5:0", 10, maxDisplay); // Throw error : out
																													// of bounds

	}

	/**
	 * A convenience function to test some expressions
	 * 
	 * @param expression expression
	 * @param length length of the array which is being 'sliced'
	 * @param maxDisplayed I don t know
	 */
	public static void TestExpression(String expression, int length,
		int maxDisplayed)
	{
		try {
			System.out.println("Testing " + expression + " with length = " + length);
			List<Integer> out = new IntRangeParser(expression).get(length);
			if (out.size() < maxDisplayed) {
				out.stream().forEach((i) -> System.out.print(i + ","));
				System.out.println();
			}
			else {
				System.out.println("More than " + maxDisplayed + " elements, first = " +
					out.get(0) + " last = " + out.get(out.size() - 1));
			}
		}
		catch (Exception e) {
			System.out.println("Exception: " + e.getMessage());
		}
	}

}
