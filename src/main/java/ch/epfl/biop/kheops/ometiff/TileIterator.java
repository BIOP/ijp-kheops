/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2026 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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

package ch.epfl.biop.kheops.ometiff;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class TileIterator implements Iterator<TileIterator.IntsKey> {

	final AtomicLong nTilesInQueue = new AtomicLong();
	final int maxTilesInQueue;
	/** Set by {@link #cancel()}: stops handing out tiles, and stops waiting */
	volatile boolean canceled = false;

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

	/**
	 * Stops the iterator: the tile currently being waited for is abandoned and
	 * every following call to {@link #next()} returns null.
	 * <p>
	 * {@link #next()} waits for the writing thread to consume a tile, and it does
	 * so while holding this iterator's monitor - so a canceled export would leave
	 * that worker, and every other worker behind it, blocked forever, since the
	 * writing thread has stopped calling {@link #decrementQueue()}.
	 */
	public void cancel() {
		canceled = true;
		synchronized (nTilesInQueue) {
			nTilesInQueue.notifyAll();
		}
	}

	@Override
	public synchronized IntsKey next() {
		if (canceled) return null;
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
		// The condition is tested under the monitor, so the notification cancel()
		// sends cannot be missed
		synchronized (nTilesInQueue) {
			while ((nTilesInQueue.get() >= maxTilesInQueue) && !canceled) {
				try {
					nTilesInQueue.wait();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
		}
		if (canceled) return null;
		nTilesInQueue.incrementAndGet();
		return new IntsKey(new int[] { ir, it, ic, iz, iy, ix });
	}

	public void decrementQueue() {
		nTilesInQueue.decrementAndGet();
		synchronized (nTilesInQueue) {
			nTilesInQueue.notifyAll();
		}
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
