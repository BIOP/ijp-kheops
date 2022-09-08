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

package ch.epfl.biop.kheops.ometiff;

import org.scijava.task.Task;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class TileIterator implements Iterator<TileIterator.IntsKey> {

	final AtomicLong nTilesInQueue = new AtomicLong();
	final int maxTilesInQueue;

	final int nr;
	final int nt;
	final int nc;
	final int nz;
	final Map<Integer, Integer> resToNY;
	final Map<Integer, Integer> resToNX;

	int ir = 0;
	int it = 0;
	int ic = 0;
	int iz = 0;
	int iy = 0;
	int ix = -1; // first iteration

	public TileIterator(int nr, int nt, int nc, int nz,
		Map<Integer, Integer> resToNY, Map<Integer, Integer> resToNX,
		int maxTilesInQueue)
	{
		this.nr = nr;
		this.nt = nt;
		this.nc = nc;
		this.nz = nz;
		this.resToNY = resToNY;
		this.resToNX = resToNX;
		this.maxTilesInQueue = maxTilesInQueue;
	}

	@Override
	public synchronized boolean hasNext() {
		boolean last = (ir == nr - 1) && (it == nt - 1) && (ic == nc - 1) &&
			(iz == nz - 1) && (iy == resToNY.get(ir) - 1) && (ix == resToNX.get(ir) -
				1);
		return !last;
	}

	@Override
	public synchronized IntsKey next() {
		ix++;
		if (ix == resToNX.get(ir)) {
			ix = 0;
			iy++;
			if (iy == resToNY.get(ir)) {
				// iy == resToNY.get(nr)
				iy = 0;
				iz++;
				if (iz == nz) {
					// iz == resToNZ.get(nr)
					iz = 0;
					ic++;
					if (ic == nc) {
						// iz == resToNZ.get(nr)
						ic = 0;
						it++;
						if (it == nt) {
							// iz == resToNZ.get(nr)
							it = 0;
							ir++;
							if (ir == nr) {
								return null; // Done!
							}
						}
					}
				}
			}
		}
		while (nTilesInQueue.get() >= maxTilesInQueue) {
			synchronized (nTilesInQueue) {
				try {
					nTilesInQueue.wait();
				}
				catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		nTilesInQueue.incrementAndGet();
		if (task != null) task.setProgressValue(nTilesInQueue.get());
		return new IntsKey(new int[] { ir, it, ic, iz, iy, ix });
	}

	public void decrementQueue() {
		nTilesInQueue.decrementAndGet();
		synchronized (nTilesInQueue) {
			nTilesInQueue.notifyAll();
		}
	}

	Task task = null;

	public void setTask(Task task) {
		this.task = task;
		task.setProgressMaximum(maxTilesInQueue);
	}

	public static final class IntsKey {

		public final int[] array;

		public IntsKey(int[] array) {
			this.array = array;
		}

		public int[] getArray() {
			return array.clone();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			IntsKey bytesKey = (IntsKey) o;
			return Arrays.equals(array, bytesKey.array);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(array);
		}
	}
}
