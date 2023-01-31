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

import bdv.img.cache.VolatileGlobalCellCache;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.n5.N5ImageLoader;
import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.bdv.img.ResourcePool;
import ch.epfl.biop.bdv.img.legacy.bioformats.BioFormatsBdvOpener;
import ch.epfl.biop.bdv.img.legacy.bioformats.BioFormatsToSpimData;
import ch.epfl.biop.bdv.img.legacy.bioformats.ReaderPool;
import ch.epfl.biop.bdv.img.legacy.bioformats.entity.SeriesNumber;
import ij.IJ;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Channel;
import net.imglib2.cache.LoaderCache;
import net.imglib2.cache.ref.BoundedSoftRefLoaderCache;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.quantity.Time;
import ome.xml.meta.MetadataConverter;
import ome.xml.meta.MetadataRetrieve;
import ome.xml.meta.MetadataStore;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.scijava.log.Logger;
import spimdata.util.Displaysettings;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KheopsHelper {

    public static SourcesInfo getSourcesFromFile(String path,int tileX,
                                                            int tileY,
                                                            int maxCacheSize,
                                                            int nParallelJobs) {
        BioFormatsBdvOpener opener =
                BioFormatsToSpimData.getDefaultOpener(path)
                        .micrometer()


                        .cacheBlockSize(tileX, tileY, 1);
                        //.queueOptions(nParallelJobs, 4)
                        //.cacheBounded(maxCacheSize*nParallelJobs);

        //IFormatReader modelReader = opener.getReaderPool().acquire();
        ReaderPool readerPool = opener.getReaderPool();


        AbstractSpimData<?> asd = BioFormatsToSpimData
                .getSpimData(
                    opener.voxSizeReferenceFrameLength(new Length(1, UNITS.MILLIMETER))
                          .positionReferenceFrameLength(new Length(1,UNITS.METER)));

        boundSpimDataCache(asd, maxCacheSize*nParallelJobs, nParallelJobs, 4);

        Map<Integer, SourceAndConverter> idToSource = new SourceAndConverterFromSpimDataCreator(asd).getSetupIdToSourceAndConverter();

        Map<Integer, SeriesNumber> idToSeriesNumber = new HashMap<>();
        Map<Integer, String> idToChannels = new HashMap<>();
        Map<Integer, Integer> seriesToId = new HashMap<>();

        idToSource.keySet().stream()
                .forEach(id -> {
                    BasicViewSetup bvs = asd.getSequenceDescription().getViewSetups().get(id);
                    SeriesNumber sn = bvs.getAttribute(SeriesNumber.class);
                    Channel channel = bvs.getAttribute(Channel.class);
                    if (sn!=null) {
                        idToSeriesNumber.put(id, sn);
                        idToChannels.put(id, channel.getName());
                        seriesToId.put(sn.getId(), id);
                    }
                    Displaysettings displaysettings = bvs.getAttribute(Displaysettings.class);
                    if (displaysettings!=null) {
                        Displaysettings.applyDisplaysettings(idToSource.get(id), displaysettings); // Applies color to sources
                    }
                });

        int nSources = idToSource.size();

        Map<Integer, List<SourceAndConverter>> idToSacs = new HashMap<>();

        for (int id = 0; id<nSources; id++) {
            SourceAndConverter source = idToSource.get(id);
            int sn_id = idToSeriesNumber.get(id).getId();
            if (!idToSacs.containsKey(sn_id)) {
                idToSacs.put(sn_id, new ArrayList<>());
            }
            idToSacs.get(sn_id).add(source);
        }

        SourcesInfo info = new SourcesInfo();

        info.idToSources = idToSacs;
        info.idToSeriesNumber = idToSeriesNumber;
        info.idToChannels = idToChannels;
        info.seriesToId = seriesToId;
        info.readerPool = readerPool;
        return info;
    }

    public static class SourcesInfo {
        public Map<Integer, List<SourceAndConverter>> idToSources;
        public Map<Integer, SeriesNumber> idToSeriesNumber;
        public Map<Integer, String> idToChannels;
        public Map<Integer, Integer> seriesToId;

        public ReaderPool readerPool;
    }

    private static boolean boundSpimDataCache(AbstractSpimData<?> asd, int nBlocks, int nThreads, int nPriorities) {
        LoaderCache loaderCache = new BoundedSoftRefLoaderCache(nBlocks);
        BasicImgLoader imageLoader = asd.getSequenceDescription().getImgLoader();
        VolatileGlobalCellCache cache = new VolatileGlobalCellCache(nPriorities, nThreads);
        // Now override the backingCache field of the VolatileGlobalCellCache
        try {
            Field backingCacheField = VolatileGlobalCellCache.class.getDeclaredField(
                    "backingCache");
            backingCacheField.setAccessible(true);
            backingCacheField.set(cache, loaderCache);
            // Now overrides the cache in the ImageLoader
            if (imageLoader instanceof Hdf5ImageLoader) {
                Field cacheField = Hdf5ImageLoader.class.getDeclaredField("cache");
                cacheField.setAccessible(true);
                cacheField.set(imageLoader, cache);
                return true;
            }
            else if (imageLoader instanceof N5ImageLoader) {
                Field cacheField = N5ImageLoader.class.getDeclaredField("cache");
                cacheField.setAccessible(true);
                cacheField.set(imageLoader, cache);
                return true;
            }
            else {
                Field cacheField = imageLoader.getClass().getDeclaredField("cache");
                cacheField.setAccessible(true);
                cacheField.set(imageLoader, cache);
                return true;
            }
        }
        catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            return false;
        }

    }

    public static void writeElapsedTime(Instant start, Logger logger, String message) {
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        String fullMessage = message+":  "+DurationFormatUtils.formatDuration(elapsed, "H:mm:ss", true);
        logger.info(fullMessage);
        IJ.log(fullMessage);
    }

    public static void copyFromMetaSeries(MetadataRetrieve metaSrc, int seriesSrc, MetadataStore metaDst, int seriesDst) {

        // Global
        metaDst.setCreator(metaSrc.getCreator());
        // Per series
        metaDst.setImageAcquisitionDate(metaSrc.getImageAcquisitionDate(seriesSrc), seriesDst);
        metaDst.setImageName(metaSrc.getImageName(seriesSrc), seriesDst);
        metaDst.setImageDescription(metaSrc.getImageDescription(seriesSrc), seriesDst);
        //metaDst.setImageExperimenterGroupRef(metaSrc.getImageExperimenterGroupRef(seriesSrc), seriesDst);
        //metaDst.setImageExperimentRef(metaSrc.getImageExperimentRef(seriesSrc), seriesDst);
        //metaDst.setImageInstrumentRef(metaSrc.getImageInstrumentRef(seriesSrc), seriesDst);
        metaDst.setPixelsPhysicalSizeX(metaSrc.getPixelsPhysicalSizeX(seriesSrc), seriesDst);
        metaDst.setPixelsPhysicalSizeY(metaSrc.getPixelsPhysicalSizeY(seriesSrc), seriesDst);
        metaDst.setPixelsPhysicalSizeZ(metaSrc.getPixelsPhysicalSizeZ(seriesSrc), seriesDst);
        metaDst.setPixelsTimeIncrement(metaSrc.getPixelsTimeIncrement(seriesSrc), seriesDst);
        //metaDst.setStageLabelName(metaSrc.getStageLabelName(seriesSrc), seriesDst);
        //metaDst.setStageLabelX(metaSrc.getStageLabelX(seriesSrc), seriesDst);
        //metaDst.setStageLabelY(metaSrc.getStageLabelY(seriesSrc), seriesDst);
        //metaDst.setStageLabelZ(metaSrc.getStageLabelZ(seriesSrc), seriesDst);

        // Per plane
        int planeCount = metaSrc.getPlaneCount(seriesSrc);
        for (int i = 0; i<planeCount; i++) {
            Time t = metaSrc.getPlaneExposureTime(seriesSrc, i);
            if (t!=null) {
                metaDst.setPlaneExposureTime(metaSrc.getPlaneExposureTime(seriesSrc, i), seriesDst, i);
                metaDst.setPlaneDeltaT(metaSrc.getPlaneDeltaT(seriesSrc, i), seriesDst,i);
                metaDst.setPlanePositionX(metaSrc.getPlanePositionX(seriesSrc,i),seriesDst,i);
                metaDst.setPlanePositionY(metaSrc.getPlanePositionY(seriesSrc,i),seriesDst,i);
                metaDst.setPlanePositionZ(metaSrc.getPlanePositionZ(seriesSrc,i),seriesDst,i);
            }
        }

        int sizeC = metaSrc.getChannelCount(seriesSrc); // ? 1: metaSrc.getPixelsSizeC(seriesSrc).getValue();
        for (int ch = 0; ch<sizeC;ch++) {
            MetadataConverter.convertChannels(metaSrc, seriesSrc, ch, metaDst, seriesDst, ch, false);
        }
    }
}
