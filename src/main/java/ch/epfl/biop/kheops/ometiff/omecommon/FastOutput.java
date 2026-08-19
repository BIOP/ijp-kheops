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
package ch.epfl.biop.kheops.ometiff.omecommon;

import loci.common.Location;

import java.io.File;
import java.io.IOException;

/**
 * Routes one output file through {@link PreallocatingFileHandle} for the time of
 * an export. Temporary, see {@link ch.epfl.biop.kheops.ometiff.omecommon}.
 * <p>
 * Bio-Formats writers get their handle from {@link Location#getHandle(String)},
 * so {@link Location#mapFile} is the only way in. Two things about how
 * {@code OMETiffWriter} uses that handle decide whether this works at all, and
 * both were found the hard way:
 * <ul>
 * <li>The writer <b>closes the output twice</b>. The first close comes from
 * {@code TiffWriter.setId} -&gt; {@code setupTiffSaver}, before a single pixel
 * has been written. Unmapping there gives the file back to a stock handle for
 * the whole image and the export is no faster at all, so the handle is reopened
 * and stays mapped.</li>
 * <li>The second close comes from {@code OMETiffWriter.saveComment}, which
 * appends the OME-XML through a {@code RandomAccessInputStream} <b>and</b> a
 * {@code RandomAccessOutputStream} opened on the same path, and needs them to be
 * two independent handles with their own positions. A mapped handle is one
 * shared object, and the comment then lands in the wrong place: the output is
 * silently a plain TIFF with no OME-XML at all. So the mapping is dropped there
 * and that phase runs on stock handles - it appends a few tens of kilobytes,
 * which costs nothing.</li>
 * </ul>
 * <h2>Scope of the mapping</h2>
 *
 * {@code Location}'s mapped-file table is a {@code ThreadLocal}, not global
 * state, so this affects only the thread running the export and only the one
 * path given here. Other code in the same JVM - the rest of Fiji, another
 * plugin reading images at the same time - never sees it.
 * <p>
 * Two consequences:
 * <ul>
 * <li>The writer has to run on the thread that created this object, which is
 * the case in {@code OMETiffExporter.export()}. If that ever changes, the
 * mapping is simply not picked up: the export stays correct and goes back to
 * being slow, with nothing to indicate it.</li>
 * <li>Exports run on pooled threads, and a {@code ThreadLocal} entry outlives
 * the task that set it, so the mapping must always be removed - hence
 * {@link AutoCloseable}. A leaked entry would hand a later, unrelated task on
 * the same pooled thread a closed handle for that path.</li>
 * </ul>
 */
public class FastOutput implements AutoCloseable {

	private final String id;
	/** null when someone else already owns this path, see the constructor */
	private final Handle handle;

	/**
	 * Routes {@code file} through a preallocating handle until this object is
	 * closed. The file must not exist yet, and the caller must not have opened it.
	 * <p>
	 * If the path is already mapped - the caller wrapped the same export twice,
	 * or a previous export leaked its mapping - this instance does nothing and
	 * lets the existing one do its job. That is not a nicety: a second handle
	 * would start with an empty content length and truncate the finished file to
	 * zero bytes on close.
	 *
	 * @param file the output the writer is about to create
	 * @throws IOException if the file cannot be created
	 */
	public FastOutput(File file) throws IOException {
		this.id = file.getAbsolutePath();
		if (Location.getMappedFile(id) != null) {
			this.handle = null;
			return;
		}
		this.handle = new Handle(file);
		Location.mapFile(id, handle);
	}

	/** Drops the mapping and gives back the padding. Idempotent. */
	@Override
	public void close() throws IOException {
		if (handle == null) return; // not ours to close
		Location.mapFile(id, null);
		handle.reallyClose();
	}

	/** A handle that survives the writer's first close, see the class javadoc */
	private final class Handle extends PreallocatingFileHandle {

		private int closes = 0;

		Handle(File file) throws IOException {
			super(file, "rw");
		}

		@Override
		public void close() throws IOException {
			if (++closes == 1) {
				// TiffWriter.setId closes the output and reopens it right away. Stay
				// open and mapped, but look like the new handle it expects to get.
				resetForReopen();
				return;
			}
			// OMETiffWriter.saveComment is next and needs its own handles
			Location.mapFile(id, null);
			super.close();
		}

		/** Safe whatever the writer did: the parent close() tolerates being called twice */
		void reallyClose() throws IOException {
			super.close();
		}
	}
}
