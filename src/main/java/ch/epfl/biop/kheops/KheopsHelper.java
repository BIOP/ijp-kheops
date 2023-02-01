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
import ch.epfl.biop.bdv.img.OpenersImageLoader;
import ch.epfl.biop.bdv.img.OpenersToSpimData;
import ch.epfl.biop.bdv.img.ResourcePool;
import ch.epfl.biop.bdv.img.bioformats.BioFormatsHelper;
import ch.epfl.biop.bdv.img.bioformats.entity.SeriesIndex;
import ch.epfl.biop.bdv.img.entity.ImageName;
import ch.epfl.biop.bdv.img.opener.OpenerSettings;
import ij.IJ;
import loci.formats.IFormatReader;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Channel;
import net.imglib2.cache.LoaderCache;
import net.imglib2.cache.ref.BoundedSoftRefLoaderCache;
import ome.units.quantity.Time;
import ome.xml.meta.MetadataConverter;
import ome.xml.meta.MetadataRetrieve;
import ome.xml.meta.MetadataStore;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.scijava.Context;
import org.scijava.log.Logger;
import spimdata.util.Displaysettings;

import java.io.File;
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
                                                            int nParallelJobs,
                                                            boolean splitRGB,
                                                            String position_convention,
                                                            Context context) {

       /* OpenerSettings settings = new OpenerSettings()
                .bioFormatsBuilder()
                .location(path)
                .cacheBlockSize(tileX, tileY, 1)
                .readerPoolSize(nParallelJobs);*/

        List<OpenerSettings> openerSettings = new ArrayList<>();
        File f = new File(path);
        int nSeries = BioFormatsHelper.getNSeries(f);
        for (int i = 0; i < nSeries; i++) {
            openerSettings.add(
                    OpenerSettings.BioFormats()
                            .location(f)
                            .setSerie(i)
                            .micrometer()
                            .cacheBlockSize(tileX,tileY, 1)
                            .readerPoolSize(nParallelJobs)
                            //.unit(unit)
                            .splitRGBChannels(splitRGB)
                            //.positionConvention(position_convention)
                            .cornerPositionConvention()
                            .context(context)
            );
        }

        //spimdata = OpenersToSpimData.getSpimData(openerSettings);

        AbstractSpimData<?> asd = OpenersToSpimData.getSpimData(openerSettings);

        boundSpimDataCache(asd, maxCacheSize*nParallelJobs, nParallelJobs, nParallelJobs);

        Map<Integer, SourceAndConverter> idToSource = new SourceAndConverterFromSpimDataCreator(asd).getSetupIdToSourceAndConverter();

        SourcesInfo info = new SourcesInfo();
        OpenersImageLoader loader = ((OpenersImageLoader)(asd.getSequenceDescription().getImgLoader()));
        info.readerPool = (ResourcePool<IFormatReader>) loader.openers.get(0).getPixelReader();

        idToSource.keySet().stream()
                .forEach(id -> {
                    BasicViewSetup bvs = asd.getSequenceDescription().getViewSetups().get(id);
                    SeriesIndex si = bvs.getAttribute(SeriesIndex.class);
                    Channel channel = bvs.getAttribute(Channel.class);
                    ImageName imageName = bvs.getAttribute(ImageName.class);
                    if (si!=null) {
                        info.idToSeriesIndex.put(id, si);
                        info.idToChannels.put(id, channel.getName());
                        info.idToImageName.put(id, imageName);
                        info.seriesToId.put(si.getId(), id);
                    }
                    Displaysettings displaysettings = bvs.getAttribute(Displaysettings.class);
                    if (displaysettings!=null) {
                        Displaysettings.applyDisplaysettings(idToSource.get(id), displaysettings); // Applies color to sources
                    }
                });

        int nSources = idToSource.size();

        //Map<Integer, List<SourceAndConverter>> idToSacs = new HashMap<>();

        for (int id = 0; id<nSources; id++) {
            SourceAndConverter source = idToSource.get(id);
            int sn_id = info.idToSeriesIndex.get(id).getId();
            if (!info.idToSources.containsKey(sn_id)) {
                info.idToSources.put(sn_id, new ArrayList<>());
            }
            info.idToSources.get(sn_id).add(source);
        }

        return info;
    }

    public static class SourcesInfo {
        public Map<Integer, List<SourceAndConverter>> idToSources = new HashMap<>();
        public Map<Integer, ImageName> idToImageName = new HashMap<>();
        public Map<Integer, SeriesIndex> idToSeriesIndex = new HashMap<>();
        public Map<Integer, String> idToChannels = new HashMap<>();
        public Map<Integer, Integer> seriesToId = new HashMap<>();
        public ResourcePool<IFormatReader> readerPool;
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
