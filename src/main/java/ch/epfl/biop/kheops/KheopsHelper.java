/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2024 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
import ch.epfl.biop.bdv.img.imageplus.ImagePlusToSpimData;
import ch.epfl.biop.bdv.img.opener.OpenerSettings;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import loci.formats.IFormatReader;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Channel;
import net.imglib2.cache.LoaderCache;
import net.imglib2.cache.ref.BoundedSoftRefLoaderCache;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.quantity.Time;
import ome.units.unit.Unit;
import ome.xml.meta.MetadataConverter;
import ome.xml.meta.MetadataRetrieve;
import ome.xml.meta.MetadataStore;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.scijava.Context;
import spimdata.util.Displaysettings;

import java.io.File;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class KheopsHelper {

    public static SourcesInfo getSourcesFromFile(String path,int tileX,
                                                            int tileY,
                                                            int maxCacheSize,
                                                            int nParallelJobs,
                                                            boolean splitRGB,
                                                            String position_convention,
                                                            Context context) {

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
                            .splitRGBChannels(splitRGB)
                            .positionConvention(position_convention)
                            .context(context)
            );
        }

        AbstractSpimData<?> asd = OpenersToSpimData.getSpimData(openerSettings);

        boolean result = boundSpimDataCache(asd, maxCacheSize*nParallelJobs, nParallelJobs, nParallelJobs);
        if (!result) System.out.println("Warning: could not bound cache of spimdata. The memory may get full.");

        Map<Integer, SourceAndConverter> idToSource = new SourceAndConverterFromSpimDataCreator(asd).getSetupIdToSourceAndConverter();

        SourcesInfo info = new SourcesInfo();
        OpenersImageLoader loader = ((OpenersImageLoader)(asd.getSequenceDescription().getImgLoader()));
        info.readerPool = (ResourcePool<IFormatReader>) loader.openers.get(0).getPixelReader();

        idToSource.keySet()
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

        for (int id = 0; id<nSources; id++) {
            SourceAndConverter source = idToSource.get(id);
            if (info.idToSeriesIndex.get(id)!=null) {
                int sn_id = info.idToSeriesIndex.get(id).getId();
                if (!info.idToSources.containsKey(sn_id)) {
                    info.idToSources.put(sn_id, new ArrayList<>());
                }
                info.idToSources.get(sn_id).add(source);
            } else {
                IJ.log("Id "+id+" will not be exported. Note that 16 bits RGB images are unsupported.");
            }
        }

        return info;
    }

    public static class SourcesInfo {
        public final Map<Integer, List<SourceAndConverter>> idToSources = new HashMap<>();
        public final Map<Integer, ImageName> idToImageName = new HashMap<>();
        public final Map<Integer, SeriesIndex> idToSeriesIndex = new HashMap<>();
        public final Map<Integer, String> idToChannels = new HashMap<>();
        public final Map<Integer, Integer> seriesToId = new HashMap<>();
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

    public static void writeElapsedTime(Instant start, Consumer<String> logger, String message) {
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        String fullMessage = message+":  "+DurationFormatUtils.formatDuration(elapsed, "H:mm:ss", true);
        logger.accept(fullMessage);
        IJ.log(fullMessage);
    }

    public static void transferSeriesMeta(MetadataRetrieve metaSrc, int seriesSrc, MetadataStore metaDst, int seriesDst) {

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
        //metaDst.setPixelsDimensionOrder(metaSrc.getPixelsDimensionOrder(seriesSrc), seriesDst);
        //metaDst.setStageLabelName(metaSrc.getStageLabelName(seriesSrc), seriesDst);
        //metaDst.setStageLabelX(metaSrc.getStageLabelX(seriesSrc), seriesDst);
        //metaDst.setStageLabelY(metaSrc.getStageLabelY(seriesSrc), seriesDst);
        //metaDst.setStageLabelZ(metaSrc.getStageLabelZ(seriesSrc), seriesDst);

        /*String instrumentRef = metaSrc.getImageInstrumentRef(seriesSrc);

        if (instrumentRef!=null) {
            for (int idx_instrument = 0; idx_instrument<metaSrc.getInstrumentCount(); idx_instrument++) {
                String id = metaSrc.getInstrumentID(idx_instrument);
                System.out.println(id+" vs "+instrumentRef);
                if (id.equals(instrumentRef)) {

                }
            }
        }*/


        // Per plane
        int planeCount = metaSrc.getPlaneCount(seriesSrc);
        for (int i = 0; i<planeCount; i++) {
            transferPlaneMeta(metaSrc,seriesSrc,i,metaDst,seriesDst,i);
        }

        int sizeC = metaSrc.getChannelCount(seriesSrc); // ? 1: metaSrc.getPixelsSizeC(seriesSrc).getValue();
        for (int ch = 0; ch<sizeC;ch++) {
            MetadataConverter.convertChannels(metaSrc, seriesSrc, ch, metaDst, seriesDst, ch, true);
        }
    }

    public static void transferPlaneMeta(MetadataRetrieve metaSrc, int seriesSrc, int planeSrc, MetadataStore metaDst, int seriesDst, int planeDst) {
        if (metaSrc.getPlaneCount(seriesSrc)>planeSrc) {
            Time t = metaSrc.getPlaneExposureTime(seriesSrc, planeSrc);
            if (t != null) metaDst.setPlaneExposureTime(t, seriesDst, planeDst);
            Time dt = metaSrc.getPlaneDeltaT(seriesSrc, planeSrc);
            if (dt != null) metaDst.setPlaneDeltaT(dt, seriesDst, planeDst);
            Length px = metaSrc.getPlanePositionX(seriesSrc, planeSrc);
            if (px != null) metaDst.setPlanePositionX(px, seriesDst, planeDst);
            Length py = metaSrc.getPlanePositionY(seriesSrc, planeSrc);
            if (py != null) metaDst.setPlanePositionY(py, seriesDst, planeDst);
            Length pz = metaSrc.getPlanePositionZ(seriesSrc, planeSrc);
            if (pz != null) metaDst.setPlanePositionZ(pz, seriesDst, planeDst);
        } else {
            //No metadata for plane "planeSrc"
        }
    }

    public static SourcesInfo getSourcesFromImage(ImagePlus image,
                                                 int maxCacheSize,
                                                 int nParallelJobs) {


        AbstractSpimData<?> asd = ImagePlusToSpimData.getSpimData(image); //OpenersToSpimData.getSpimData(openerSettings);

        boolean result = boundSpimDataCache(asd, maxCacheSize*nParallelJobs, nParallelJobs, nParallelJobs);
        if (!result) System.out.println("Warning: could not bound cache of spimdata. The memory may get full.");

        Map<Integer, SourceAndConverter> idToSource = new SourceAndConverterFromSpimDataCreator(asd).getSetupIdToSourceAndConverter();

        SourcesInfo info = new SourcesInfo();

        idToSource.keySet()
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

        for (int id = 0; id<nSources; id++) {
            SourceAndConverter source = idToSource.get(id);
            int sn_id = 0;
            if (!info.idToSources.containsKey(sn_id)) {
                info.idToSources.put(sn_id, new ArrayList<>());
            }
            info.idToSources.get(sn_id).add(source);
        }

        return info;
    }

    public static Unit<Length> getUnitFromCalibration(Calibration cal) {

        assert cal != null;
        Unit<Length> u = BioFormatsHelper.getUnitFromString(cal.getUnit());
        if (u!=null) return u;

        switch (cal.getUnit()) {
            case "um":
            case "\u03BCm":
            case "\u03B5m":
            case "µm":
            case "micron":
            case "micrometer":
                return UNITS.MICROMETER;
            case "mm":
            case "millimeter":
                return UNITS.MILLIMETER;
            case "cm":
            case "centimeter":
                return UNITS.CENTIMETER;
            case "m":
            case "meter":
                return UNITS.METRE;
            default:
                return UNITS.REFERENCEFRAME;
        }
    }

}
