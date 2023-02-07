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

import java.text.ParseException;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * For each dimension, a String specifies
 * what indexes are selected. Indexes are 0-based The following syntax is
 * supported: - a comma "," separates independent blocks - a semicolon ":"
 * indicates a range, with bounds included, 0:4 will select 0, 1, 2, 3 and 4 -
 * two semicolons "x:y:z" can serve to indicate a step size. For instance "0:2:4"
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
					throw new Exception("Error step size is zero.");
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
							list.add((int) index);
						}
					}
					else {
						for (double index = startInclusive; index >= endInclusive; index +=
							step)
						{
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

	static double cvt(String arg, int length) {
		double value = Double.parseDouble(arg);
		if (value < 0) {
			value = length + value;
		}
		return value;
	}

	// convert argument to value : let it be negative for step size
	static double cvtDouble(String arg, int length) {
		return Double.parseDouble(arg);
	}

	/**
	 * @return as usual, a nicer string representation of this object, in this
	 *         case the expression given
	 */
	@Override
	public String toString() {
		return expression;
	}

	public static void main(String... args) {
		// A few examples
		int maxDisplay = 200;

		TestExpression("", 0, maxDisplay);
		TestExpression("", 1, maxDisplay);
		TestExpression("", 10, maxDisplay);

		TestExpression(null, 0, maxDisplay);
		TestExpression(null, 1, maxDisplay);
		TestExpression(null, 10, maxDisplay);

		TestExpression("0", 0, maxDisplay);
		TestExpression("0", 1, maxDisplay);
		TestExpression("0:0", 0, maxDisplay);
		TestExpression("0:1", 1, maxDisplay);
		TestExpression("0:0", 1, maxDisplay);
		TestExpression("0:-1", 1, maxDisplay);
		TestExpression("0:-1", 10, maxDisplay);
		TestExpression("0:9", 10, maxDisplay);
		TestExpression("0:2:-1", 10, maxDisplay);
		TestExpression("0:2:9", 10, maxDisplay);
		TestExpression("-1:-2:0", 10, maxDisplay);
		TestExpression("9:-2:0", 10, maxDisplay);
		TestExpression("-1:-1:0", 10, maxDisplay);
		TestExpression("9:-1:0", 10, maxDisplay);
		TestExpression("0:0.5:-1", 10, maxDisplay);
		TestExpression("0:0.5:9", 10, maxDisplay);
		TestExpression("0:0.5:9,9", 10, maxDisplay);
		TestExpression("-1:-0.5:0", 10, maxDisplay);
		TestExpression("9:-0.5:0", 10, maxDisplay);
		TestExpression("0:0.5:-1,0:0.5:9", 10, maxDisplay);
		TestExpression("-1:-0.5:0,9:-0.5:0", 10, maxDisplay);

	}

	/**
	 * A convenience function to test some expressions
	 * 
	 * @param expression expression
	 * @param length length of the array which is being 'sliced'
	 * @param maxDisplayed I don't know
	 */
	public static void TestExpression(String expression, int length,
		int maxDisplayed)
	{
		try {
			System.out.println("Testing " + expression + " with length = " + length);
			List<Integer> out = new IntRangeParser(expression).get(length);
			if (out.size() < maxDisplayed) {
				out.forEach((i) -> System.out.print(i + ","));
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
